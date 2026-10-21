# Notebook App

An offline-first, native digital notebook application for **iPadOS** and **Android tablets and phones**. iPad and Apple Pencil are the primary product focus. The two applications share a portable, editable document format but have independent native implementations.

> **Status:** Milestone M0 - Project Setup & Foundation. This repository is a starting point, not a functioning application yet.

## V1 product boundaries

- **Apple:** Swift / SwiftUI, with UIKit where necessary; developed with Xcode on a rented Mac.
- **Android:** Kotlin / Jetpack Compose; developed with Android Studio on Windows.
- **Documents:** platform-independent, versioned `.notebook` packages containing ordered pages and editable elements. The document schema is **provisional** until cross-platform testing.
- **Persistence:** offline-first SQLite plus managed image/PDF assets (implementation planned for a later milestone).
- **Reliability:** accurate save status; portable editable-note import/export and full-library backup **and restore** in V1.
- **Out of V1:** user accounts, a backend, cloud synchronization, audio recording, handwriting recognition/OCR, and real-time collaboration.

The handwriting engine is **not selected**. Evaluation of PencilKit, Jetpack Ink, and alternatives is deferred to **M2**.

## Repository layout

```text
ios/                 Native Apple application (to be created during M0)
android/             Native Android application (to be created during M0)
shared/
  schemas/           Shared portable document schemas
  fixtures/          Synthetic compatibility and validation examples
  validators/        Cross-platform, platform-independent format checks
docs/
  product/           Product requirements
  architecture/      Architecture and local data design
  ui-ux/             UI/UX specifications
  development/       Setup, workflow, and testing guides
  decisions/         Architecture decision records
planning/            Milestone plans and backlog exports
scripts/             Development utilities
.github/
  workflows/         CI workflows (to be added during M0)
  ISSUE_TEMPLATE/    Issue templates (to be added during M0)
```

Directories with only a `.gitkeep` file are **placeholders**, not implemented modules.

## Getting started

### Clone (Windows PowerShell)

```powershell
git clone https://github.com/IlyaMich/notebook-app.git
cd notebook-app
```

Development requirements and installation steps for Xcode and Android Studio will be documented separately as part of M0. No native app is buildable at this stage.

## Collaboration conventions

- `main` is the stable branch.
- Use short-lived feature branches and pull requests; prefer squash merging.
- Each developer should review the other's changes.
- CI and repository rules will be configured in FND-02 and FND-07; they are **not yet active**.
- Changes to shared schemas or coordinate conventions require joint review and appropriate fixture updates.
- Use synthetic documents only in tests. Never commit personal notes, private signing keys, credentials, tokens, or environment secrets.

## Source of truth

The approved V1 PRD and companion planning documents are the product/architecture baseline. They will be added to the appropriate `docs/` and `planning/` folders in a separate, reviewed step. The versioned shared format schema, golden fixtures, and validator will also be imported when those source artifacts are available. Do **not** assume their absence in this initial scaffold means their requirements are optional.

## Foundation milestone (M0)

M0 is complete only after both developers can work through reviewed pull requests, native placeholder apps build and navigate on their target platforms, shared format validation runs in CI, and supported OS/device/testing targets are recorded. Handwriting-engine implementation is **not** an M0 requirement.
