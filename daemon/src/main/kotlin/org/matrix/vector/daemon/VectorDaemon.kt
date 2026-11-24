package org.matrix.vector.daemon

import android.app.ActivityManager
import android.app.ActivityThread
import android.content.Context
import android.ddm.DdmHandleAppName
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.ServiceManager
import android.os.SystemProperties
import android.system.Os
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.env.CliSocketServer
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.env.LogcatMonitor
import org.matrix.vector.daemon.ipc.BRIDGE_TRANSACTION_CODE
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.SystemServerService
import org.matrix.vector.daemon.utils.applyNotificationWorkaround

private const val TAG = "VectorDaemon"
private const val ACTION_SEND_BINDER = 1

object VectorDaemon {
  private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e(TAG, "Caught fatal coroutine exception in background task!", throwable)
  }
  private val daemonInstanceId = FileSystem.createDaemonInstanceId()

  // Dispatchers.IO: Uses the shared background thread pool.
  // SupervisorJob(): Ensures one failing task doesn't kill the whole daemon.
  val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
  val bridgeServiceName = "activity"

  /**
   * Whether a `system_server` was already serving the moment this daemon started, which is how
   * Zygisk injecting into a booted system rather than into a fresh fork shows up on our side. The
   * boot it missed then has to be replayed, and it has to be met under another name.
   *
   * Set on the main thread during [main] and cleared by the death recipient on a binder thread,
   * which is what the `@Volatile` is for.
   */
  @Volatile var startedAfterSystemServer = false

  /**
   * The name the module looks the daemon up under while system_server is specialized. `serial` is
   * free at boot, because system_server registers it only much later, so a binder found there can
   * only be ours. On a booted system the real service holds it, and a lookup returning that is
   * indistinguishable from a successful rendezvous, so a name of our own is used instead.
   */
  val proxyServiceName: String
    get() = if (startedAfterSystemServer) "serial_vector" else "serial"

  /**
   * Keeps the `activity` BinderProxy reachable for as long as its death notification is needed,
   * using compareAndSet so duplicate death callbacks cannot run recovery twice.
   */
  private val bridgeBinder = AtomicReference<IBinder>()

  @JvmStatic
  fun main(args: Array<String>) {
    if (!FileSystem.tryLock()) kotlin.system.exitProcess(0)

    val ownerState = FileSystem.ensureActiveReinjectionOwner(daemonInstanceId)
    if (!ownerState.isOwner) {
      terminateStaleDaemon(
          "Daemon instance `$daemonInstanceId` is not the active reinjection owner. " +
              "Active owner=`${ownerState.owner?.toLogString() ?: "unknown"}`.")
    }

    var explicitLateInject = false
    for (arg in args) {
      if (arg == "--late-inject") {
        explicitLateInject = true
      }
    }

    // `activity` is system_server's own, registered long after the specialization in which the
    // module comes looking for us, so at boot nothing answers it and a binder there means someone
    // booted without us.
    startedAfterSystemServer =
        explicitLateInject || ServiceManager.checkService(bridgeServiceName) != null

    Log.i(
        TAG,
        "Vector-SRX daemon started: instance=$daemonInstanceId, " +
            "owner=${ownerState.owner?.toLogString()}, " +
            "afterSystemServer=$startedAfterSystemServer, proxy=$proxyServiceName")
    Log.i(
        TAG,
        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "commit ${BuildConfig.VERSION_HASH}")

    Thread.setDefaultUncaughtExceptionHandler { _, e ->
      Log.e(TAG, "Uncaught exception in Daemon", e)
      kotlin.system.exitProcess(1)
    }

    // Setup Main Looper
    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
    @Suppress("DEPRECATION") Looper.prepareMainLooper()

    // Squat on the proxy service name immediately, which creates the early IPC channel of
    // FrameworkService for our Zygisk module during system_server specialization.
    SystemServerService.registerProxyService(proxyServiceName)

    // Start Environmental Daemons
    LogcatMonitor.start()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Dex2OatServer.start()
    CliSocketServer.start()

    // Preload Framework DEX in the background
    scope.launch { FileSystem.getPreloadDex(ConfigCache.state.isDexObfuscateEnabled) }

    // Initializes system frameworks inside the daemon process
    ActivityThread.systemMain()
    DdmHandleAppName.setAppName("org.matrix.vector.daemon", 0)

    // Wait for Android core services
    waitForSystemService("package")
    waitForSystemService("activity") // current bridgeServiceName
    waitForSystemService(Context.USER_SERVICE)
    waitForSystemService(Context.APP_OPS_SERVICE)

    applyNotificationWorkaround()

    // Read this before `sendToBridge`, which leaves the main thread at euid 1000.
    val isVerboseLog = ManagerService.isVerboseLogEnabled()

    // Setup IPC channel for applications by injecting DaemonService binder
    sendToBridge(VectorService.asBinder(), false)

    if (!isVerboseLog) {
      LogcatMonitor.stopVerbose()
    }

    Looper.loop()
    throw RuntimeException("Main thread loop unexpectedly exited")
  }

  private fun waitForSystemService(name: String) = runBlocking {
    while (ServiceManager.getService(name) == null) {
      Log.i(TAG, "Waiting system service: $name for 1s")
      delay(1000)
    }
  }

  // The bridge is setup in `system_server` via Zygisk API
  @Suppress("DEPRECATION")
  private fun sendToBridge(
      binder: IBinder,
      isRestart: Boolean,
      reinjectionLease: FileSystem.ReinjectionLease? = null,
  ) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "sendToBridge MUST run on the main thread!"
    }

    if (reinjectionLease != null) {
      val ownerState = FileSystem.ensureActiveReinjectionOwner(daemonInstanceId)
      if (!ownerState.isOwner) {
        reinjectionLease.close()
        terminateStaleDaemon(
            "Daemon `$daemonInstanceId` lost reinjection ownership before restart injection. " +
                "Active owner=`${ownerState.owner?.toLogString() ?: "unknown"}`.")
      }
    }

    Os.seteuid(0)

    try {
      runCatching {
            var bridgeService: IBinder?
            if (isRestart) {
              Log.w(
                  TAG,
                  "system_server restarted for daemon `$daemonInstanceId`" +
                      reinjectionLease?.let { ", round=${it.round}" }.orEmpty())
            }

            while (true) {
              bridgeService = ServiceManager.getService(bridgeServiceName)
              if (bridgeService?.pingBinder() == true) break
              Log.i(TAG, "`$bridgeServiceName` service not ready, waiting 1s...")
              Thread.sleep(1000)
            }

            // Setup death recipient to handle system_server crashes / soft restarts
            val deathRecipient =
                object : IBinder.DeathRecipient {
                  override fun binderDied() {
                    if (!bridgeBinder.compareAndSet(bridgeService, null)) return
                    runCatching { bridgeService.unlinkToDeath(this, 0) }

                    val leaseResult = FileSystem.tryAcquireReinjectionLease(daemonInstanceId)
                    when (leaseResult.status) {
                      FileSystem.ReinjectionLeaseStatus.BUSY -> {
                        Log.i(
                            TAG,
                            "Daemon `$daemonInstanceId` saw system_server death but reinjection " +
                                "is already in progress by " +
                                "`${leaseResult.owner?.toLogString() ?: "another daemon"}`.")
                        if (leaseResult.owner?.instanceId != daemonInstanceId) {
                          terminateStaleDaemon(
                              "Daemon `$daemonInstanceId` lost restart race to " +
                                  "`${leaseResult.owner?.toLogString() ?: "another daemon"}`.")
                        }
                      }
                      FileSystem.ReinjectionLeaseStatus.NOT_OWNER -> {
                        terminateStaleDaemon(
                            "Stale daemon `$daemonInstanceId` ignored system_server death. " +
                                "Active owner=`${leaseResult.owner?.toLogString() ?: "unknown"}`.")
                      }
                      FileSystem.ReinjectionLeaseStatus.ACQUIRED -> {
                        val lease = leaseResult.lease ?: return
                        Log.w(
                            TAG,
                            "System Server died! Daemon `$daemonInstanceId` acquired reinjection " +
                                "lease (round=${lease.round}); clearing caches and re-injecting...")
                        try {
                          withRootIdentity {
                            clearSystemCaches()
                            // KernelSU/zygote soft reboot keeps this daemon alive while Android
                            // services stop/start; keep the wrapper socket and refresh mounts.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                              Dex2OatServer.refreshMount()
                            }
                            if (startedAfterSystemServer) {
                              SystemServerService.unregisterProxyService()
                            }
                            // The replacement system_server specializes as a cold start.
                            startedAfterSystemServer = false
                            SystemServerService.prepareForSystemServerRestart(
                                proxyServiceName, daemonInstanceId, lease.round)
                            ManagerService.invalidateForSystemServerRestart()
                          }
                          val posted =
                              Handler(Looper.getMainLooper()).post {
                                sendToBridge(binder, true, lease)
                              }
                          if (!posted) {
                            Log.e(
                                TAG,
                                "Failed to post reinjection round=${lease.round} to main thread.")
                            lease.close()
                          }
                        } catch (t: Throwable) {
                          lease.close()
                          throw t
                        }
                      }
                    }
                  }
                }
            bridgeBinder.set(bridgeService)
            try {
              bridgeService.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
              bridgeBinder.compareAndSet(bridgeService, null)
              throw e
            }

            // Try sending the Binder payload (up to 3 times)
            var success = false
            for (i in 0 until 3) {
              val data = Parcel.obtain()
              val reply = Parcel.obtain()
              try {
                data.writeInt(ACTION_SEND_BINDER)
                data.writeStrongBinder(binder)
                success = bridgeService.transact(BRIDGE_TRANSACTION_CODE, data, reply, 0) == true
                reply.readException()
                if (success) break
              } finally {
                data.recycle()
                reply.recycle()
              }
              Log.w(TAG, "No response from bridge, retrying...")
              Thread.sleep(1000)
            }

            if (success) {
              Log.i(TAG, "Successfully injected Vector IPC binder for applications.")
              // Only the name we invented for a late injection: `serial` is a real service name,
              // and system_server registering it is what takes our proxy out of servicemanager.
              if (startedAfterSystemServer) SystemServerService.unregisterProxyService()
            } else {
              if (!SystemServerService.systemServerRequested) {
                Log.e(TAG, "No Vector module reached system_server; leaving it running")
              } else {
                Log.e(TAG, "Vector reached system_server but its bridge never answered")
              }
            }
          }
          .onFailure { Log.e(TAG, "Error during injecting DaemonService", it) }
    } finally {
      Os.seteuid(1000)
      reinjectionLease?.close()
    }
  }

  private fun clearSystemCaches() {
    Log.i(TAG, "Clearing ServiceManager and ActivityManager caches...")
    runCatching {
          // Clear ServiceManager.sServiceManager
          var field = ServiceManager::class.java.getDeclaredField("sServiceManager")
          field.isAccessible = true
          field.set(null, null)

          // Clear ServiceManager.sCache
          field = ServiceManager::class.java.getDeclaredField("sCache")
          field.isAccessible = true
          val sCache = field.get(null)
          if (sCache is MutableMap<*, *>) {
            sCache.clear()
          }

          // Clear ActivityManager.IActivityManagerSingleton
          field = ActivityManager::class.java.getDeclaredField("IActivityManagerSingleton")
          field.isAccessible = true
          val singleton = field.get(null)
          if (singleton != null) {
            val mInstanceField =
                Class.forName("android.util.Singleton").getDeclaredField("mInstance")
            mInstanceField.isAccessible = true
            synchronized(singleton) { mInstanceField.set(singleton, null) }
          }
        }
        .onFailure { Log.w(TAG, "Failed to clear system caches via reflection", it) }
  }

  /**
   * Brings the framework down and up without rebooting the device.
   *
   * `system_server` is forked from the *primary* zygote, so restarting that is what restarts the
   * framework — on a 64/32 device the primary init service is still called `zygote` (it runs
   * app_process64) and `zygote_secondary` is the 32-bit one.
   */
  fun softReboot() {
    Log.w(TAG, "Soft reboot: restarting the primary zygote")
    forgetDeliberateBoot()
    SystemProperties.set("ctl.restart", "zygote")
  }

  /**
   * Takes the boot we are about to cause back out of PackageWatchdog's count so deliberate soft
   * reboots do not escalate RescueParty / CrashRecovery toward recovery/factory-reset prompts.
   */
  private fun forgetDeliberateBoot() {
    for (property in arrayOf("crashrecovery.rescue_boot_count", "sys.rescue_boot_count")) {
      runCatching { SystemProperties.set(property, "0") }
          .onFailure { Log.w(TAG, "Could not clear `$property` before restarting", it) }
    }
  }

  private fun terminateStaleDaemon(reason: String): Nothing {
    Log.w(TAG, "$reason Terminating stale daemon instance.")
    kotlin.system.exitProcess(0)
  }

  private inline fun <T> withRootIdentity(block: () -> T): T {
    val originalEuid = runCatching { Os.geteuid() }.getOrDefault(0)
    val switched = originalEuid != 0 && runCatching { Os.seteuid(0) }.isSuccess
    try {
      return block()
    } finally {
      if (switched) {
        runCatching { Os.seteuid(originalEuid) }
            .onFailure { Log.w(TAG, "Failed to restore euid to $originalEuid", it) }
      }
    }
  }
}
