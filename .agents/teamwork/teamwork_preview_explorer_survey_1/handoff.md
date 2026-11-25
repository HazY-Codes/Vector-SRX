# Survey 1 Handoff Report — Vector-SRX R1 Codebase & Diff Audit

**Summary:** Vector-SRX (`HEAD` = `e7a31c41`) cleanly forward-ports Vector-SR's soft-reboot/reinjection/dex2oat/ART-invalidation stack and community PRs onto `upstream/master` (`6f6b976e`, Vector v2.2), with all translations (`problems: 0`) and diff hygiene checks passing. However, the audit uncovered **three high-priority defects** (BusyBox `mount -o rslave` incompatibility in `service.sh`, a `staticScope` vs `android`/`system` scope-normalization contradiction that rejects/prunes framework scopes, and the CMake 4.x `CMAKE_CXX_MODULE_STD` C++23 host `std.cc` scanning failure blocking CI run `#45375977969`) plus **five medium/low edge cases** (a `linkToDeath` race stranding `VectorDaemon.sendToBridge`, a dangling 6th `FindAndCall` JNI argument in `module.cpp`, unchecked `ResolveOEMVeneer` pointer reads, `post-fs-data.sh`/`service.sh` APatch gating asymmetry, and minor UI/update.json sync items).

---

## 1. Observation

### 1.1 Git Topology & Upstream Lineage
- `HEAD` (`e7a31c41`) sits 12 commits ahead of `upstream/master` (`6f6b976e`, `canary-3119`, Vector v2.2):
  - `bc63084f` Resolve OEM inline hook veneers to prevent infinite recursion (`JingMatrix/Vector#579`)
  - `8a68394c` Normalize the system framework's scope name to one spelling (`JingMatrix/Vector#1104`)
  - `56036b5a` Release consumed hot-reload resources and retired callbacks (`JingMatrix/Vector#1018`)
  - `acb072a1` Restore injected system-server connections after daemon restart (`JingMatrix/Vector#1017`)
  - `4adb37f8` Add a central app list for ART inline-hook compatibility (`JingMatrix/Vector#1013` / Vector-SR)
  - `1b50ef4b` Harden dex2oat wrapper mount recovery, recursion guard, and native logging bounds (Vector-SR + `JingMatrix/Vector#1008`)
  - `105024af` Port Vector-SR soft-restart reinjection ownership, lease, and handshake hardening (`vector-sr/master`)
  - `f3398778` Add KernelSU emulated soft-reboot cleanup and private mount-namespace shadow guard (`#1012` + `#1103`)
  - `901d3c07` Apply Samsung One UI 8.5/9 shared-UID legacy-hook storm hotfix (`peppicry/Vector-SR-S24U-Hotfix`)
  - `349a92b0` Brand and configure Vector-SRX repository, CI workflow, and GPL-3.0 notices
  - `07c69375` Sync `zygisk/update.json` metadata to `v2.2.1` (`3130`)
  - `e7a31c41` Configure CI concurrency and register Core workflow (`3131`)
- `git diff --stat upstream/master..HEAD -- ':!manager/src/main/res' ':!daemon/src/main/res'` touches 84 files (`+4712 / -800`).
- `git diff --check upstream/master..HEAD` exited `0` with no whitespace errors.
- `python3 manager/tools/check_translations.py manager/src/main/res` and `python3 manager/tools/check_translations.py daemon/src/main/res` both exited `0` with `problems: 0`.
- `git submodule status` shows 9 submodules currently uninitialized locally (`-7b6048b7...`, etc.); `.github/workflows/core.yml:33` checks out `submodules: recursive`.

