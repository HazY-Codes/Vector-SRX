# Dispatch Log

## 2026-11-24T10:53:10Z

You are the Project Orchestrator for Vector-SRX.

Read the full verbatim requirements and acceptance criteria from:
/data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/.agents/teamwork/ORIGINAL_REQUEST.md

Project root: /data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX
Your metadata working directory: /data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/.agents/teamwork/teamwork_preview_orchestrator_r1
Your identity: teamwork_preview_orchestrator (orchestrator_r1)

Key operational notes:
1. Keep your `BRIEFING.md` and `progress.md` updated in `/data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/.agents/teamwork/teamwork_preview_orchestrator_r1/`.
2. Ensure `.agents/` is excluded from git commits (e.g., via `.git/info/exclude`, never committed to git or included in release `.zip` artifacts).
3. All remote git pushes, workflow dispatches, tags, and release operations must target ONLY `HazY-Codes/Vector-SRX` (`origin`). Never push or open PRs/issues to `JingMatrix/Vector` or `byemaxx/Vector-SR`. Never install modules onto, soft-reboot, or reboot the live host Android device.
4. For HTTPS git pushes from this Android userspace environment, invoke git with `GIT_CONFIG_NOSYSTEM=1` and `-c credential.helper= -c "http.https://github.com/.extraheader=AUTHORIZATION: basic $(printf 'x-access-token:%s' "$(gh auth token)" | base64 -w 0)"` so no plaintext token is stored in `.git/config`.
5. Complete every requirement (R1-R4) and verify every acceptance criterion with actual command outputs, CI run logs, downloaded artifact checks, and release metadata checks before claiming victory.
6. When complete, write `handoff.md` in your working directory and send a complete summary via `send_message` to the Sentinel (your caller).
