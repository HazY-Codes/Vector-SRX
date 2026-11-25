# Dispatch — teamwork_preview_explorer_survey_3

- Role: Survey Explorer 3 (R2 Host Regression & Static Verification Audit)
- Working directory: /data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/.agents/teamwork/teamwork_preview_explorer_survey_3
- Project root: /data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX
- Assigned: 2026-11-24T10:53:30Z

## 2026-11-24T10:53:53Z
Survey 3 — R2 Host Regression & Static Verification Audit:
1. Inventory all existing tests and verification scripts under `tests/`, `manager/tools/`, `daemon/`, `native/`, `legacy/`, `services/`, `zygisk/`.
2. Run existing host checks read-only to establish current baseline:
   - `python3 manager/tools/check_translations.py manager/src/main/res`
   - `python3 manager/tools/check_translations.py daemon/src/main/res`
   - `python3 tests/reattach/run_tests.py`
   - `python3 tests/hotload/run_tests.py`
   - `git diff --check`
3. Inspect target code for new/expanded R2 host regression tests:
   - Soft-reboot daemon/compiler-mount cleanup (`emulated-soft-reboot.sh` / `service.sh` / `post-fs-data.sh` shadow gating `VECTOR_SHADOW_OK`, BusyBox/Toybox edge cases)
   - `InlineHookProcessPolicy` (daemon/Java/Kotlin logic and native counterpart)
   - Native log truncation null-termination
   - Samsung `android.uid.system` legacy-hook guard
4. Check available host toolchain (`python3`, `clang`/`g++`, `javac`/`java`, `sh`/`bash`, `busybox`/`toybox`, etc.).
5. Write `handoff.md` and send summary via `send_message` to `278c704a-a2fe-4d36-b113-35cebb7f4c28`.