### 1.2 Architecture & Module Boundaries (`settings.gradle.kts:21-35`)
| Module | Role & Boundaries |
| :--- | :--- |
| `:zygisk` | Magisk/KSU/APatch module assets (`zygisk/module/*`), `libzygisk.so` (`module.cpp`, `ipc_bridge.cpp`), Java zygote/system_server entrypoint (`Main.kt`, `BridgeService.kt`, `ParasiticManager*`, `GrapheneDclHooker.kt`), `vector.dex` packaging + `checkXResourcesIsolationRelease` (`zygisk/build.gradle.kts:402-474`). |
| `:daemon` | Root daemon (`vectord` / `VectorDaemon.kt`, `VectorService.kt`), `ConfigCache.kt`, `ModuleDatabase.kt`, `PreferenceStore.kt`, `InlineHookProcessPolicy.kt`, `FileSystem.kt`, `Dex2OatServer.kt`, `Cli.kt`/`CliHandler.kt`, JNI (`daemon/src/main/jni/{dex2oat,logcat,obfuscation}.cpp`), embeds Manager signing cert via `GenerateSignInfoTask` (`daemon/build.gradle.kts:68-108`). |
| `:dex2oat` | Native `dex2oat` wrapper executable (`dex2oat/src/main/cpp/dex2oat.cpp`) and `liboat_hook.so` (`oat_hook.cpp` using `lsplt_static`). |
| `native/` | Static C++23 library linked into `:zygisk`: LSPlant/Dobby hook bridge (`hook_bridge.cpp`, `resources_hook.cpp`, `native_api.cpp`), ELF/linker symbol resolution (`elf_image.cpp`), ART inline-hook snapshot/cleanup (`art_inline_hook_cleanup.cpp`), bounded logging (`logging.h`). |
| `:xposed` | Framework Java runtime (`VectorContext.kt`, `VectorModuleManager.kt`, `VectorServiceClient.kt`, `VectorProcessChannel.kt`, `VectorRemotePreferences.kt`, `DexMemoryScope.kt`, `OnDemandSerialExecutor.kt`, `VectorModuleClassLoader.kt`, `VectorHookHandle.kt`). |
| `:legacy` | `de.robv.android.xposed.*` compatibility layer + `LegacyDelegateImpl.java` (`XResources` isolation via `ResourceProxy`). |
| `:services:daemon-service`, `:services:manager-service` | AIDL contracts (`IVectorDaemon.aidl`, `IFrameworkService.aidl`, `IProcessChannel.aidl`, `IModuleService.aidl`, `IManagerService.aidl`) & shared utils. |
| `:manager`, `:manager-ui` | Jetpack Compose Material 3 Manager UI (`HomeViewModel.kt`, `SystemStatusScreen.kt`, `InlineHookInvalidationPicker.kt`, `ScopeViewModel.kt`, `GitHubRepository.kt`), debug `FakeManagerService.kt`, shared UI/module detection (`ModuleDetection.kt`). |
| `:tests` | Host regression harnesses: `tests/reattach/run_tests.py` and `tests/hotload/run_tests.py`. |

### 1.3 Direct Code & Runtime Observations per Target Area

#### Area 1 — Soft-Reboot & Mount Scripts (`zygisk/module/*`)
1. `zygisk/module/emulated-soft-reboot.sh:6-132`:
   - Line 6: `[ "$KSU" = true ] || [ "$APATCH" = true ] || [ "$1" = "--force" ] || exit 0`.
   - `daemon_identity()` (lines 16-30): verifies `cwd` matches `$MODDIR` or `$MODDIR (deleted)`, `exe` ends with `*/app_process{,32,64}`, `cmdline` (`tr '\000' ' '`) matches `vectord*`, `*/vectord*`, `lspd*`, `*/lspd*`, or `*org.matrix.vector.daemon.VectorDaemon*`.
   - `process_start()` (lines 32-40) + `stop_daemons()` (lines 53-71): captures start time field 22 from `/proc/$pid/stat` to guard against PID reuse, sends `kill -9`, polls until dead or zombie (`Z *|X *`).
   - `visible_mount()` + `cleanup_target()` (lines 73-106): opens target on FD 3, parses `mnt_id:` from `/proc/self/fdinfo/3`, checks `/proc/self/mountinfo` field `$4` after stripping `\040(deleted)` and `//deleted` against `$MODDIR/bin/dex2oat{32,64}` and `${MODDIR#/data}/bin/dex2oat{32,64}`, unmounts up to 32 layers (`umount "$target" 2>/dev/null || umount -l "$target"`), gated on `init_ns = self_ns` (lines 110-112).
   - Lines 125-129: detaches leaked `/data/resource-cache` mounts matching `$MODDIR/.resource-cache`, `${MODDIR#/data}/.resource-cache`, or ` - tmpfs ` in `init_ns`.
   - Verified empirically on host Android (`/system/bin/sh` = `mksh`, `/system/bin/awk` = Android `one-true-awk`, `/system/bin/tr` = Toybox): `root=${root%\\040(deleted)}`, `tr '\000' ' '`, and `awk` `split(..., /[[:space:]]+/)` all succeed with exit 0.
