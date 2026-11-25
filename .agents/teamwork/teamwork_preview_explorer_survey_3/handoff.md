# Handoff Report — Survey 3: R2 Host Regression & Static Verification Audit

**Core Summary:** `check_translations.py` (manager & daemon) and `git diff --check` pass (`exit 0`, `problems: 0`), while `tests/reattach/run_tests.py` and `tests/hotload/run_tests.py` require CLI paths (`--jdk`, `--sdk`, `--gradle-cache`), prebuilt Kotlin/Java classes, initialized submodules (`external/lsplant`, `external/fmt`), and a C++ compiler (`c++`/`g++` not currently installed on host; `clang` 21.1.8-3 is installable via `pkg`). All four new/expanded R2 regression targets have clear, deterministic seams for host-executable tests without requiring a device reboot.

---

## 1. Observation

### 1.1 Inventory of Existing Tests & Verification Scripts

| Scope | Path | Type / Purpose |
|---|---|---|
| `tests/reattach/` | `tests/reattach/run_tests.py` | Host harness compiling `ReattachTest.java` against built `:daemon`, `:zygisk`, `:xposed`, `:services:*`, `:hiddenapi:stubs` classes + extracting `IPCBridge::RequestSystemServerBinder` & `RequestManagerBinderFromSystemServer` from `zygisk/src/main/cpp/ipc_bridge.cpp` into `handshake_fixture.cpp` (`c++ -std=c++17`). |
| `tests/reattach/` | `tests/reattach/ReattachTest.java` | Java lifecycle tests across API branches `26, 28, 30, 31, 33, 34, 35, 36, 37`. |
| `tests/reattach/` | `tests/reattach/handshake_fixture.cpp` | C++17 native fixture with JNI/transport/sleep doubles. |
| `tests/reattach/` | `tests/reattach/stubs/**/*.java` | 15 Android/daemon stub classes (`ActivityThread`, `ContextImpl`, `IApplicationThread`, `Context`, `ApplicationInfo`, `Binder`, `Build`, `Bundle`, `Parcel`, `Process`, `RemoteException`, `ArraySet`, `Log`, `HiddenApiBridge`, `ConfigCache`, `ModuleDatabase`). |
| `tests/reattach/` | `tests/reattach/README.md` | Usage and scope documentation. |
| `tests/hotload/` | `tests/hotload/run_tests.py` | Host harness compiling `ResourceCleanupTest.java` & `HookCleanupTest.java` against built `:xposed` & `:services:daemon-service` classes + extracting `JNIMonitor` (from `external/lsplant/.../jni_helper.hpp`) and `HookItem`/`unhookMethod`/`replaceCallback`/`callbackSnapshot` (from `native/src/jni/hook_bridge.cpp`) into `cleanup_fixture.cpp` (`g++ -std=c++20`). |
| `tests/hotload/` | `tests/hotload/HookCleanupTest.java` | 9 JNI/JVM GC & callback lifetime test cases (`HookCleanupTest.java:223-237`). |
| `tests/hotload/` | `tests/hotload/ResourceCleanupTest.java` | `OnDemandSerialExecutor` + `DexMemoryScopeKt` resource cleanup test cases. |
| `tests/hotload/` | `tests/hotload/cleanup_fixture.cpp` | C++20 JNI fixture using `parallel_hashmap/phmap.h`. |
| `tests/hotload/` | `tests/hotload/stubs/android/os/SharedMemory.java` | `SharedMemory` host double. |
| `tests/hotload/` | `tests/hotload/README.md` | Usage and scope documentation. |
| `manager/tools/` | `manager/tools/check_translations.py` | XML string/plurals parity, `%N$s`/`%s`/`%d` placeholder parity, and stray CJK script detector (also run in `.github/workflows/core.yml:36-39`). |
| `zygisk/` | `zygisk/build.gradle.kts:402-474` | Gradle verification task `checkXResourcesIsolationRelease` scanning minified DEX via `smali-dexlib2` to forbid stray `XResources` references outside `resourceOwners`. |
| `zygisk/module/` | `emulated-soft-reboot.sh`, `post-fs-data.sh`, `service.sh`, `daemon`, `customize.sh`, `action.sh`, `uninstall.sh` | Runtime module shell scripts (no automated tests yet). |
| `daemon/`, `native/`, `legacy/`, `services/`, `xposed/`, `manager-ui/`, `dex2oat/` | — | **0** unit test files or test directories (`*test*` / `*Test*` search returned only `tests/`). |

