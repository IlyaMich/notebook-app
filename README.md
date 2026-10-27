# Notebook App

An offline-first, native digital notebook application for **Android tablets and phones** and **iPadOS**. iPad and Apple Pencil remain the primary product focus in the approved V1 specification, but **implementation is Android-first**: we will build and validate a useful Android prototype before starting SwiftUI development.

> **Status (2026-10-26):** FND-01 (repository setup) and the contribution-workflow portion of FND-02 are complete. Android development environment setup (FND-04) is next. No native app build or handwriting engine has been verified yet. Apple foundation tasks are deferred, not canceled.

## V1 product boundaries (unchanged)

- **Android:** Kotlin / Jetpack Compose, developed with Android Studio on Windows. Android tablets and phones are included.
- **Apple:** Swift / SwiftUI, with UIKit where necessary. The SwiftUI workstream is deferred until the Android prototype milestone; a Scaleway Mac mini M2 has been selected for later development but has **not** been verified or provisioned as part of this project.
- **Documents:** platform-independent, versioned `.notebook` packages containing ordered pages and editable elements. The V0.2 document model and proposed wire schema are **provisional** until actual cross-platform tests pass.
- **Persistence:** offline-first SQLite plus app-managed image/PDF assets, with Room planned for Android and GRDB planned for Apple.
- **Reliability:** save status must reflect durable commits; V1 includes editable note import/export and full-library **backup and restore**.
- **Out of V1:** user accounts, backend/cloud synchronization, audio recording, handwriting recognition/OCR, and real-time collaboration.

The handwriting engine is **not selected**. Candidate evaluation remains deferred until **M2** (Android engine evaluation first; Apple when its workstream resumes). Android-only validation must not be described as evidence of cross-platform interoperability.

## Repository layout

```text
ios/                 Native Apple application (deferred, directory retained)
android/             Native Android application (current implementation lane)
shared/
  schemas/           Platform-independent portable document schemas
  fixtures/          Synthetic compatibility and validation examples
  validators/        Platform-independent format checks
docs/
  product/           Approved product requirements
  architecture/      Document and local storage designs
  ui-ux/             Approved UI/UX specifications
  development/       Foundation kickoff and Android-first execution plan
  decisions/         Architecture decision records
planning/            Backlog priorities and future backlog exports
scripts/             Development utilities
.github/
  workflows/         CI workflows (Android first, Apple later)
  ISSUE_TEMPLATE/    GitHub issue templates (as added)
```

Directories containing only `.gitkeep` are placeholders, not implemented modules. Keep native code in its platform directory and shared format/test contracts under `shared/`.

## Getting started (Windows)

```powershell
git clone https://github.com/IlyaMich/notebook-app.git
cd notebook-app
```

The first development task is **FND-04**: set up Android Studio and the Android SDK on Windows, then verify an emulator and available physical test hardware. Next, **FND-06** creates a minimal Jetpack Compose application with Library, Notebook Contents, and Note Editor navigation placeholders. **FND-07A** adds Android build/test CI and shared format validation when the original schema/validator artifacts have been provided.

We have **not** claimed Android Studio installation, a working native app, CI execution, or a physical-device drawing test at this stage.

## Collaboration conventions

- `main` is the stable branch. Use short-lived branches, peer-reviewed pull requests, and squash merges.
- The contribution workflow and PR/ADR templates were added in **FND-02**; see [CONTRIBUTING.md](CONTRIBUTING.md).
- The repository uses **GitHub Free** under a private personal account. Peer review is a team rule; private-repository branch protection is **not technically enforced** on this plan. Do not push directly to `main`.
- Automated checks have not yet been integrated; Android CI is planned under **FND-07A** and Apple CI under **FND-07B**.
- Shared schemas, coordinate conventions, and import/export behavior require joint review and appropriate compatibility fixtures.
- Commit synthetic documents only. Never commit personal notes, credentials, signing material, tokens, or private keys.

## Source of truth and revised work order

The approved product and architecture documents are already committed under `docs/`:

- [V1 PRD and Delivery Plan](docs/product/notebook_v1_prd_and_delivery_plan.md)
- [Document Model Specification V0.2](docs/architecture/notebook_document_model_spec_v0_2.md)
- [Library and Local Storage Plan V0.1](docs/architecture/notebook_library_storage_plan_v0_1.md)
- [UI/UX Design System V0.3](docs/ui-ux/notebook_ui_ux_design_system_v0_3.md)
- [Original Foundation Sprint Kickoff](docs/development/notebook_foundation_sprint_kickoff.md)

These original specifications remain unchanged. The [Android-first execution plan](docs/development/notebook_android_first_execution_plan_2026-10-26.md) records the **new implementation order**, and the [backlog priority addendum](planning/android_first_backlog_priorities.md) translates it into actionable work. Neither document cancels the original two-platform V1 release requirements.

## Foundation milestone (M0)

**Completed:** FND-01 repository/collaboration access and FND-02 contribution workflow (with unenforced GitHub Free protection).

**Current Android-first checkpoint:** FND-04 Android toolchain and emulator/device access → FND-06 working Compose navigation shell → FND-07A Android CI/shared checks, with the Android device matrix and OS target recorded alongside setup (FND-08A).

**Deferred Apple checkpoint:** FND-03 Scaleway/Xcode setup → FND-05 SwiftUI navigation shell → FND-07B Apple CI and FND-08B iPadOS/device testing workflow.

An Android-only foundation is an **interim checkpoint**, not completion of the original full M0 milestone. Cross-platform `.notebook` round trips and native ink compatibility remain open until both implementations can be tested. The first useful Android prototype target is **create notebook → create note → draw an editable stroke → durable save → exit → relaunch → reopen**, with handwriting-engine evaluation beginning only in M2.