2. `zygisk/module/post-fs-data.sh:6-8` & `zygisk/module/service.sh:8-10`:
   - Both check `[ "$KSU" = true ]` (excluding `$APATCH`) before invoking `emulated-soft-reboot.sh`.
3. `zygisk/module/service.sh:16-28` & `zygisk/module/daemon:35-40`:
   - `service.sh:16-19`:
     ```sh
     if unshare -m true 2>/dev/null; then
       unshare -m /system/bin/sh -c '
           if mount -o rslave none / 2>/dev/null; then
             VECTOR_SHADOW_OK=1 exec "$0" "$@"
           else
             echo "vector: mount namespace stays shared; daemon starts without resource-cache shadow" >&2
             VECTOR_NO_SHADOW=1 exec "$0" "$@"
           fi
       ' "$MODDIR/daemon" "$@" &
     ```
   - `strings /system/bin/toybox` on host returned only `rslave` (not `make-rslave`); `/system/bin/mount --help` confirms Toybox 0.8.13 parses `-o OPTION`. BusyBox `mount` (used in KernelSU `/data/adb/ksu/bin` and APatch `/data/adb/ap/bin` ahead of `/system/bin` in `$PATH`) uses `--make-rslave /` and does not accept `mount -o rslave none /`.
   - `zygisk/module/daemon:35-40`: only bind-mounts `$dir/.resource-cache` over `/data/resource-cache` when `[ "$VECTOR_SHADOW_OK" = "1" ]`.
4. `zygisk/module/module.prop:1-8` & `zygisk/module/customize.sh:57,85,121-125,137`:
   - `id=zygisk_vector`, `name=Vector-SRX`, `updateJson=https://raw.githubusercontent.com/HazY-Codes/Vector-SRX/master/zygisk/update.json`.
   - `customize.sh:85,137` extracts and sets `0744` on `emulated-soft-reboot.sh`; all scripts in `zygisk/module/` use LF line endings (`git ls-files --eol`).

#### Area 2 — Daemon, Reinjection, `dex2oat`, Scope & `InlineHookProcessPolicy`
1. **Reinjection ownership & restart lease (`FileSystem.kt:96-99,219-302,857-986`; `VectorDaemon.kt:77-84,204-314`)**:
   - `FileSystem.tryLock()` (`FileSystem.kt:219-244`) holds `lockPath` via a `Runtime.getRuntime().addShutdownHook(...)` so R8 cannot shrink the `FileChannel`/`FileLock` references.
   - `FileSystem.ensureActiveReinjectionOwner` (`FileSystem.kt:248-252,877-897`) persists `instanceId|pid|claimedAtMillis` under `vector_reinjection_owner.lock`; `isExpectedDaemonProcess` (`FileSystem.kt:972-986`) recognizes `vectord`, `lspd`, and `org.matrix.vector.daemon.VectorDaemon`.
   - `VectorDaemon.kt:207` uses `bridgeBinder.compareAndSet(bridgeService, null)` to deduplicate death callbacks, acquires `FileSystem.tryAcquireReinjectionLease(daemonInstanceId)` (`VectorDaemon.kt:210`), clears `ServiceManager` & `ActivityManager` caches (`clearSystemCaches()`, lines 317-345), calls `Dex2OatServer.refreshMount()`, resets `startedAfterSystemServer = false`, calls `SystemServerService.prepareForSystemServerRestart(...)`, invalidates `ManagerService` guard (`invalidateForSystemServerRestart()`, `ManagerService.kt:148-158`), and posts `sendToBridge(binder, true, lease)` to the main looper.
   - **Defect observed at `VectorDaemon.kt:271-276,310`**: Inside `sendToBridge`, if `bridgeService` dies between `pingBinder()` (line 198) and `bridgeService.linkToDeath(deathRecipient, 0)` (line 272), `linkToDeath` throws `RemoteException`, `bridgeBinder` is cleared back to `null`, `runCatching { ... }.onFailure` logs the error (line 310), `finally` closes `reinjectionLease` (line 313), and `sendToBridge` returns without registering any death recipient or scheduling a retry.