### 1.2 Baseline Host Check Results (Read-Only Execution)

Executed in `/data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX`:

| Command | Exit Code | Verbatim Output / Result |
|---|---|---|
| `python3 manager/tools/check_translations.py manager/src/main/res` | `0` | `problems: 0` |
| `python3 manager/tools/check_translations.py daemon/src/main/res` | `0` | `problems: 0` |
| `python3 tests/reattach/run_tests.py` | `2` | `usage: run_tests.py [-h] --jdk JDK --sdk SDK --gradle-cache GRADLE_CACHE [--output OUTPUT]`<br>`run_tests.py: error: the following arguments are required: --jdk, --sdk, --gradle-cache` |
| `python3 tests/hotload/run_tests.py` | `2` | `usage: run_tests.py [-h] --jdk JDK --sdk SDK [--gradle-cache GRADLE_CACHE] [--output OUTPUT] [--baseline BASELINE]`<br>`run_tests.py: error: the following arguments are required: --jdk, --sdk` |
| `GIT_CONFIG_NOSYSTEM=1 git -c safe.directory=... diff --check` | `0` | *(empty — zero whitespace errors)* |
| `GIT_CONFIG_NOSYSTEM=1 git -c safe.directory=... status --short` | `0` | `?? .agents/` |

**Additional blockers preventing `reattach/run_tests.py` and `hotload/run_tests.py` from running even with flags today:**
1. **No compiled module classes:** `daemon/build`, `xposed/build`, `zygisk/build`, `hiddenapi/stubs/build` do not exist (`NO_BUILD_DIRS`), and all 9 git submodules (`external/lsplant`, `external/fmt`, `external/dobby`, `external/lsplt`, `external/xz-embedded`, `external/apache/commons-lang`, `external/axml/manifest-editor`, `services/libxposed`, `xposed/libxposed`) are uninitialized (`git submodule status` prefixes `-`).
2. **SDK version mismatch in `tests/hotload/run_tests.py:50`:** Hardcodes `args.sdk / 'platforms/android-37.0/android.jar'`, whereas host `$HOME/android-sdk/platforms/` contains `android-37.2/android.jar` and `android-37.2-2/android.jar` (unlike `tests/reattach/run_tests.py:31` which uses `sorted((args.sdk / 'platforms').glob('android-*/android.jar'))[-1]`).
3. **Kotlin stdlib version in cache:** `tests/hotload/run_tests.py:45-46` looks up `kotlin = "2.4.21"` (from `gradle/libs.versions.toml:3`), while `$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/` currently only has `2.4.0/` and `2.4.10/`.
4. **C++ compiler missing on host:** `reattach/run_tests.py:49` invokes `c++` and `hotload/run_tests.py:86` invokes `g++`; neither is currently installed in `$PREFIX/bin`.

### 1.3 Target Code Inspection for New/Expanded R2 Tests

