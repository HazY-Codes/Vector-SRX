# Project: Vector-SRX

## Architecture
Vector-SRX is an Android 8.1–17 Xposed / `libxposed` API 102 Zygisk framework fork (`id=zygisk_vector`, `HazY-Codes/Vector-SRX`) built on `JingMatrix/Vector` v2.2 with Vector-SR soft-reboot, reinjection, `dex2oat`, per-app ART inline-hook invalidation, ARM64 OEM veneer unwrapping, and Samsung One UI `android.uid.system` fixes.

- `:zygisk` — Magisk/KSU/APatch module scripts (`zygisk/module/*`), `libzygisk.so` (`zygisk/src/main/cpp/*`), Zygote/system_server entry (`Main.kt`, `BridgeService.kt`), `vector.dex` packaging & `checkXResourcesIsolationRelease`.
- `:daemon` — Root daemon (`VectorDaemon.kt`, `VectorService.kt`), `ConfigCache.kt`, `ModuleDatabase.kt`, `PreferenceStore.kt`, `InlineHookProcessPolicy.kt`, `FileSystem.kt`, `Dex2OatServer.kt`, `CliHandler.kt`, JNI (`daemon/src/main/jni/*`), `SignInfo` embedding.
- `:dex2oat` — Native `dex2oat` wrapper (`dex2oat.cpp`) and `liboat_hook.so` (`oat_hook.cpp`).
- `native/` — Static C++23 library linked into `:zygisk` (`hook_bridge.cpp`, `native_api.h/.cpp`, `art_inline_hook_cleanup.cpp`, `logging.h`).
- `:xposed` & `:legacy` — Framework Java runtime (`VectorContext.kt`, `VectorModuleManager.kt`, `DexMemoryScope.kt`, `OnDemandSerialExecutor.kt`) and legacy Xposed compatibility (`LegacyDelegateImpl.java`, `XposedBridge.java`).
- `:services:daemon-service`, `:services:manager-service` — AIDL IPC contracts.
- `:manager`, `:manager-ui` — Jetpack Compose Material 3 Manager UI (`HomeViewModel.kt`, `ScopeViewModel.kt`, `ModuleDetection.kt`, `GitHubRepository.kt`).
- `:tests` — Host regression suites (`tests/reattach/`, `tests/hotload/`, plus new R2 suites `tests/softreboot/`, `tests/policy/`, `tests/logging/`, `tests/legacy/`).

## Feature Inventory
Every surveyed feature and requirement from `ORIGINAL_REQUEST.md` and Survey 1–3 reports:

| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F1 | Emulated soft-reboot & mount-ns cleanup | Fix Toybox/BusyBox `mount -o rslave` vs `--make-rslave` in `service.sh`, align `$APATCH` gating in `post-fs-data.sh`/`service.sh`, safe `MODDIR` resolution in `emulated-soft-reboot.sh`, `VECTOR_SHADOW_OK` gating | M1 | Survey 1, Survey 3 |
| F2 | `android`/`system` staticScope normalization | Normalize `"android"` -> `"system"` in `FileSystem.readStaticScope`, `ModuleDatabase.setModuleScope`/`pruneScopeToClaimed`, `CliHandler`, `ModuleDetection.inspect`, and `ScopeViewModel` | M1 | Survey 1 |
| F3 | Reinjection `linkToDeath` race & JNI/veneer hardening | Retry/recover `RemoteException` in `VectorDaemon.sendToBridge` `linkToDeath`; fix 6-vs-5 arg `forkCommon` `FindAndCall` in `module.cpp`; guard `ResolveOEMVeneer` in `native_api.h` & clamp `logcat.cpp` `snprintf` | M1 | Survey 1, Survey 3 |
| F4 | Repo hygiene, branding & attribution | Exclude `.agents/` in `.git/info/exclude`, fix `README.md:55` typo, verify `LICENSE`, `NOTICE.md`, `module.prop` (`id=zygisk_vector`, `name=Vector-SRX`) | M1 | Survey 1, Survey 2 |
| F5 | CI CMake 4.x / NDK r30 `import std;` build fix | Disable `FMT_MODULE` in `external/CMakeLists.txt` and pass `-DCMAKE_CXX_MODULE_STD=OFF -DFMT_MODULE=OFF` in `build.gradle.kts` while keeping `dex_builder.ixx` module scan enabled | M2 | Survey 1, Survey 2 |
| F6 | CI workflow hardening & packaging checks | Review `.github/workflows/core.yml` (Node 24 / ninja / host checks / symbol & APK packaging checks across 4 ABIs) | M2 | Survey 2 |
| F7 | Unblock & run existing host verification suites | Init submodules, ensure host `clang` (+ `busybox` if available), fix `tests/hotload/run_tests.py` SDK/CXX resolution & `tests/reattach/run_tests.py` CXX fallback, run `check_translations.py`, `reattach`, `hotload`, `git diff --check` | M3 | Survey 3 |
| F8 | Expand automated host R2 regression suites | Add host-executable tests for soft-reboot/shadow gating (`tests/softreboot/`), `InlineHookProcessPolicy` + `art_inline_hook_cleanup.cpp` + scope normalization (`tests/policy/`), `logging.h` truncation null-termination under ASan/UBSan (`tests/logging/`), and `android.uid.system` legacy-hook guard (`tests/legacy/`) | M3 | Survey 3, ORIGINAL_REQUEST |
| F9 | Multi-ABI CI green, artifact integrity & `v2.2.1` release | Push to `origin` (`HazY-Codes/Vector-SRX`), verify `Core` green across 4 ABIs, audit downloaded Release/Debug `.zip` + `.sha256` + `.dynsym` + `CN=Vector-SRX` `SignInfo`, sync `zygisk/update.json` & `changelog.md`, tag & verify `v2.2.1` `--latest` release | M4 | Survey 2, ORIGINAL_REQUEST |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | R1 Hardening & Repo Hygiene | F1, F2, F3, F4 (`zygisk/module/*`, `daemon/*`, `manager-ui/*`, `zygisk/src/main/cpp/module.cpp`, `native/include/core/native_api.h`, `README.md`, `.git/info/exclude`) | none | IN_PROGRESS |
| M2 | R3 CI & CMake Build Fix | F5, F6 (`build.gradle.kts`, `external/CMakeLists.txt`, `.github/workflows/core.yml`, `dex2oat/src/main/cpp/CMakeLists.txt`) | none | IN_PROGRESS |
| M3 | R2 Host Regression & Verification Suite | F7, F8 (`tests/*`, host toolchain & submodules, `check_translations.py`) | M1, M2 | PLANNED |
| M4 | Multi-ABI CI, Artifact Audit & `v2.2.1` Release | F9 (push to `origin`, CI verification, downloaded `.zip`/`.dynsym`/`.sha256`/signing checks, `zygisk/update.json` sync, tag `v2.2.1` release) | M1, M2, M3 | PLANNED |

## Interface Contracts & Code Layout
- **Write Ownership (M1 vs M2)**:
  - M1 owns: `zygisk/module/*`, `daemon/*`, `manager-ui/*`, `zygisk/src/main/cpp/module.cpp`, `native/*`, `legacy/*`, `README.md`, `.git/info/exclude`.
  - M2 owns: `build.gradle.kts`, `external/CMakeLists.txt`, `.github/workflows/core.yml`, `dex2oat/src/main/cpp/CMakeLists.txt`.
  - M3 owns: `tests/*` (plus read-only execution of `manager/tools/check_translations.py` and host builds).
  - M4 owns: `zygisk/update.json`, `zygisk/changelog.md`, git push/tag/release on `origin` (`HazY-Codes/Vector-SRX`) ONLY.
- **Metadata Hygiene**: All agent metadata lives exclusively under `.agents/teamwork/` (excluded via `.git/info/exclude`), never committed to git and never included in release `.zip` artifacts.
- **Remote & Device Safety**: Push/tag/release ONLY to `origin` (`HazY-Codes/Vector-SRX`) using `GIT_CONFIG_NOSYSTEM=1` + `http.https://github.com/.extraheader`. Never touch `JingMatrix/Vector` or `byemaxx/Vector-SR`. Never install modules or reboot/soft-reboot the host device.