2. **Live `system_server` re-attachment (`IVectorDaemon.aidl:56-57`, `BridgeService.kt:63-103`, `VectorServiceClient.kt:79-90`, `VectorModuleManager.kt:64-85`, `FrameworkService.kt:263-325`, `VectorContext.kt:72-87`, `VectorRemotePreferences.kt:92-129`)**:
   - `BridgeService.receiveFromBridge` (`BridgeService.kt:63-103`) re-attaches `system_server` (`uid=1000`, `process="system"`, `systemServerLifeToken`), calls `VectorServiceClient.reconnectSystemServer(framework, next.service)`, which calls `VectorModuleManager.reconnectSystemServerServices(daemon)` -> `daemon.reconnectSystemModule(pkg, version, hotReloadable)` -> `VectorContext.reconnectService(replacement)` -> `VectorRemotePreferences.reconnect(replacement)`, and re-attaches `VectorProcessChannel`. Verified by existing `tests/reattach/ReattachTest.java`.
   - `SystemServerService.kt:214-217`: `DEX_TRANSACTION_CODE` and `OBFUSCATION_MAP_TRANSACTION_CODE` are handled before `originService` forwarding; `ServiceRegistrationWatcher` (`SystemServerService.kt:30-88`) is kept separate by `daemon/proguard-rules.pro:55` for pre-Android R safety.
3. **Dual-namespace `dex2oat` recovery & recursion guard (`Dex2OatServer.kt:58-539`, `daemon/src/main/jni/dex2oat.cpp:27-149`, `dex2oat/src/main/cpp/dex2oat.cpp:29,116-125`)**:
   - `daemon/src/main/jni/dex2oat.cpp:102-127`: `doMountNative` runs `apply_mounts` in the daemon's namespace and forks a child calling `setns(/proc/1/ns/mnt)` to run `apply_mounts` in `init`'s namespace, unmounting up to `kMaxUnmountLayers = 32` layers (`MNT_DETACH`).
   - `Dex2OatServer.kt:280-326`: `requiredDex2OatIndices()` treats `dex2oatd` debug binaries (`1`, `3`) as optional unless present; `validateOriginalDex2OatFdLocked()` verifies open FDs are not pointing to `WRAPPER32`/`WRAPPER64` (`sameFile` via `dev`/`ino`); `notMounted()` stats both `bin` and `/proc/1/root$bin` against `WRAPPER32`/`WRAPPER64`; `enableDex2OatPropertyFallback()` runs `resetprop dalvik.vm.dex2oat-flags --inline-max-code-units=0` only when mounting/recovery fails.
   - `dex2oat/src/main/cpp/dex2oat.cpp:116-125`: `VECTOR_DEX2OAT_WRAPPER_PID` compares `std::to_string(getpid())` across `execve` to abort recursive wrapper execution while allowing child `dex2oat` processes.
4. **System scope normalization (`ModuleDatabase.kt:47-48,73,201-213,306-312,344-347,369-422`, `ConfigCache.kt:326-362`, `VectorService.kt:533-572`, `CliHandler.kt:109-144`, `ModuleAppService.kt:527-534`, `ScopeViewModel.kt:287,308,643,701`)**:
   - **Defect observed**: `ModuleDatabase.setModuleScope` (`ModuleDatabase.kt:344-347`) and `VectorService.dispatchModuleScope` (`VectorService.kt:561-564`) normalize `"android"` -> `"system"` on write, and `ModuleDatabase.getModuleScope` (`ModuleDatabase.kt:73`) normalizes `"android"` -> `"system"` on read. However:
     1. `FileSystem.readStaticScope` (`FileSystem.kt:382-400`) does **not** normalize `"android"` -> `"system"` when reading `META-INF/xposed/scope.list`.
     2. `ModuleDatabase.setModuleScope` (`ModuleDatabase.kt:306-312`) checks `scope.map { it.packageName }.distinct().filterNot { claimed.contains(it) }` against `ConfigCache.staticScopeOf(packageName)` **before** normalization, so any `"system"` entry (from Manager, `VectorService`, or `getModuleScope` round-tripped via `CliHandler.handleScope("add")`) is rejected when `scope.list` declares `"android"`.
     3. `ModuleDatabase.pruneScopeToClaimed` (`ModuleDatabase.kt:380-384`, called at `ConfigCache.kt:327`) executes `DELETE FROM scope WHERE mid = ? AND app_pkg_name NOT IN ($placeholders)` using unnormalized `claimed` (`"android"`), deleting normalized `"system"` rows from SQLite!
     4. `ScopeViewModel.kt:287,308,701` compares `systemFrameworkEntry` (`packageName = "system"`, line 643) against `recommended.packages` (`ModuleDetection.kt:106-113`), which is unnormalized for modern modules, hiding `system_server` when `staticScope=true` and `scope.list` uses `"android"`.
     5. `CliHandler.kt:109-117` checks unnormalized `apps` against unnormalized `claimed`.
