# Notebook App - Foundation Sprint Kickoff (M0)

**Baseline:** V1 Product Requirements Document 1.0 · 21 October 2026  
**Scope:** Repository and development environment only. No final handwriting SDK choice at this stage.

## Purpose
Create one buildable starting point for both native platforms, with shared documentation and test automation. Our immediate objective is to reduce setup and collaboration risks before investing in the editor engine.

## Tasks and suggested assignment

| Order | Issue ID | Assignment lane | Result |
|---|---|---|---|
| 1 | FND-01 | Joint | Private monorepo, access, README, base folders |
| 2 | FND-02 | Joint | Branch protections, review workflow and ADR template |
| 3 | FND-03 | Apple primary | Rented cloud Mac + Xcode, simulator and distribution/signing approach |
| 4 | FND-04 | Android primary | Windows Android Studio, SDK and stylus device debugging |
| 5 | FND-05 | Apple primary | Minimal SwiftUI app with Library/Notebook/Editor navigation placeholders |
| 6 | FND-06 | Android primary | Minimal Compose app with corresponding screens |
| 7 | FND-07 | Joint | PR checks for validators and platform builds |
| 8 | FND-08 | Joint | Supported OS versions, actual test devices and iPad testing workflow |

**Parallelization:** After FND-01, Apple and Android toolchain tasks can proceed independently. FND-07 depends on both native app shells; FND-08 depends on both toolchains.

## Work rules
- Commit only synthetic notes/fixtures; do not commit personal notes or signing credentials.
- Every PR needs the other developer's review and evidence of a build/test.
- Share changes to schema/coordinates through an ADR and fixture update.
- Leave PencilKit vs Jetpack Ink vs alternative evaluation for M2, as agreed.

## Acceptance / exit checklist

- [ ] Each developer can clone and create a reviewed pull request.
- [ ] SwiftUI shell builds and launches on a Mac simulator.
- [ ] Kotlin/Compose shell builds and launches from Windows on emulator/device.
- [ ] Library, Notebook, and Editor placeholders can be navigated.
- [ ] Shared schema validation passes in CI and invalid fixtures fail.
- [ ] Device matrix, min OS targets and cloud Mac access/testing limitations are documented.
- [ ] Both developers agree to the document coordinate conventions and backlog workflow.

## First vertical slice after foundation
`Create notebook → create note → add an editable element → commit → exit → relaunch → reopen`.

See `notebook_v1_implementation_backlog.xlsx` for task details and dependencies; use the PRD for functional acceptance and release gates.
