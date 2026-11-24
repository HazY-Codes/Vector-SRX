<div align="center">

# Vector-SRX

**Soft-Reboot Extended Modern Xposed / libxposed Framework (Android 8.1 – 17)**

[![Build](https://img.shields.io/github/actions/workflow/status/HazY-Codes/Vector-SRX/core.yml?branch=master&event=push&logo=github&label=Build)](https://github.com/HazY-Codes/Vector-SRX/actions/workflows/core.yml?query=event%3Apush+branch%3Amaster+is%3Acompleted)
[![Download](https://img.shields.io/github/v/release/HazY-Codes/Vector-SRX?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/HazY-Codes/Vector-SRX/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](./LICENSE)

</div>

---

### Introduction

**Vector-SRX** (*Soft-Reboot Extended*) is an independently maintained fork of [JingMatrix/Vector](https://github.com/JingMatrix/Vector) that forward-ports and extends the soft-restart, reinjection, and ART compatibility improvements from [byemaxx/Vector-SR](https://github.com/byemaxx/Vector-SR) onto the latest **Vector v2.2** codebase — including the modern Jetpack Compose Manager and full `libxposed` API 102 hot-reload support.

Vector-SRX retains the standard module ID (`id=zygisk_vector`) so it installs cleanly as an in-place upgrade over existing **Vector** or **Vector-SR** setups across **KernelSU / KernelSU Next**, **Magisk**, and **APatch** with **NeoZygisk** or **Zygisk Next**.

> [!NOTE]
> **Unofficial Fork Notice:** Vector-SRX is an independent community continuation and is not affiliated with or endorsed by JingMatrix or byemaxx. Please report issues with Vector-SRX builds to [HazY-Codes/Vector-SRX/issues](https://github.com/HazY-Codes/Vector-SRX/issues), not to upstream repositories. See [NOTICE.md](./NOTICE.md) for full GPL-3.0 modification and provenance details.

---

### Key Enhancements Over Stock Vector

* **KernelSU Emulated Soft-Reboot & Private Shadow Isolation:**
  * Cleans up surviving `vectord`/`lspd` daemons, stale `/apex/com.android.{art,runtime}/bin/dex2oat*` bind mounts, and leaked resource-cache mounts during `ksud soft-reboot` (`emulated-soft-reboot.sh` + `post-fs-data.sh`).
  * Gates `/data/resource-cache` shadowing on a verified private slave mount namespace (`VECTOR_SHADOW_OK=1`), preventing leaked global shadows from hiding RRO idmap files (`android.ext.services`) and boot-looping `system_server` on GMS / HyperOS / One UI ROMs.
* **Split-Brain Reinjection Ownership & Lease Recovery:**
  * Active daemon ownership tracking (`vector_reinjection_owner`), PID-reuse validation, and restart lease locks (`vector_reinjection_restart.lock`) ensure zero duplicate daemons or split-brain `system_server` handshakes across repeated soft restarts.
  * Prioritizes Vector bootstrap transactions in `SystemServerService` and extends the Zygisk `system_server` rendezvous window to 20 seconds for slower soft-restart paths.
* **Live `system_server` Re-Attachment After Daemon Restart:**
  * Reconnects an already-injected `system_server` (`BridgeService` + `IVectorDaemon.reconnectSystemModule`) along with loaded modern modules and remote preferences if `vectord` restarts while `system_server` stays alive.
* **Hardened `dex2oat` Wrapper & Recursion Guard:**
  * Dual-namespace (`daemon` + `init` PID 1) mount/unmount recovery up to 32 stacked layers, stock compiler inode validation, PID-scoped wrapper recursion guard (`VECTOR_DEX2OAT_WRAPPER_PID`), optional `dex2oatd` debug binary handling, and failure-only `--inline-max-code-units=0` property fallback.
* **Per-App ART Inline-Hook Compatibility & Stealth:**
  * Central opt-in app selector integrated directly into the Compose Manager (`System status -> ART inline hook compatibility mode`) to invalidate temporary `libart.so` inline hooks immediately after framework bootstrap, paired with a linker version script restricting `libzygisk.so` `.dynsym` exports to Zygisk ABI entrypoints.
* **OEM ARM64 Veneer Unwrapping & Samsung One UI 8.5/9 Shared-UID Hotfix:**
  * Unwraps 16-byte ARM64 OEM `libart.so` hotpatch veneers (`ldr x16/17, pc+8; br x16/17`) before Dobby inline hooking to avoid `system_server` recursion crashes.
  * Bypasses legacy hook wrappers around Samsung's `getApplicationInfoAsUser("android.uid.system")` probe to eliminate post-soft-reboot `VectorLegacyBridge` exception/CPU storms on One UI 8.5/9.
* **System Scope Normalization & Hot-Reload Hygiene:**
  * Normalizes `android` and `system` scope entries so CLI and `libxposed` system-scoped modules reliably dispatch `onSystemServerStarting`, and frees retired API 102 hot-reload DEX mappings and callbacks.

---

### Compatibility & Installation

* **Android Versions:** Android 8.1 (API 27) through Android 17 (API 37).
* **Root & Zygisk:** KernelSU / KernelSU Next (including late-load / jailbreak mode), Magisk, or APatch + [NeoZygisk](https://github.com/JingMatrix/NeoZygisk) or Zygisk Next.

1. Download the latest `Vector-SRX-v*-Release.zip` (or `-Debug.zip` for troubleshooting) from [Releases](https://github.com/HazY-Codes/Vector-SRX/releases).
2. Flash the module in your root manager (KernelSU / Magisk / APatch). Because the module ID remains `zygisk_vector`, it upgrades cleanly in-place over Vector or Vector-SR.
3. Reboot (or soft-reboot under supported KernelSU setups) and open **Vector-SRX** from the status notification,root manager Action button, or dialer code `*#*#832867#*#*`.

---

### Credits & Upstream Lineage

Vector-SRX stands on the shoulders of the open-source Android hooking community:

* [JingMatrix/Vector](https://github.com/JingMatrix/Vector) — Upstream Vector v2.2 framework & Compose Manager.
* [byemaxx/Vector-SR](https://github.com/byemaxx/Vector-SR) — Soft-restart ownership, reinjection lease, `dex2oat` recovery, and ART inline-hook compatibility architecture.
* [peppicry/Vector-SR-S24U-Hotfix](https://github.com/peppicry/Vector-SR-S24U-Hotfix) — Samsung One UI 8.5/9 `android.uid.system` shared-UID legacy-hook hotfix.
* Community PR contributors on `JingMatrix/Vector`: `bartasc4` (#1012, #1013, #1017, #1018), `McTr0` (#1103, #1104), and `XR-stb` (#1008).
* [LSPlant](https://github.com/JingMatrix/LSPlant), [Dobby](https://github.com/JingMatrix/Dobby), [LSPosed](https://github.com/LSPosed/LSPosed), [XposedBridge](https://github.com/rovo89/XposedBridge), [Magisk](https://github.com/topjohnwu/Magisk), and [xz-embedded](https://github.com/tukaani-project/xz-embedded).

---

### License

Vector-SRX is free software licensed under the [GNU General Public License v3.0](./LICENSE) (GPL-3.0). See [NOTICE.md](./NOTICE.md) for modification notices and upstream provenance.