5. **Per-app ART inline-hook invalidation (`InlineHookProcessPolicy.kt`, `PreferenceStore.kt:9-12,108-158`, `FrameworkService.kt:36-37,250-258`, `ipc_bridge.cpp:92-93,491-513`, `module.cpp:365-404`)**:
   - `InlineHookProcessPolicy.mayInvalidate` excludes `(uid == 1000 && processName == "system")`.
   - `PreferenceStore.shouldInvalidateArtInlineHooks` matches `SYSTEM_UI_VIRTUAL_PACKAGE` (`"system"`) to `uid == 1000 && processName == "system:ui"`, or resolves `packageName` in `uid / 100000` via `getPackageInfoWithComponents` and matches `expectedUid == uid` and `(processName == applicationProcessName || processName in componentProcesses)`.
   - Manager never sets invalidation on itself (`module.cpp:366`: `!is_manager_app_`), and `system_server` explicitly disables it (`module.cpp:493`: `ConfigureArtInlineHookInvalidation(false)`).

#### Area 3 — Native (`native/`, `dex2oat/`, `zygisk/`)
1. **`.dynsym` export hiding (`zygisk/src/main/cpp/CMakeLists.txt:15-25`)**: Version script restricts dynamic exports to `zygisk_module_entry` and `zygisk_companion_entry` (`local: *;`).
2. **Truncated log null-termination (`native/include/common/logging.h:71-76`)**: Uses `*result.out = '\0';` after `fmt::format_to_n(buf.data(), buf.size() - 1, ...)` instead of indexing `buf[result.size]`.
3. **ARM64 OEM veneer unwrapping (`native/include/core/native_api.h:114-135,143-175`)**:
   - Decodes `ldr x17, pc+8; br x17` (`0x58000051`, `0xd61f0220`) and `ldr x16, pc+8; br x16` (`0x58000050`, `0xd61f0200`) on `__aarch64__` and resolves `*reinterpret_cast<void **>(insn + 2)`.
   - **Edge case**: No null/readability guard on `real_target` or `target` (e.g. if `target` were `--xp` XOM or `real_target == nullptr`, or chained veneers). Also `module.cpp:139` calls `RecordArtInlineHookInvalidationTarget(target)` with the pre-veneer `target` while `HookInline` patches `ResolveOEMVeneer(target)`.
4. **ART inline-hook cleanup (`native/src/core/art_inline_hook_cleanup.cpp:80-502`)**:
   - Snapshots pre-Vector modified `libart.so` executable pages (`CaptureExecutablePages`), then in `InvalidateArtInlineHooksIfEnabled()` restores clean file-backed `PT_LOAD` (`PF_X`) segments in a single `MAP_PRIVATE | MAP_FIXED` `mmap` (preserving contiguous VMA layout), preflighting and reapplying preserved pre-Vector pages (`PreparePreservedPagesForRewrite` / `RestorePreservedPages`) + `__builtin___clear_cache`, leaving LSPlant/Dobby metadata untouched.
5. **ABI / JNI signature mismatch (`zygisk/src/main/cpp/module.cpp:394-396,500-503` vs `Main.kt:30-36`)**:
   - `module.cpp:394-396` and `500-503` pass 6 arguments (`..., binder.get(), is_manager_app_`) to `FindAndCall("forkCommon", "(ZZLjava/lang/String;Ljava/lang/String;Landroid/os/IBinder;)V", ...)` whose JNI signature and `Main.forkCommon` Kotlin definition take 5 arguments.

#### Area 4 — Legacy & Samsung One UI (`legacy/`)
- `legacy/src/main/java/org/matrix/vector/legacy/LegacyDelegateImpl.java:71-81`: When `executable.getName()` is `"getApplicationInfoAsUser"` and `args[0]` equals `"android.uid.system"`, bypasses `VectorLegacyCallback` / `LegacyApiSupport` (`before`/`after` hooks) and invokes `invokeOriginal.invoke()` directly, rethrowing via `sneakyThrow(t)`.
- `legacy/src/main/java/de/robv/android/xposed/XposedBridge.java:143-147`: Suppresses `android.content.pm.PackageManager$NameNotFoundException` with message `"android.uid.system"` in `XposedBridge.log(Throwable)`.

