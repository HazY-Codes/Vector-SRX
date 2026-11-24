package org.matrix.vector.daemon.ipc

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IClientCallback
import android.os.IServiceCallback
import android.os.Parcel
import android.os.ServiceManager
import android.os.Process
import android.util.Log
import org.matrix.vector.ipc.IFrameworkService
import org.matrix.vector.daemon.*
import org.matrix.vector.daemon.system.getSystemServiceManager

private const val TAG = "VectorSystemServer"

/**
 * The Android R half of [SystemServerService.registerProxyService], in a class of its own on
 * purpose.
 *
 * `android.os.IServiceCallback` does not exist before Android R, and ART resolves the superclass of
 * every type a method mentions when it verifies that method, not when the branch mentioning it
 * runs. Spelling the callback out inside [SystemServerService] therefore made the daemon's very
 * first call fail to resolve `IServiceCallback$Stub` on Android 9 and log a NoClassDefFoundError,
 * even though the version gate meant that code was never executed (issue #925). Behind a separate
 * class the resolution is deferred to this object's initialization, which the gate skips on older
 * platforms.
 */
private object ServiceRegistrationWatcher {

  // Written from the main thread at startup and from a binder thread when system_server dies.
  @Volatile private var watchedName: String? = null
  @Volatile private var unusedName: String? = null

  fun watch(serviceName: String) {
    // servicemanager keeps every callback it is handed, and a system_server restart brings the
    // daemon back through here; without this a long-lived daemon stacks up duplicates that all
    // report the same registration.
    if (watchedName == serviceName) return
    val callback =
        object : IServiceCallback.Stub() {
          // The IServiceCallback will tell us when the real Android service is ready,
          // allowing us to capture it and then naturally stop intercepting traffic.
          override fun onRegistration(name: String, binder: IBinder?) {
            if (name == serviceName && binder != null) {
              SystemServerService.adoptOriginService(name, binder)
            }
          }

          override fun asBinder(): IBinder = this
        }
    runCatching { getSystemServiceManager().registerForNotifications(serviceName, callback) }
        .onSuccess { watchedName = serviceName }
        .onFailure { Log.e(TAG, "Failed to register IServiceCallback", it) }
  }

  /**
   * Asks servicemanager to drop [serviceName] once nothing but itself is holding [binder].
   *
   * It will not drop a service on request alone. `tryUnregisterService` refuses outright while
   * the service is marked as having a client on the way, which handing the binder out is what
   * sets, and the only code that clears that mark returns early for any name without a client
   * callback — so for a service registered the ordinary way the mark is permanent and the
   * refusal is forever. Asking repeatedly changes nothing.
   *
   * Registering a callback is what puts the name on servicemanager's interval, which then clears
   * the mark and, once the last client is gone, says so. Unregistering from inside that is how a
   * lazy service shuts itself down, and it is the only sequence servicemanager accepts.
   */
  fun unregisterWhenUnused(serviceName: String, binder: IBinder) {
    if (unusedName == serviceName) return
    val callback =
        object : IClientCallback.Stub() {
          override fun onClients(registered: IBinder, hasClients: Boolean) {
            if (hasClients) return
            runCatching { getSystemServiceManager().tryUnregisterService(serviceName, registered) }
                .onSuccess { Log.d(TAG, "Dropped the proxy service `$serviceName`") }
                .onFailure { Log.d(TAG, "Proxy service `$serviceName` stays registered: $it") }
          }

          override fun asBinder(): IBinder = this
        }
    runCatching { getSystemServiceManager().registerClientCallback(serviceName, binder, callback) }
        .onSuccess { unusedName = serviceName }
        .onFailure { Log.d(TAG, "Proxy service `$serviceName` stays registered: $it") }
  }
}

/**
 * The daemon's end of the one handshake system_server gets.
 *
 * A plain [Binder] rather than an AIDL stub on purpose. system_server never holds an interface for
 * this - it reaches the daemon by transacting [BRIDGE_TRANSACTION_CODE] on whatever binder the
 * hijacked service name resolves to, which [onTransact] answers directly. An AIDL interface here
 * would generate a dispatch table nothing ever entered, and would have to state a descriptor that
 * nothing ever checks.
 */
object SystemServerService : Binder(), IBinder.DeathRecipient {

  private val serviceLock = Any()
  private var proxyServiceName: String? = null
  private var originService: IBinder? = null

  val systemServerRequested: Boolean
    get() = FrameworkService.isSystemServerRegistered()

