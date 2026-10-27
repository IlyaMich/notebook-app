# Notebook App — Android-first backlog priority addendum

**Decision date:** 2026-10-26  
**Applies to:** FND-01 through FND-08 and the start of Android M1/M2  
**Nature of change:** Delivery order and task decomposition only; **not** a replacement for the V1 PRD, original sprint kickoff, or original full backlog spreadsheet.

## Completed and verified by the team

| ID | State | Evidence |
| --- | --- | --- |
| FND-01 | Done | Private monorepo, documented structure, both developers' Git access, reviewed/merged PRs |
| FND-02 | Done with limitation | Contribution guidelines and PR/ADR templates merged; private `main` protection not enforced under GitHub Free |

## Execution queue — Android first

"P0" here means high execution priority and/or a prerequisite; it does **not** change the PRD's V1 release-blocker definitions. "Next" is relative order, not a claim that work has started.

| Order | ID | Lane | Execution priority / state | Depends on | Definition of done |
| --- | --- | --- | --- | --- | --- |
| 1 | **FND-04** | Android | **P0 — Start now; not yet verified** | FND-01 | Stable Android Studio and SDK installed on Windows; emulator boots and test-device access recorded |
| Parallel | **FND-08A** (split from FND-08) | Android + joint | **P0 — During setup** | FND-04 device inventory | Android minimum OS choice, emulator/device matrix, and stylus hardware details recorded; decision supported by actual available devices |
| 2 | **FND-06** | Android | **P0 — Next after environment** | FND-04; incorporate FND-08A minimum SDK decision | Compose app builds and runs; placeholder Library → Notebook Contents → Note Editor navigation works in appropriate layouts |
| 3 | **FND-07A** (Android/shared part of FND-07) | Joint, Android lead | **P0 — Next after shell** | FND-06; shared validator artifacts when available | GitHub Actions Android build/test check runs on PR; independently validate supplied shared schema and golden fixtures; include rejection tests when source assets available |
| After Android foundation | **Android M1** | Android | Upcoming milestone, not started | FND-06/FND-07A | Persistent folder/notebook/note hierarchy and first saved empty note via Room/SQLite |
| After Android M1 | **Android M2** | Android | Upcoming milestone, not started | Android M1 | Evaluate Android ink candidates at M2; create, draw an editable stroke, durably save, exit, relaunch, reopen on Android |

**Shared-format dependency:** The original `notebook_schema_v1_draft.json`, golden `.notebook` example, and Python validator are not among the five uploaded project documents. Obtain the actual artifacts from the team before claiming schema validation is integrated. Do not synthesize production fixtures as substitutes.

## Deferred Apple work — retained in scope

| ID | Lane | Execution phase | Depends on / exit evidence |
| --- | --- | --- | --- |
| **FND-03** | Apple | **Deferred** until Android useful prototype | Provision selected Scaleway Mac mini M2; verify macOS/Xcode, remote access, simulator, signing/distribution path |
| **FND-05** | Apple | **Deferred** | FND-03; create and run SwiftUI Library/Notebook/Editor shell |
| **FND-07B** (Apple part of FND-07) | Joint, Apple lead | **Deferred** | FND-05; Apple build/test GitHub Actions with appropriate runner/signing strategy |
| **FND-08B** (Apple part of FND-08) | Joint | **Deferred** | FND-03; supported iPadOS target, physical iPad test matrix, deployment/signing workflow documented |

FND-07 and FND-08 remain **open as full cross-platform parent tasks** until both A (Android) and B (Apple) parts satisfy the original exit conditions. Do **not** mark original M0 complete after only Android CI and navigation are working.

## Issue tracking guidance

Use [GitHub issue alignment instructions](github_issue_alignment.md) to map or create tasks without overriding existing issue bodies or release priority semantics. Execution phase labels are separate from P0/P1 product-release classifications.

## Invariants (not changed)

- Two native applications remain in V1 scope; `ios/` stays in the monorepo.
- Portable `.notebook` documents remain platform-independent, provisional, and editable on both target platforms in the eventual V1.
- Full-library backup and restore stay in V1, with safe import/recovery behavior.
- Handwriting-engine selection still belongs to M2, not foundation setup.
- Original PRD, document model, library-storage plan, and UI/UX specification remain as approved; this file records scheduling only.