#### Area 5 — R8 / ProGuard, CI Build (`#45375977969`), & Compose Manager UI/IPC
1. **CI Build Failure Root Cause (`gh run view 45375977969 --log-failed`)**:
   - `.github/workflows/core.yml:100-102` deletes `$ANDROID_HOME/cmake`, causing Gradle (`build.gradle.kts:268-271`, `version = "3.29.8+"`) to select Ubuntu runner's `/usr/local/bin/cmake` (CMake 4.x).
   - With `NDK 30.0.16248370` (`clang 21`) and `CMAKE_CXX_STANDARD 23` (`daemon/src/main/jni/CMakeLists.txt:4`, `native/CMakeLists.txt:5`, `zygisk/src/main/cpp/CMakeLists.txt:5`, `external/CMakeLists.txt:5`), CMake 4.x auto-enables `CMAKE_CXX_MODULE_STD` (`@cmake_cxx_std@synth_0` on `external/fmt`), causing `clang-scan-deps` to scan host `/usr/include/c++/15/bits/std.cc` with the Android NDK sysroot and fail with `fatal error: 'bits/stdc++.h' file not found`.
   - Note: `external/lsplant`'s `dex_builder` uses C++20 named modules (`.ixx`, per `build.gradle.kts:290`), so `CMAKE_CXX_SCAN_FOR_MODULES` must stay enabled while `-DCMAKE_CXX_MODULE_STD=OFF` disables the broken `std` module scan.
2. **ProGuard / R8 Rules**:
   - `zygisk/proguard-rules.pro:1-9`: Keeps `Main.forkCommon` (5-arg signature) and `BridgeService.execTransact`.
   - `daemon/proguard-rules.pro:1-55`: Keeps `native <methods>` (covering `Dex2OatServer`), `VectorDaemon.main`, `Cli.main`, Picocli annotations, `CliRequest`/`CliResponse`, `LogcatMonitor.refreshFd`, and `ServiceRegistrationWatcher` (`-keep,allowobfuscation`).
   - `FileSystem.tryLock` (`FileSystem.kt:235`), `VectorDaemon.bridgeBinder`, `BridgeService.connection`, `VectorServiceClient.connection`, and `ManagerService.guard` all retain strong live reads / shutdown-hook references so R8 cannot shrink them.
   - `zygisk/build.gradle.kts:402-474`: `checkXResourcesIsolationRelease` verifies `XResources`/`XResources$XTypedArray` do not leak into R8-merged lambdas.
3. **Compose Manager UI / IPC**:
   - `IManagerService.aidl:76` (`PROTOCOL_VERSION = 4`), `DaemonClient.kt:228-240`, `FakeManagerService.kt:227-231`, `HomeViewModel.kt:625-759`, `SystemStatusScreen.kt:143-152,230-239,284-291`, and `InlineHookInvalidationPicker.kt` are aligned end-to-end.
   - `HomeViewModel.kt:661-672`: Synthetic `"system"` row displays `android` ("Android System") label/icon while targeting only `system:ui` (`PreferenceStore.kt:10-14`).
   - `zygisk/update.json:3-4`: Currently pins `versionCode: 3131` and `Vector-SRX-v2.2.1-3131-Release.zip`; must be synchronized to the final commit count before tagging `v2.2.1`.
   - `README.md:55`: Minor typo `notification,root`.

---

## 2. Logic Chain

