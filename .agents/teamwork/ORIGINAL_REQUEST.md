# Original User Request

## 2026-11-24T10:52:39Z

Audit, harden, verify, and ship **Vector-SRX** (`https://github.com/HazY-Codes/Vector-SRX`) — a production-grade Android 8.1–17 Xposed / `libxposed` API 102 Zygisk framework fork built on the latest upstream `JingMatrix/Vector` v2.2 codebase (including the Jetpack Compose Manager) with the soft-reboot, reinjection, `dex2oat`, per-app ART inline-hook compatibility, OEM ARM64 veneer, and Samsung One UI 8.5/9 fixes from `byemaxx/Vector-SR` and community patches.

Working directory: /data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX
Integrity mode: development

## Verification & Reference Resources

- **Target Repository (`origin`):** `https://github.com/HazY-Codes/Vector-SRX` (authenticated via `gh` CLI; initial GitHub Actions run `#45375977969` failed during `Build with Gradle` and needs root-cause diagnosis and resolution).
- **Read-Only Upstream Remotes (already fetched in working directory):**
  - `upstream`: `https://github.com/JingMatrix/Vector.git` (baseline `6f6b976e` / `v2.2`)
  - `vector-sr`: `https://github.com/byemaxx/Vector-SR.git` (`master` and `merge_upstream_manager`)
- **Existing In-Repo Verification Harnesses:**
  - `tests/reattach/run_tests.py` (`system_server` daemon-restart re-attachment suite)
  - `tests/hotload/run_tests.py` (`libxposed` API 102 hot-reload resource & callback cleanup suite)
  - `manager/tools/check_translations.py` (resource & localization integrity checker for `manager/src/main/res` and `daemon/src/main/res`)
- **Host Environment Notes:** Native Android ARM64 Linux userspace (`$PREFIX=/data/user/0/com.agy/files/usr`, OpenJDK in `$PREFIX/lib/jvm/`, `python3`, `clang`/`g++`, `git`, `gh`). Release signing secrets (`KEY_STORE`, `KEY_STORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`) are already configured on `HazY-Codes/Vector-SRX` GitHub Actions (local backup at `/data/user/0/com.agy/files/.config/vector-srx/`). Note that for HTTPS git pushes from this Android userspace environment, invoke git with `GIT_CONFIG_NOSYSTEM=1` and `-c credential.helper= -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $(printf 'x-access-token:%s' "$(gh auth token)" | base64 -w 0)"` so no plaintext token is stored in `.git/config`.

## Requirements

### R1. Soft-Reboot, Reinjection & Compatibility Audit and Hardening
Perform a thorough adversarial audit across all integrated soft-reboot, late-load, and compatibility changes against both `upstream/master` and `vector-sr` to eliminate any subtle edge-case bugs, race conditions, ABI/JNI mismatches, R8 shrinking hazards, SELinux/mount-namespace leaks, or UI/IPC discrepancies. Specifically ensure:
- KernelSU emulated soft-reboot cleanup (`emulated-soft-reboot.sh`, `post-fs-data.sh`, `service.sh`) and private mount-namespace `/data/resource-cache` shadowing (`VECTOR_SHADOW_OK`) never leak mounts into `init`'s namespace or break boot on BusyBox or Toybox environments.
- Surviving-daemon reinjection ownership & restart lease locking, live `system_server` re-attachment after daemon restart, dual-namespace `dex2oat` mount recovery + recursion guard + failure-only property fallback, `android`/`system` scope normalization, per-app ART inline-hook invalidation (daemon, native cleanup, `.dynsym` hiding, and Compose Manager UI), ARM64 OEM veneer unwrapping, and the Samsung One UI `android.uid.system` shared-UID bypass are complete, thread-safe, and free of regressions.
- Module ID remains `id=zygisk_vector` for seamless in-place upgrades over Vector and Vector-SR, while display branding (`Vector-SRX`), repository links (`HazY-Codes/Vector-SRX`), and GPL-3.0 attribution (`LICENSE`, `NOTICE.md`, `README.md`) are accurate and consistent.

