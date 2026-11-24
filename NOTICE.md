# Modification & Attribution Notice (GPL-3.0 Section 5)

**Vector-SRX** (`HazY-Codes/Vector-SRX`) is a modified derivative work distributed under the terms of the **GNU General Public License v3.0 (GPL-3.0)**.

It is an independent community continuation and is **not** an official release of `JingMatrix/Vector` or `byemaxx/Vector-SR`. All original copyrights remain with their respective authors.

## Upstream Provenance & Attributed Sources

1. **Primary Upstream Baseline — [`JingMatrix/Vector`](https://github.com/JingMatrix/Vector)**
   * Baseline commit: `6f6b976e` (`v2.2 / 3119`, 2026-11-15)
   * License: GPL-3.0
   * Also incorporates unmerged upstream PR `JingMatrix/Vector#579` (`3c46968c` by JingMatrix: ARM64 OEM `libart.so` inline-hook veneer unwrapping).

2. **Soft-Restart & Runtime Recovery — [`byemaxx/Vector-SR`](https://github.com/byemaxx/Vector-SR)**
   * Reference branches: `master` (`45d20109`, 2026-11-12) and `merge_upstream_manager` (`7065a816`, 2026-08-08)
   * License: GPL-3.0
   * Ported/adapted components (2026-11-24):
     * Active daemon reinjection owner persistence, PID-reuse verification (extended for `vectord` and `lspd`), and split-brain restart lease locking (`FileSystem.kt`, `VectorDaemon.kt`).
     * Dual-namespace (`daemon` + `init`) `dex2oat` wrapper mount/unmount recovery, stock compiler inode verification, optional debug compiler (`dex2oatd`) handling, and failure-only `--inline-max-code-units=0` property fallback (`Dex2OatServer.kt`, `daemon/src/main/jni/dex2oat.cpp`).
     * Thread-safe `ManagerGuard` replacement/invalidation (`ManagerService.kt`), prioritized Vector handshake transactions (`SystemServerService.kt`), 20-second Zygisk `system_server` rendezvous retry window (`ipc_bridge.cpp`), Android 16 vendor `linkToDeath` specialization resilience (`VectorServiceClient.kt`), `.dynsym` export hiding (`zygisk/src/main/cpp/CMakeLists.txt`), and truncated `fmt::format_to_n` null-termination fix (`native/include/common/logging.h`).

3. **Upstream Community Pull Requests Integrated (2026-11-24)**
   * `JingMatrix/Vector#1008` (`XR-stb`): PID-scoped `VECTOR_DEX2OAT_WRAPPER_PID` recursive wrapper execution guard (`dex2oat/src/main/cpp/dex2oat.cpp`).
   * `JingMatrix/Vector#1012` (`bartasc4`): KernelSU emulated soft-reboot daemon and stale compiler mount cleanup (`emulated-soft-reboot.sh`, `post-fs-data.sh`, adapted for `vectord`/`lspd` and bind-mounted/unshadowed resource-cache states).
   * `JingMatrix/Vector#1013` (`bartasc4` / `lzup333` / `byemaxx`): Central per-app ART inline-hook invalidation policy, native Dobby executable-page restoration (`art_inline_hook_cleanup.cpp`), and Jetpack Compose Manager picker UI (`InlineHookInvalidationPicker.kt`).
   * `JingMatrix/Vector#1017` & `#1018` (`bartasc4`): Live `system_server` re-attachment after daemon restart (`IVectorDaemon.reconnectSystemModule`, `BridgeService.kt`) and API 102 hot-reload DEX/callback resource cleanup.
   * `JingMatrix/Vector#1103` & `#1104` (`McTr0`): Private mount-namespace verification (`VECTOR_SHADOW_OK=1`) before shadowing `/data/resource-cache` (`service.sh`, `daemon`) and `android`/`system` framework scope normalization (`ModuleDatabase.kt`, `ConfigCache.kt`, `VectorService.kt`).

4. **Samsung One UI 8.5 / 9 Shared-UID Hotfix — [`peppicry/Vector-SR-S24U-Hotfix`](https://github.com/peppicry/Vector-SR-S24U-Hotfix)**
   * License: GPL-3.0
   * Ported `android.uid.system` pseudo-package lookup bypass in `LegacyDelegateImpl.java` and `XposedBridge.java`.