#### Target A: Soft-Reboot Cleanup & Private Shadow Namespace Gating
- **`zygisk/module/emulated-soft-reboot.sh` (133 lines):**
  - Line 6: `[ "$KSU" = true ] || [ "$APATCH" = true ] || [ "$1" = "--force" ] || exit 0`
  - Line 7: `MODDIR=$(CDPATH= cd -- "${0%/*}" && pwd -P) || exit 1`
    - *Edge case:* If invoked via bare basename without `/` (e.g. `sh emulated-soft-reboot.sh` from inside `MODDIR`), `${0%/*}` expands to `emulated-soft-reboot.sh`, causing `cd` to fail and exit `1`.
  - Lines 16–30 (`daemon_identity`): validates numeric PID > 1, `pid != $$`, `/proc/$pid/cwd` matches `$MODDIR` or `$MODDIR (deleted)`, `/proc/$pid/exe` matches `*/app_process{,32,64}`, `/proc/$pid/cmdline` matches `vectord*`, `*/vectord*`, `lspd*`, `*/lspd*`, or `*org.matrix.vector.daemon.VectorDaemon*`.
  - Lines 32–40 (`process_start`): reads `/proc/$1/stat`, strips through last `) ` (`record=${record##*) }`) so comm names containing spaces or `)` do not shift fields, `set -f; set -- $record; [ "$#" -ge 20 ] || return 1; shift 19; printf '%s\n' "$1"` (extracts field 22 start time).
  - Lines 42–51 (`daemon_candidates`): `ls -ld /proc/[0-9]*/cwd 2>/dev/null | awk -v dir="$MODDIR" ...` splitting on `" -> "` and `/[[:space:]]+/`.
    - *Verified on host:* `/system/bin/awk` (BWK/one-true-awk) exited `0` on `split(..., /[[:space:]]+/)` and printed `1234`.
  - Lines 53–71 (`stop_daemons`): TOCTOU start-time re-check before `kill -9 "$pid"`, polls `/proc/$pid/stat` up to 20×0.05s, treating `Z *` / `X *` or vanished `/proc/$pid/stat` as exited.
  - Lines 73–106 (`visible_mount` + `cleanup_target`): reads `mnt_id:` from `/proc/self/fdinfo/3` after `exec 3< "$1"`; looks up `$1 == id && $5 == target` in `/proc/self/mountinfo`; strips `\040(deleted)` and `//deleted`; only unmounts if root matches `$MODDIR/bin/dex2oat{32,64}` or `${MODDIR#/data}/bin/dex2oat{32,64}` (bounded to 32 layers, falls back to `umount -l`, verifies `next_id != mount_id`).
  - Lines 110–130: only cleans global mounts when `/proc/1/ns/mnt == /proc/self/ns/mnt`; iterates 8 `/apex/com.android.{runtime,art}/bin/dex2oat*` paths and also unmounts leaked `/data/resource-cache` if `mountinfo` root is `$MODDIR/.resource-cache`, `${MODDIR#/data}/.resource-cache`, or ` - tmpfs `.
- **`zygisk/module/post-fs-data.sh` (9 lines):**
  - Line 6: `[ "$KSU" = true ] || exit 0`; line 8: `exec sh "$MODDIR/emulated-soft-reboot.sh"`.
- **`zygisk/module/service.sh` (29 lines):**
  - Lines 8–10: if `[ "$KSU" = true ]`, runs `sh "$MODDIR/emulated-soft-reboot.sh"`.
  - Lines 16–28: probes `unshare -m true 2>/dev/null`, then spawns `unshare -m /system/bin/sh -c '...' "$MODDIR/daemon" "$@" &`. Inside unshared ns, runs `if mount -o rslave none / 2>/dev/null; then VECTOR_SHADOW_OK=1 exec "$0" "$@"; else ... VECTOR_NO_SHADOW=1 exec "$0" "$@"; fi`. If `unshare -m true` fails, runs `VECTOR_NO_SHADOW=1 "$MODDIR/daemon" "$@" &`.
  - *Toybox edge case observed:* `toybox mount --help` on this host (`Toybox 0.8.13-android`) does not list `rslave` in its documented options (unlike util-linux/BusyBox `--make-rslave` or `mount -o rslave`). Thus `mount -o rslave none /` failing and triggering the `VECTOR_NO_SHADOW=1` fallback is a realistic runtime branch that host tests must explicitly cover.
- **`zygisk/module/daemon` (57 lines):**
  - Lines 35–40: `if [ "$VECTOR_SHADOW_OK" = "1" ]; then` creates `$dir/.resource-cache` and runs `mount -o bind "$emptyDir" /data/resource-cache`. When `VECTOR_SHADOW_OK` is unset or `VECTOR_NO_SHADOW=1`, no bind mount is performed.
  - Line 46: `while [ ! -S "/dev/socket/zygote" ] &&[ "$wait_count" -lt 100 ]; do` (no space between `&&` and `[`, valid in mksh/bash/ash, though unusual).

