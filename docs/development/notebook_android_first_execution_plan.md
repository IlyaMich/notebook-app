# Notebook App — Android-First Execution Plan

**Date:** 2026-10-26  
**Status:** Approved sequencing decision; addendum to the original V1 PRD and M0 kickoff  
**Repository:** `IlyaMich/notebook-app`

## Decision

Implement and verify a functional native Android notebook prototype **before** beginning the SwiftUI implementation. Keep the previously agreed two-native-app V1 product scope unless changed explicitly in a later decision. This changes delivery order, not the user-facing V1 requirements or canonical document contract.

The `ios/` directory remains in the repository; renting the Scaleway Mac mini M2 and configuring Xcode are deferred until the Apple workstream resumes.

## Previous progress

- **FND-01 — Done:** private monorepo, collaboration access, scaffold, project documentation, reviewed pull requests.
- **FND-02 — Done with limitation:** contribution guidance, PR and ADR templates, team code-review convention. GitHub Free on a private personal repository does **not** enforce protected `main`; continue voluntary peer review and squash merges.
- **FND-03 / FND-05 — Deferred:** cloud Mac / Xcode and SwiftUI navigation shell. No verified Mac provisioning or Apple build.
- **FND-04 / FND-06 — Next:** Android development environment and Compose navigation shell.
- **FND-07 — Split:** Android build/tests and shared-format validation first; add Apple CI when Xcode and the Swift project are available.
- **FND-08 — Split:** document Android OS/device matrix first; complete Apple minimum OS and physical-iPad test/distribution workflow later.

## Execution sequence

1. **FND-04 (Android):** Install stable Android Studio on Windows and recommended SDK components; verify emulator and/or physical test device. Record versions and device capabilities.
2. **FND-06:** Generate minimal Kotlin/Jetpack Compose project under `android/`; implement navigation placeholders for Library, Notebook Contents, and Note Editor. Verify launch on a tablet emulator and, when available, a phone emulator or physical Android phone.
3. **FND-07A:** Add Android Gradle build/test workflow; integrate available shared `.notebook` schema/fixtures/Python validator with independent CI validation. Do not invent missing source artifacts.
4. **Android M1:** Implement the approved folder → notebook → note → page hierarchy and local Room/SQLite storage in a small tested slice. Keep local DB schema independent from the portable file schema.
5. **Android M2:** Evaluate Android handwriting engine candidates **at this milestone**, implement a basic canonical editable stroke, and validate `create → draw → durable save → exit → relaunch → reopen`. Use real stylus hardware for latency and palm behavior; emulator is not sufficient for ink-quality acceptance.
6. **FND-03 / FND-05 / FND-07B / FND-08 (Apple):** Provision Scaleway Mac, establish signing/test-device workflow, build SwiftUI navigation shell, add Apple CI, and record iPadOS targets.
7. **Cross-platform prototype and subsequent milestones:** Test `.notebook` parsing/export and real ink round trips in both directions before freezing a production document format. Complete remaining planned V1 and release gates on both platforms.

## Guardrails

- Preserve the private GitHub monorepo and independent native app implementations.
- Preserve approved V1 scope: Android tablets/phones and iPadOS; offline-first; full-library backup/restore and editable note import/export.
- Keep `shared/schemas`, `shared/fixtures`, and `shared/validators` platform-independent; the existing V0.2 document model is provisional.
- Do not select the handwriting engine in foundation work. Do not claim cross-platform verification until both platforms have been tested.
- Continue short-lived feature branches, other-developer review, and squash merges; keep secrets and real user notes out of Git.
- Treat original PRD/M0 exit criteria as **full two-platform completion**; an Android-only foundation is an explicitly labeled interim checkpoint, not the completion of original M0.

## Immediate next action

On the designated Windows Android development PC, verify Android Studio installation, Android SDK tools, virtualization/emulator status, and available physical stylus hardware. Report the installed Studio version, detected SDK versions, emulator boot status, and Android device model/OS (if known). Decide minimum Android API after reviewing real device constraints rather than choosing it by assumption.