1. **BusyBox `mount -o rslave none /` (`service.sh:18`)**: Because KernelSU and APatch inject BusyBox `mount` first in `$PATH`, `mount -o rslave none /` fails under BusyBox, pushing `service.sh` into the `VECTOR_NO_SHADOW=1` fallback (`service.sh:21-22`). That avoids a boot loop (good safety gate), but disables `/data/resource-cache` shadowing on KernelSU/APatch devices and re-exposes Android <= 10 `overlays.list` / early zygote `idmap2` races (`82112f99`). Adding a fallback to `mount --make-rslave /` (or explicitly trying `/system/bin/mount -o rslave none /` then `mount --make-rslave /`) restores private mount-namespace shadowing across both Toybox and BusyBox.
2. **`staticScope` vs `android`/`system` normalization**: Because `libxposed` API 102 specifies `"android"` for the system framework in `scope.list` while `ModuleDatabase` stores `"system"` (`ModuleDatabase.kt:47-48,344-347`), failing to normalize `FileSystem.readStaticScope` (`FileSystem.kt:382-400`) and `ModuleDetection.inspect` (`ModuleDetection.kt:106-113`) causes `setModuleScope` (`ModuleDatabase.kt:307`), `pruneScopeToClaimed` (`ModuleDatabase.kt:380-384`), `CliHandler.rejectBeyondStaticScope` (`CliHandler.kt:111`), and `ScopeViewModel` (`ScopeViewModel.kt:287,308,701`) to treat `"system"` as outside the static scope—actively rejecting user edits and deleting saved `"system"` rows on cache rebuild.
3. **CMake 4.x `std.cc` CI Failure**: Because `core.yml:102` removes `$ANDROID_HOME/cmake`, Gradle selects `/usr/local/bin/cmake` (4.x), which probes host GCC 15 `/usr/include/c++/15/bits/std.cc` for `CMAKE_CXX_STANDARD 23`. Setting `-DCMAKE_CXX_MODULE_STD=OFF` in `build.gradle.kts:303-310` suppresses host `std` module scanning while keeping `CMAKE_CXX_SCAN_FOR_MODULES` enabled for `dex_builder.ixx`.
4. **`sendToBridge` `linkToDeath` race & `module.cpp` extra arg**: If `system_server` restarts right after `pingBinder()` (`VectorDaemon.kt:198`) and before `linkToDeath` (`VectorDaemon.kt:272`), swallowing `RemoteException` exits `sendToBridge` without a death monitor; looping/retrying on `RemoteException` (or retrying `bridgeService` acquisition) closes the race. Removing `is_manager_app_` from the two `FindAndCall` calls in `module.cpp:396,503` brings JNI call arity into exact alignment with `Main.forkCommon`.

---

## 3. Caveats

1. **No local Gradle/NDK/APK build executed**: Android SDK/NDK r30 and initialized git submodules (`external/*`, `services/libxposed`, `xposed/libxposed`) are not set up in this read-only survey pass, so `tests/reattach/run_tests.py`, `tests/hotload/run_tests.py`, and `./gradlew zipAll` were not executed locally (scheduled for R2/R3 milestones).
2. **Untested live BusyBox binary**: Host userspace contains Toybox 0.8.13 (`/system/bin/mount`), not a BusyBox binary (`which busybox` returned nothing), so BusyBox `mount --make-rslave` vs `-o rslave` behavior is derived from BusyBox `util-linux/mount.c` semantics rather than a live BusyBox binary on this device.
3. **R4 boundary respected**: No repository files were modified, no git pushes occurred, and no module/soft-reboot commands were run on the host device.

---

## 4. Conclusion & Recommended Milestone Boundaries

### Priority Remediation List (before R2/R3)
1. **P0 (R3 CI Blocker)**: Add `-DCMAKE_CXX_MODULE_STD=OFF` to `cmake.arguments` in `build.gradle.kts:303-310` (and optionally verify CMake behavior in `.github/workflows/core.yml`).
2. **P0 (R1 Correctness)**: Normalize `"android"` -> `"system"` when loading `staticScope` in `FileSystem.readStaticScope` (`FileSystem.kt:382-400`), `CliHandler.rejectBeyondStaticScope` (`CliHandler.kt:111`), and modern `ModuleDetection.inspect` (`ModuleDetection.kt:106-113`), and handle `android`/`system` equivalence cleanly in `ModuleDatabase.setModuleScope` & `pruneScopeToClaimed`.
3. **P1 (R1 Portability)**: Update `zygisk/module/service.sh:18` to support both Toybox and BusyBox (`mount -o rslave none / 2>/dev/null || mount --make-rslave / 2>/dev/null` and/or `/system/bin/mount -o rslave none /`).
4. **P1 (R1 Reliability)**: In `VectorDaemon.kt:270-310`, handle `RemoteException` from `linkToDeath` by retrying `bridgeService` acquisition instead of exiting `sendToBridge` with `bridgeBinder == null`.
5. **P2 (R1 Hygiene/Hardening)**:
   - Remove trailing `is_manager_app_` arg from `FindAndCall("forkCommon", ...)` in `zygisk/src/main/cpp/module.cpp:396,503`.
   - Guard `ResolveOEMVeneer` (`native/include/core/native_api.h:115-135`) against null/chained targets and consider readability safety.
   - Review whether `post-fs-data.sh:6` / `service.sh:8` should also include `[ "$APATCH" = true ]` to match `emulated-soft-reboot.sh:6`.
   - Fix `README.md:55` spacing (`notification, root`), and re-sync `zygisk/update.json` to the final commit count before tagging `v2.2.1`.