### R2. Automated Host Regression & Static Verification Suite
Ensure all existing host regression suites run and pass locally, and add or expand automated host-executable tests to cover the newly integrated soft-reboot script/mount cleanup logic, ART inline-hook process policy, native truncated-log bounds, and Samsung shared-UID legacy-hook filtering so core invariants are verified programmatically without requiring a physical device reboot.

### R3. Multi-ABI CI Build, Artifact Verification & Tagged `v2.2.1` Production Release
Diagnose and fix all build, formatting (`ktfmt`), lint, R8, CMake/NDK, or workflow issues on `HazY-Codes/Vector-SRX` until the GitHub Actions `Core` workflow succeeds cleanly across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). Once `master` builds green and artifacts are verified, synchronize `zygisk/update.json` and `zygisk/changelog.md` with the exact release version/code and publish a tagged **`v2.2.1`** stable GitHub Release (`--latest`) on `HazY-Codes/Vector-SRX`.

### R4. Controlled GitHub & Host Device Boundaries
All remote git pushes, workflow dispatches, tags, and release operations must target **only** `HazY-Codes/Vector-SRX`. Never push branches, tags, PRs, or issues to `JingMatrix/Vector`, `byemaxx/Vector-SR`, or any third-party repository. Do not install modules onto, soft-reboot, or reboot the live host Android device.

## Acceptance Criteria

### Host Regression & Static Checks
- [ ] `python3 manager/tools/check_translations.py manager/src/main/res` and `python3 manager/tools/check_translations.py daemon/src/main/res` both exit `0` with `problems: 0`.
- [ ] Existing host test suites (`tests/reattach/run_tests.py` and `tests/hotload/run_tests.py`) execute cleanly on the host and exit `0` with zero failed or skipped test cases.
- [ ] Automated host regression tests covering soft-reboot daemon/compiler-mount cleanup (`emulated-soft-reboot.sh` / `service.sh` shadow gating), `InlineHookProcessPolicy`, native log truncation null-termination, and the `android.uid.system` legacy-hook guard execute and exit `0`.
- [ ] `git diff --check` and repository hygiene checks pass with zero whitespace errors, zero untracked build/temp bloat committed, and complete GPL-3.0 attribution in `LICENSE` and `NOTICE.md`.

### Multi-ABI GitHub Actions Build & Artifact Integrity
- [ ] GitHub Actions `Core` workflow on `HazY-Codes/Vector-SRX` (`master`) completes with `conclusion: success`, producing both `Vector-SRX-v*-Release.zip` and `Vector-SRX-v*-Debug.zip` plus R8 mappings and native debug symbols.
- [ ] Downloaded `Vector-SRX-v2.2.1-*-Release.zip` contains `module.prop` (`id=zygisk_vector`, `name=Vector-SRX`), `customize.sh`, `service.sh`, `post-fs-data.sh`, `emulated-soft-reboot.sh`, `daemon`, `daemon.apk`, `manager.apk`, `framework/vector.dex`, and all 4 ABI binaries (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) for `libzygisk.so`, `dex2oat`, and `liboat_hook.so`, and every `.sha256` file inside the archive verifies cleanly against its payload.
- [ ] `libzygisk.so` across built ABIs exports no internal Dobby/LSPlant symbols in `.dynsym` beyond the Zygisk ABI entrypoints, and `manager.apk` is signed with the configured `CN=Vector-SRX` release certificate matching the `SignInfo` embedded in `daemon.apk`.

### Stable `v2.2.1` Release & Update Channel Consistency
- [ ] Tagged GitHub Release `v2.2.1` on `HazY-Codes/Vector-SRX` is published as `isLatest: true` (`isPrerelease: false`) with both signed `Release` and `Debug` `.zip` assets attached and publicly downloadable (HTTP 200/302).
- [ ] `zygisk/update.json` on `master` matches the published `v2.2.1` tag's exact `version` (`"v2.2.1"`), `versionCode`, and `zipUrl`, verified by fetching or validating against the live GitHub Release asset metadata.