#### Target B: `InlineHookProcessPolicy` & Native ART Inline-Hook Cleanup
- **`daemon/src/main/kotlin/org/matrix/vector/daemon/data/InlineHookProcessPolicy.kt` (lines 1–29):**
  - `matchesSystemUiVirtualPackage(configuredPackages: Set<String>, processName: String, uid: Int): Boolean` — true iff `"system" in configuredPackages && uid == Process.SYSTEM_UID && processName == "system:ui"` (using `SYSTEM_UI_VIRTUAL_PACKAGE = "system"` and `SYSTEM_UI_PROCESS = "system:ui"` from `PreferenceStore.kt:10-11`).
  - `matchesPackage(expectedUid: Int, actualUid: Int, processName: String, applicationProcessName: String?, componentProcesses: Set<String>): Boolean` — requires `expectedUid == actualUid` and exact membership (`processName == applicationProcessName || processName in componentProcesses`, avoiding prefix-matching bugs).
  - `mayInvalidate(processName: String, uid: Int): Boolean` — `uid != Process.SYSTEM_UID || processName != "system"` (protects `system_server` while still allowing `system:ui` at `uid == 1000`).
- **`daemon/src/main/kotlin/org/matrix/vector/daemon/data/PreferenceStore.kt` (lines 108–158):**
  - `getInvalidateArtInlineHookPackages()`, `setInvalidateArtInlineHooks(packageName, enabled)` (trims, rejects blank), `shouldInvalidateArtInlineHooks(processName, uid)` (checks `matchesSystemUiVirtualPackage`, then resolves `userId = uid / PER_USER_RANGE`, skips `SYSTEM_UI_VIRTUAL_PACKAGE`, queries `packageManager?.getPackageInfoWithComponents`, calls `InlineHookProcessPolicy.matchesPackage`).
- **`daemon/src/main/kotlin/org/matrix/vector/daemon/ipc/FrameworkService.kt` (lines 250–258):**
  - `INVALIDATE_ART_INLINE_HOOKS_TRANSACTION_CODE` gates on `InlineHookProcessPolicy.mayInvalidate(info.processName, info.key.uid) && PreferenceStore.shouldInvalidateArtInlineHooks(info.processName, info.key.uid)`.
- **`zygisk/src/main/cpp/module.cpp` (lines 365–376, 402–404, 493) & `native/src/core/art_inline_hook_cleanup.cpp` (505 lines):**
  - `module.cpp:365-368`: excludes `is_manager_app_`, calls `ConfigureArtInlineHookInvalidation(invalidate_art_inline_hooks_requested)`.
  - `module.cpp:493`: `postServerSpecialize` unconditionally calls `ConfigureArtInlineHookInvalidation(false)`.
  - `art_inline_hook_cleanup.cpp:411-502`:
    - `ConfigureArtInlineHookInvalidation(false)` resets targets, preserved pages, enabled/completed flags, returns `false`.
    - `ConfigureArtInlineHookInvalidation(true)` calls `CaptureLibArtExecutableState()`; if `libart.so` isn't found or snapshot fails, leaves `g_art_invalidation_enabled = false` and returns `false` (fail-safe so pre-existing hooks are never clobbered).
    - `RecordArtInlineHookInvalidationTarget` / `ForgetArtInlineHookInvalidationTarget`: deduplicates, ignores null or post-completion calls.
    - `InvalidateArtInlineHooksIfEnabled()`: if disabled or already completed returns `true`; if `tracked_targets == 0` marks completed and returns `true`; otherwise remaps dirty executable `PT_LOAD` segments via `MAP_PRIVATE | MAP_FIXED`, reapplies `g_preserved_art_pages`, and resets state.

#### Target C: Native Log Truncation Null-Termination
- **`native/include/common/logging.h` (lines 67–76, `vector::native::detail::LogToAndroid`):**
  - Line 71: `std::array<char, 1024> buf{};`
  - Line 73: `auto result = fmt::format_to_n(buf.data(), buf.size() - 1, fmt, std::forward<T>(args)...);`
  - Line 74: `*result.out = '\0';  // result.size includes characters truncated by format_to_n.`
  - Line 75: `__android_log_write(prio, tag, buf.data());`
  - Commit `1b50ef4b` replaced `buf[result.size] = '\0'` with `*result.out = '\0'`. When formatted output >= 1024 chars, `fmt::format_to_n(..., 1023, ...)` sets `result.size` to the untruncated length (>= 1024, out-of-bounds!) while `result.out` is clamped at `buf.data() + 1023` (valid index 1023).