### Recommended Milestone Boundaries & Dependencies
- **Milestone 1 (R1 Hardening)**: Apply P0/P1/P2 fixes (`service.sh`, `FileSystem.kt`/`ModuleDatabase.kt`/`CliHandler.kt`/`ModuleDetection.kt` staticScope normalization, `VectorDaemon.kt` `linkToDeath` retry, `module.cpp` JNI arity, `native_api.h` veneer guard, `README.md` typo) + P0 CI CMake fix (`build.gradle.kts`).
- **Milestone 2 (R2 Automated Host Verification)**: Run `check_translations.py`, `tests/reattach/run_tests.py`, `tests/hotload/run_tests.py`, and add host regression tests covering `emulated-soft-reboot.sh` / `service.sh` shadow gating (Toybox + mocked BusyBox), `InlineHookProcessPolicy`, native `logging.h` truncation null-termination, `staticScope` `android`/`system` normalization, and `LegacyDelegateImpl` `android.uid.system` bypass.
- **Milestone 3 (R3 CI Build, Artifact Audit & `v2.2.1` Release)**: Push to `HazY-Codes/Vector-SRX`, verify `Core` workflow passes across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), verify `.zip` contents, `.sha256`, `.dynsym` exports, and `CN=Vector-SRX` signing match, synchronize `zygisk/update.json` & `zygisk/changelog.md` to exact `versionCode`, and tag/verify `v2.2.1` `--latest` release.

---

## 5. Verification Method
To independently verify these observations:
1. **Static & Translation Checks** (run in repo root):
   - `python3 manager/tools/check_translations.py manager/src/main/res` (expect `problems: 0`, exit `0`)
   - `python3 manager/tools/check_translations.py daemon/src/main/res` (expect `problems: 0`, exit `0`)
   - `git diff --check upstream/master..HEAD` (expect exit `0`, empty output)
2. **Toybox / `mksh` / `awk` Verification**:
   - Inspect `/system/bin/mount --help` and `strings /system/bin/toybox | grep -E 'rslave|make-rslave'` (expect only `rslave`, confirming BusyBox fallback `--make-rslave` is needed in `zygisk/module/service.sh:18`).
3. **Code Inspection**:
   - Inspect `daemon/src/main/kotlin/org/matrix/vector/daemon/data/FileSystem.kt:382-400`, `ModuleDatabase.kt:306-312,344-347,380-384`, `ConfigCache.kt:326-331`, and `manager-ui/src/main/kotlin/org/matrix/vector/ui/module/ModuleDetection.kt:106-113` to confirm `"android"` in `scope.list` is unnormalized against `"system"` writes.
   - Inspect `gh run view 45375977969 --repo HazY-Codes/Vector-SRX --log-failed` to confirm CMake 4.x `@cmake_cxx_std@synth_0` failure on `/usr/include/c++/15/bits/std.cc`.
   - Inspect `zygisk/src/main/cpp/module.cpp:394-396,500-503` vs `zygisk/src/main/kotlin/org/matrix/vector/core/Main.kt:30-36` for the 6-vs-5 argument mismatch.
   - Inspect `daemon/src/main/kotlin/org/matrix/vector/daemon/VectorDaemon.kt:196-314` for the `linkToDeath` `RemoteException` exit path.
4. **Invalidation Conditions**:
   - Any commit modifying `zygisk/module/service.sh`, `build.gradle.kts`, `FileSystem.kt`, `ModuleDatabase.kt`, `ModuleDetection.kt`, `VectorDaemon.kt`, or `zygisk/src/main/cpp/module.cpp` invalidates the corresponding defect status and must be re-verified via R2 host tests and R3 CI build.