  fun registerProxyService(serviceName: String): Boolean {
    // Register as the service name early to setup an IPC for `system_server`.
    Log.d(TAG, "Registering bridge service for `system_server` with name `$serviceName`.")

    // `IServiceManager.registerForNotifications` is only available since Android R.
    // On older platforms we simply let the real service replace our proxy in servicemanager.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      ServiceRegistrationWatcher.watch(serviceName)
    }

    // The Zygisk module polls this name during `system_server` specialization,
    // so it must be claimed on every supported platform.
    return runCatching {
          ServiceManager.addService(serviceName, this)
          synchronized(serviceLock) { proxyServiceName = serviceName }
          true
        }
        .onFailure { Log.e(TAG, "Failed to register proxy service `$serviceName`", it) }
        .getOrDefault(false)
  }

  /**
   * Clears dead origin-service state and reclaims the rendezvous name during soft restart.
   */
  fun prepareForSystemServerRestart(serviceName: String, instanceId: String, round: Long) {
    Log.i(
        TAG,
        "Preparing system_server rendezvous `$serviceName` for daemon `$instanceId` (round=$round)")
    clearOriginService()
    registerProxyService(serviceName)
  }

  /**
   * Arranges for our proxy to leave servicemanager once the module it was there for is done with
   * it; see [ServiceRegistrationWatcher.unregisterWhenUnused] for why it cannot simply be asked.
   *
   * Worth arranging because the name invented for a late injection is ours alone, so unlike
   * `serial` nothing will ever come along and replace it. Android R and later; older
   * servicemanagers offer no way to take a name back, and there it stays registered.
   */
  fun unregisterProxyService() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val name = synchronized(serviceLock) { proxyServiceName } ?: return
    ServiceRegistrationWatcher.unregisterWhenUnused(name, this)
  }

  /**
   * Adopts the real system service once servicemanager reports its registration, so that
   * [onTransact] starts forwarding non-Vector transactions to it.
   */
  internal fun adoptOriginService(name: String, binder: IBinder) {
    if (binder === this) return
    Log.d(TAG, "Intercepted system service registration with name `$name`")
    synchronized(serviceLock) {
      if (originService === binder) return
      originService?.let { prev -> runCatching { prev.unlinkToDeath(this, 0) } }
      originService = binder
      runCatching { binder.linkToDeath(this, 0) }
          .onFailure {
            Log.w(TAG, "Could not watch real `$name` service", it)
            originService = null
          }
    }
  }

  /**
   * Registers system_server and answers with its framework service, or null if this is not
   * system_server. Used by both the initial rendezvous and the existing bridge after a daemon
   * restart.
   */
  internal fun attachProcess(
      uid: Int,
      pid: Int,
      processName: String,
      processLifeToken: IBinder?,
  ): IFrameworkService? {
    if (
        uid != Process.SYSTEM_UID ||
            processLifeToken == null ||
            processName != "system" ||
            getCallingUid() != uid ||
            getCallingPid() != pid
    )
        return null

    // Status is derived from the live registration, including failure and process death.
    if (!FrameworkService.registerHeartBeat(uid, pid, processName, processLifeToken)) return null
    return FrameworkService
  }

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    when (code) {
      BRIDGE_TRANSACTION_CODE -> {
        val uid = data.readInt()
        val pid = data.readInt()
        val processName = data.readString() ?: ""
        val processLifeToken = data.readStrongBinder()

        val service = attachProcess(uid, pid, processName, processLifeToken)
        if (service != null) {
          reply?.writeNoException()
          reply?.writeStrongBinder(service.asBinder())
          return true
        }
        return false
      }
      DEX_TRANSACTION_CODE,
      OBFUSCATION_MAP_TRANSACTION_CODE -> {
        return FrameworkService.onTransact(code, data, reply, flags)
      }
      else -> {
        val (origin, name) = synchronized(serviceLock) { originService to proxyServiceName }
        origin?.let {
          // Handle Vector handshake/bootstrap codes above first; forward only real service calls.
          Log.d(TAG, "Forwarding request to real `$name` service.")
          return it.transact(code, data, reply, flags)
        }
        return super.onTransact(code, data, reply, flags)
      }
    }
  }

  private fun clearOriginService() {
    val prev =
        synchronized(serviceLock) {
          val current = originService
          originService = null
          current
        }
    prev?.let { runCatching { it.unlinkToDeath(this, 0) } }
  }

  override fun binderDied() {
    clearOriginService()
  }
}