- **Related secondary observation (caveat):**
  - `daemon/src/main/jni/logging.h` and `dex2oat/src/main/cpp/include/logging.h` delegate directly to `__android_log_print` (no `format_to_n`).
  - However, `daemon/src/main/jni/logcat.cpp:106-116` computes `int m_len = snprintf(meta_buf, sizeof(meta_buf), ".%03ld %8d:%6d:%6d %c/%-15.*s ] ", ...)` and passes `(size_t)m_len` to `iov[2]` without clamping `m_len` to `sizeof(meta_buf) - 1` if `tagLen` causes truncation (note `%-15.*s` sets minimum width 15, not max precision).

#### Target D: Samsung `android.uid.system` Legacy-Hook Guard
- **`legacy/src/main/java/org/matrix/vector/legacy/LegacyDelegateImpl.java` (lines 65–81, `processLegacyHook`):**
  - Guard:
    ```java
    if ("getApplicationInfoAsUser".equals(executable.getName())
            && args != null
            && args.length > 0
            && "android.uid.system".equals(args[0])) {
        try {
            return invokeOriginal.invoke();
        } catch (Throwable t) {
            sneakyThrow(t);
            return null;
        }
    }
    ```
  - Must bypass `legacyHooks` (`beforeHookedMethod`/`afterHookedMethod`) ONLY when method name is `"getApplicationInfoAsUser"` AND `args != null && args.length > 0 && "android.uid.system".equals(args[0])`, while preserving checked/unchecked exception propagation via `sneakyThrow(t)` and falling through to `LegacyApiSupport` for all other methods, null/empty `args`, or other package names.
- **`legacy/src/main/java/de/robv/android/xposed/XposedBridge.java` (lines 140–154, `XposedBridge.log(Throwable t)`):**
  - Guard:
    ```java
    if (t != null
            && "android.content.pm.PackageManager$NameNotFoundException".equals(t.getClass().getName())
            && "android.uid.system".equals(t.getMessage())) {
        return;
    }
    ```
  - Suppresses ONLY exact class name `android.content.pm.PackageManager$NameNotFoundException` with exact message `"android.uid.system"`, while still forwarding other `NameNotFoundException` messages, other exception classes with `"android.uid.system"`, and `null` to `org.matrix.vector.util.Log.getStackTraceString(t)` + `Log.e`.

### 1.4 Host Toolchain Audit (Linux ARM64 Android Userspace)

| Tool | Status on Host | Exact Path / Version / Notes |
|---|---|---|
| `python3` | Installed | `/data/user/0/com.agy/files/usr/bin/python3` — `Python 3.14.6` |
| `sh` / `bash` | Installed | `/data/user/0/com.agy/files/usr/bin/sh`, `bash` `5.3.20(1)-release`, `/system/bin/sh` (Android mksh) |
| `awk` | Installed | `/data/user/0/com.agy/files/usr/bin/awk` and `/system/bin/awk` |
| `toybox` | Installed | `/system/bin/toybox` — `0.8.13-android` (`unshare`, `mount`, `umount`, `readlink`, `tr`, `ls`, `kill`, `sleep`) |
| `busybox` | Not installed | Available in `pkg` repo (`busybox 1.38.0-1`) |
| `java` / `javac` | Installed (not on `$PATH`) | `$PREFIX/lib/jvm/java-25-openjdk/bin/{java,javac}` — `OpenJDK 25.0.5.1` (`openjdk-21` `21.0.13.1` also in `pkg` repo) |
| `clang` / `clang++` / `g++` | Not installed | `clang 21.1.8-3` available in `pkg` repo |
| Android SDK | Installed | `$HOME/android-sdk/platforms/android-37.2/android.jar` and `android-37.2-2/android.jar`; `build-tools/36.0.0` |
| Gradle cache | Partial | `$HOME/.gradle/wrapper/dists/gradle-9.7.1-bin/` (repo wrapper wants `9.8.2`); `kotlin-stdlib` `2.4.0` & `2.4.10` (repo wants `2.4.21`) |
| `readelf` | Installed | `/system/bin/readelf` |

