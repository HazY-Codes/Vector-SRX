**Vector-SRX** brings all of **Vector-SR**'s soft-reboot, reinjection, and ART compatibility enhancements forward onto the latest upstream **Vector v2.2** codebase (with the modern Jetpack Compose Manager and libxposed API 102).

### 🔄 Soft-Reboot & Late-Load Reliability
*   **KernelSU Emulated Soft-Reboot & Private Shadow Isolation:** Cleans up surviving daemons, stale `/apex/.../bin/dex2oat*` bind mounts, and leaked shadows across `ksud soft-reboot`, while gating `/data/resource-cache` shadowing on a verified private mount namespace (`VECTOR_SHADOW_OK=1`) so RRO idmaps (`android.ext.services`) never blank out or boot-loop `system_server`.
*   **Split-Brain Reinjection Ownership & Lease:** Ported Vector-SR's active reinjection owner and restart lease locks (`vectord` / `lspd` aware), atomic `bridgeBinder` death deduplication, thread-safe `ManagerGuard` invalidation, and a 20-second Zygisk `system_server` rendezvous window.
*   **Live `system_server` Re-Attachment:** Reconnects an already-injected `system_server` and its loaded modern modules & remote preferences after a daemon restart without re-running module entrypoints or restarting Android.
*   **Dual-Namespace `dex2oat` Recovery & Recursion Guard:** Unmounts/remounts `dex2oat` wrappers across both daemon and `init` mount namespaces, validates stock compiler inodes, adds a PID-scoped wrapper recursion guard, treats `dex2oatd` debug binaries as optional, and provides failure-only `--inline-max-code-units=0` property fallback.

### 🛡️ ART Hooking & OEM ROM Compatibility
*   **Per-App ART Inline-Hook Compatibility Mode:** Opt-in central app picker in the Compose Manager (`System Status -> ART inline hook compatibility mode`) that invalidates Vector's temporary `libart.so` inline hooks after framework bootstrap while hiding internal `.dynsym` exports in `libzygisk.so`.
*   **OEM ARM64 Veneer Unwrapping:** Unwraps 16-byte OEM `libart.so` hotpatch trampolines (`ldr x16/17, pc+8; br x16/17`) before Dobby inline hooking to prevent infinite recursion in `system_server`.
*   **Samsung One UI 8.5 / 9 Shared-UID Hotfix:** Bypasses legacy hook callbacks around `ApplicationPackageManager#getApplicationInfoAsUser("android.uid.system")` to prevent post-soft-reboot `VectorLegacyBridge` log and CPU storms.
*   **System Scope Normalization & Hot-Reload Cleanup:** Normalizes `android` and `system` scope spellings so CLI/libxposed system-scoped modules always dispatch `onSystemServerStarting`, and releases retired API 102 hot-reload DEX mappings and callbacks.
