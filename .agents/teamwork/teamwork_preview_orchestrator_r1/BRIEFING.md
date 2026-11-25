# BRIEFING — 2026-11-24T10:53:10Z

## Mission
Audit, harden, verify, fix CI, and publish the `v2.2.1` production release of Vector-SRX on `HazY-Codes/Vector-SRX`.

## 🔒 My Identity
- Archetype: teamwork_preview_orchestrator
- Roles: orchestrator, user_liaison, human_reporter, successor
- Working directory: /data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/.agents/teamwork/teamwork_preview_orchestrator_r1
- Original parent: Sentinel
- Original parent conversation ID: 497b6a1c-72f4-4275-9bb9-f27b5a11137f

## 🔒 My Workflow
- **Pattern**: Project
- **Scope document**: /data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/PROJECT.md (to be created after Survey)
1. **Decompose**: Survey with 3 Explorers in parallel (codebase/remotes/scripts, CI/build/workflow failure #45375977969, and tests/translations/licensing), merge into `PROJECT.md § Feature Inventory`, then decompose into milestones.
2. **Dispatch & Execute**: Delegate or run Explorer → Worker → Reviewer → Challenger → Auditor → Gate loop per milestone (plus Dual Track E2E/regression testing).
3. **On failure**: Retry → Replace → Skip (except Auditor, non-skippable) → Redistribute → Redesign.
4. **Succession**: Self-succeed at 16 spawns (when all non-sub-orchestrator subagents complete).
- **Work items**:
  1. Survey & Project Decomposition [in-progress]
  2. Milestones & E2E/Host Test Track [pending]
  3. Final CI Verification, Artifact Audit & v2.2.1 Release [pending]
- **Current phase**: 0 (Survey)
- **Current focus**: Survey Vector-SRX codebase, CI run #45375977969, upstream/vector-sr diffs, and host test suites.

## 🔒 Key Constraints
- DISPATCH-ONLY orchestrator: never write source code, run commands, or explore code directly; always delegate via `invoke_subagent`.
- Never reuse a subagent after it has delivered its handoff — always spawn fresh.
- Target ONLY `HazY-Codes/Vector-SRX` (`origin`); never push/PR/issue to `JingMatrix/Vector` or `byemaxx/Vector-SR`.
- Never install modules onto, soft-reboot, or reboot the live host Android device.
- Ensure `.agents/` is excluded via `.git/info/exclude` and never committed or packed into release `.zip` artifacts.
- Forensic Auditor (`teamwork_preview_auditor`) is non-skippable and has binary veto.

## Current Parent
- Conversation ID: 497b6a1c-72f4-4275-9bb9-f27b5a11137f
- Updated: 2026-11-24T10:53:10Z

## Key Decisions Made
- Phase 0 Survey (3 Explorers) completed. Synthesized findings into `.agents/teamwork/PROJECT.md` with Milestones M1–M4.
- `teamwork_preview_orchestrator` is not an invocable subagent type in this environment; running the Project Pattern Iteration Loop directly from top-level orchestrator.
- Dispatching `worker_m1` (M1 R1 Hardening & Repo Hygiene) and `worker_m2` (M2 R3 CI & CMake Fix) in parallel with strict disjoint write ownership.

## Team Roster
| Agent | Type | Work Item | Status | Conv ID |
|-------|------|-----------|--------|---------|
| teamwork_preview_explorer_survey_1 | teamwork_preview_explorer | R1 Codebase & Remote Diff Audit | completed | 4d071e14-8704-4660-9766-8810b7793fb9 |
| teamwork_preview_explorer_survey_2 | teamwork_preview_explorer | R3 CI #45375977969 & Repo Hygiene Audit | completed | 8455a470-93b8-4871-8263-b9c4633c898e |
| teamwork_preview_explorer_survey_3 | teamwork_preview_explorer | R2 Host Regression & Toolchain Audit | completed | edda2054-c44d-4dc3-af0b-a8a914ca8b96 |
| worker_m1 | teamwork_preview_worker | M1 R1 Hardening & Repo Hygiene | in-progress | 0a59666e-6703-44d4-b97e-8ff96b24f810 |
| worker_m2 | teamwork_preview_worker | M2 R3 CI & CMake Build Fix | in-progress | b6b77cb8-ad45-4edc-88b3-263d703bcebc |

## Succession Status
- Succession required: no
- Spawn count: 5 / 16
- Pending subagents: 0a59666e-6703-44d4-b97e-8ff96b24f810, b6b77cb8-ad45-4edc-88b3-263d703bcebc
- Predecessor: none
- Successor: not yet spawned

## Active Timers
- Heartbeat cron: 278c704a-a2fe-4d36-b113-35cebb7f4c28/task-9
- Safety timer: none
- On succession: kill all timers before spawning successor
- On context truncation: run `manage_task(Action="list")` — re-create if missing

## Artifact Index
- `/data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/.agents/teamwork/ORIGINAL_REQUEST.md` — Verbatim user requirements and acceptance criteria
- `/data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/.agents/teamwork/teamwork_preview_orchestrator_r1/DISPATCH.md` — Dispatch log
- `/data/user/0/com.agy/files/.gemini/antigravity-app/scratch/Vector-SRX/.agents/teamwork/teamwork_preview_orchestrator_r1/progress.md` — Liveness & step checklist