---

## 2. Logic Chain

1. **Baseline host verification gap:** From §1.2, `check_translations.py` and `git diff --check` are standalone and pass immediately (`exit 0`). In contrast, `tests/reattach/run_tests.py` and `tests/hotload/run_tests.py` currently fail early (`exit 2` without args) and depend on: (a) initialized submodules (`external/*`, `services/libxposed`, `xposed/libxposed`), (b) compiled Gradle intermediates, (c) a host C++17/C++20 compiler (`c++`/`g++`), and (d) for `hotload`, fixing `android-37.0` hardcoding (§1.2.2) and `kotlin-stdlib` version resolution.
2. **Host testability of the 4 R2 targets without a device reboot:**
   - **Soft-reboot & shadow gating (§1.3 Target A):** `emulated-soft-reboot.sh`, `post-fs-data.sh`, `service.sh`, and `daemon` are pure POSIX shell scripts operating on `/proc` files, `readlink`, `ls -ld`, `awk`, `tr`, `kill`, `umount`, `unshare`, and `mount`. Putting a controlled `PATH` shim directory first (mocking `unshare`, `mount`, `umount`, `kill`, `ls`, `readlink`, `app_process`, `getprop`, `log`, and fake `/proc` / `mountinfo` / `fdinfo` fixtures—or parameterizing paths via env overrides in tests without modifying production behavior) lets Python/sh test both Toybox and BusyBox output shapes (`ls -ld` column spacing, `mount -o rslave` success vs failure fallback to `VECTOR_NO_SHADOW=1`, `VECTOR_SHADOW_OK=1` bind mount, `init_ns != self_ns` skip, stale `dex2oat` + `/data/resource-cache` unmount, PID reuse `process_start` guard, and `post-fs-data.sh`/`service.sh` `$KSU` gating). Note: for `service.sh` (`unshare -m /system/bin/sh ...`), `PATH` shims catch `unshare`, `mount`, `umount`, etc., while `/system/bin/sh` exists natively on this host!
   - **`InlineHookProcessPolicy` (§1.3 Target B):** `InlineHookProcessPolicy.kt` is pure Kotlin with only `android.os.Process.SYSTEM_UID` and two string constants (`SYSTEM_UI_VIRTUAL_PACKAGE`, `SYSTEM_UI_PROCESS`). It can be tested either via compiled `:daemon` classes (like `tests/reattach/`) OR standalone by compiling/invoking against a tiny `android.os.Process` stub, and `art_inline_hook_cleanup.cpp` state transitions (`ConfigureArtInlineHookInvalidation(false)`, missing `libart.so` snapshot fail-safe, `Record`/`Forget`, `InvalidateArtInlineHooksIfEnabled` with 0 targets & idempotency) can be compiled and tested on host with `clang++` + a `<android/log.h>` / `<fmt/format.h>` host shim (or `-DLOG_DISABLED` + minimal `fmt` stub) and a mock `dl_iterate_phdr` / temporary ELF file!
   - **Native log truncation null-termination (§1.3 Target C):** `native/include/common/logging.h` defines `vector::native::detail::LogToAndroid` as a header-only template. A tiny host C++ test harness (`clang++`) providing a capturing `__android_log_write` stub and `fmt::format_to_n` (directly from `external/fmt` once submodules are initialized, or verifying both real `fmt::format_to_n` and boundary lengths 0, 1022, 1023, 1024, 4096) directly executes production `LogToAndroid` under AddressSanitizer (`-fsanitize=address,undefined`) to prove zero OOB writes and guaranteed `buf[1023] == '\0'`.
   - **Samsung `android.uid.system` guard (§1.3 Target D):** `LegacyDelegateImpl.processLegacyHook` and `XposedBridge.log(Throwable)` require only JDK reflection (`Method`/`Constructor`) plus small compile-time Android/Vector stubs (`android.content.res.XResources`, `android.content.pm.PackageManager$NameNotFoundException`, `org.matrix.vector.util.Log`, `VectorLegacyCallback`, `HookBridge`, etc.)—mirroring the exact `tests/reattach/` and `tests/hotload/` pattern.

---

## 3. Caveats

- **Read-only audit:** No repository files were modified and no `pkg install` or `git submodule update` commands were run.
- **Unverified Gradle/host execution of `reattach` and `hotload`:** Because `external/*` submodules are uninitialized, `c++`/`g++` is not installed, and Gradle intermediates are absent, `tests/reattach/run_tests.py` and `tests/hotload/run_tests.py` could not yet be executed past argument validation.
- **Hardcoded `/system/bin/sh` & `/proc` in shell scripts:** In `emulated-soft-reboot.sh`, `visible_mount` reads `/proc/self/fdinfo/3` and `cleanup_target` reads `/proc/self/mountinfo` directly (not via `$PROC_DIR`). Host tests on Linux/Android can test `visible_mount` against a real fd (`exec 3< "$tmpfile"` works on host!), while `cleanup_target` and `daemon_candidates` either require testing the helper functions / a lightweight `mountinfo`/`proc` root hook or `unshare -r -m` (if permitted by SELinux, though on Android app sandbox `unshare` may return `EPERM` — note `service.sh`'s `unshare -m` failure branch actually runs naturally in sandbox!).
- **Secondary `snprintf` truncation site:** `daemon/src/main/jni/logcat.cpp:106-116` (`FastWrite`) passes `snprintf`'s return value `m_len` directly to `iovec.iov_len` without clamping if `m_len >= sizeof(meta_buf)` (96 bytes). Not part of `1b50ef4b`, flagged for R1/R2 awareness.

---

## 4. Conclusion & Recommended R2 Test Architecture

1. **Fix / Unblock Existing Host Suites First:**
   - Initialize submodules (`git submodule update --init --recursive`), install `clang` (+ optionally `busybox` for BusyBox vs Toybox dual-shell matrix testing) on host via `pkg install clang busybox`.
   - Patch `tests/hotload/run_tests.py:50` to resolve `android.jar` dynamically (`sorted((args.sdk / 'platforms').glob('android-*/android.jar'))[-1]`, matching `tests/reattach/run_tests.py:31`) and allow `CXX` / `clang++` fallback when `g++` is absent (same for `c++` in `tests/reattach/run_tests.py:49`).
   - Run `:daemon:compileReleaseKotlin :zygisk:compileReleaseKotlin :xposed:compileReleaseKotlin :services:daemon-service:compileReleaseJavaWithJavac :services:manager-service:compileReleaseJavaWithJavac :hiddenapi:stubs:jar` (or verify CI can also run all host suites!).
2. **Add Dedicated Host Regression Suites Under `tests/` (following the exact `tests/reattach` & `tests/hotload` zero-device pattern):**
   - **`tests/softreboot/run_tests.py`** (Python 3 + `/system/bin/sh` + `bash` + `busybox` if present):
     - Tests `post-fs-data.sh`, `service.sh`, `daemon`, and `emulated-soft-reboot.sh` with `PATH`-injected BusyBox/Toybox tool shims:
       1. `$KSU != true` / `$APATCH != true` no-op exits.
       2. `service.sh` `unshare -m` failure -> `VECTOR_NO_SHADOW=1`, `VECTOR_SHADOW_OK` unset, `daemon` does NOT bind-mount `/data/resource-cache`.
       3. `service.sh` `mount -o rslave none /` failure -> `VECTOR_NO_SHADOW=1` fallback.
       4. `service.sh` success -> `VECTOR_SHADOW_OK=1`, `daemon` bind-mounts `$dir/.resource-cache` onto `/data/resource-cache`.
       5. `emulated-soft-reboot.sh`: Toybox vs BusyBox `ls -ld /proc/<pid>/cwd` + `awk` matching (including ` (deleted)`), `process_start()` with comm containing spaces/`)`, TOCTOU PID-reuse rejection, `visible_mount` fdinfo parsing, and `cleanup_target` `mountinfo` root matching (`$MODDIR/bin/dex2oat{32,64}`, `${MODDIR#/data}/bin/dex2oat{32,64}`, `\040(deleted)`, `//deleted`, and non-matching roots left untouched).
   - **`tests/policy/run_tests.py`** (or extend `tests/reattach/` + native C++ test):
     - **Kotlin/JVM (`InlineHookProcessPolicy`):** Test `matchesSystemUiVirtualPackage` (`"system"` + `uid=1000` + `"system:ui"` vs wrong uid/process), `matchesPackage` (exact `applicationProcessName`, `componentProcesses`, UID mismatch, and prefix-trap `"com.foo.bar"` vs `"com.foo"`), and `mayInvalidate` (`uid=1000, "system"` -> `false`; `uid=1000, "system:ui"` -> `true`; `uid=10123, "system"` -> `true`).
     - **Native (`art_inline_hook_cleanup.cpp`):** Compile with `clang++ -std=c++20 -fsanitize=address,undefined` against minimal host stubs, exercising `ConfigureArtInlineHookInvalidation(false)`, `ConfigureArtInlineHookInvalidation(true)` fail-safe when `libart.so` is absent, `Record`/`Forget`, and `InvalidateArtInlineHooksIfEnabled()` zero-target & idempotency paths.
   - **`tests/logging/run_tests.py`** (Native C++20 + ASan/UBSan):
     - Includes `native/include/common/logging.h` directly with `external/fmt` and a test `__android_log_write` capture sink.
     - Tests exact-fit (1023 chars), off-by-one (1024 chars), and large overflow (4096 chars) messages under `-fsanitize=address,undefined`, asserting captured string length == 1023, NUL-terminated at `buf[1023]`, and zero ASan/UBSan errors.
   - **`tests/legacy/run_tests.py`** (Java against compiled `:legacy` + `:xposed` classes + minimal `XResources`/`NameNotFoundException` stubs):
     - Tests `LegacyDelegateImpl.processLegacyHook`:
       1. `getApplicationInfoAsUser("android.uid.system", ...)` bypasses `legacyHooks` (0 before/after calls) and returns/sneakyThrows original `NameNotFoundException`.
       2. `getApplicationInfoAsUser("com.other.pkg", ...)` and non-matching method names with `"android.uid.system"` still dispatch to `legacyHooks`.
       3. Null/empty `args` does not NPE and dispatches normally.
     - Tests `XposedBridge.log(Throwable)`:
       1. `android.content.pm.PackageManager$NameNotFoundException("android.uid.system")` is suppressed (0 `Log.e` calls).
       2. `NameNotFoundException("com.other.pkg")`, `RuntimeException("android.uid.system")`, and `null` are NOT suppressed.

---

## 5. Verification Method

To independently verify all claims in this report:
1. Re-run baseline checks from `/data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX`:
   - `python3 manager/tools/check_translations.py manager/src/main/res` (expect exit `0`, `problems: 0`)
   - `python3 manager/tools/check_translations.py daemon/src/main/res` (expect exit `0`, `problems: 0`)
   - `GIT_CONFIG_NOSYSTEM=1 git -c safe.directory=/data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX diff --check` (expect exit `0`)
2. Inspect exact cited files and line numbers:
   - `tests/reattach/run_tests.py:31,49` and `tests/hotload/run_tests.py:45-50,86`
   - `zygisk/module/emulated-soft-reboot.sh:6-132`, `zygisk/module/service.sh:8-28`, `zygisk/module/post-fs-data.sh:6-8`, `zygisk/module/daemon:35-40`
   - `daemon/src/main/kotlin/org/matrix/vector/daemon/data/InlineHookProcessPolicy.kt:6-28`, `PreferenceStore.kt:10-11,108-158`, `FrameworkService.kt:250-258`, `native/src/core/art_inline_hook_cleanup.cpp:411-502`
   - `native/include/common/logging.h:69-76`
   - `legacy/src/main/java/org/matrix/vector/legacy/LegacyDelegateImpl.java:66-81`, `legacy/src/main/java/de/robv/android/xposed/XposedBridge.java:140-154`
3. Invalidation condition: if submodules are initialized, `clang`/`busybox` are installed via `pkg`, or `tests/*/run_tests.py` are updated after this timestamp, re-run the baseline commands and update toolchain/status accordingly.
