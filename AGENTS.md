# Notebook App — Codex / Coding Agent Instructions

## 1. Purpose and authority

This file guides coding agents working in the Notebook App repository: https://github.com/IlyaMich/notebook-app.

The product is an offline-first, editable digital notebook. The current implementation is Android-first (Kotlin and Jetpack Compose); the iPadOS implementation is deferred, not canceled. Never restart or recreate the existing app as a response to an ordinary feature request.

Follow, in order: (1) the user's current explicit request, (2) more-specific instructions in the affected directory, (3) this file, (4) the current code, tests, and repository documentation. If any instruction conflicts with data safety or an established shared format contract, stop and explain the conflict before acting. Treat older planning documents as intentions, not evidence that functionality has been implemented.

## 2. Repository map and source of truth

- `android/`: native Android app; `android/app/` is the Gradle application module `:app`.
- `android/app/src/main/`: Android production code and resources.
- `android/app/src/test/`: Android JVM/unit tests.
- `android/app/src/androidTest/`: on-device instrumentation tests.
- `android/app/schemas/`: exported Room schema snapshots (when applicable).
- `shared/schemas/`, `shared/validators/`, `shared/fixtures/`: cross-platform portable-document contracts and synthetic test data.
- `ios/`: future native Apple implementation; do not modify without a task requesting it.
- `docs/product/`, `docs/architecture/`, `docs/ui-ux/`, `docs/development/`, `docs/decisions/`: requirements, architecture, design, workflow, and ADRs.
- `planning/`: planning/backlog artifacts.
- `.github/workflows/`: CI configuration.

Read `README.md`, `CONTRIBUTING.md`, the relevant issue, affected code/tests, and applicable current architecture documents before making consequential changes. Inspect rather than assume file paths, class names, completed issue status, or behavior. The project README may contain dated status statements; verify current reality from source and Git history.

## 3. Working agreement and change scope

1. Before edits, inspect `git status --short`, current branch, and relevant diff. Do not assume a clean tree.
2. Respect existing staged and unstaged changes, including work by the other developer. Never overwrite, revert, reformat, or stage unrelated files.
3. Keep each change focused on the requested issue and explicit acceptance criteria. Prefer a small patch over a broad refactor.
4. For nontrivial work, first explain the current behavior, affected files, risks, proposed change, and verification plan. For a clearly authorized small fix, implement directly.
5. Do not change dependencies, toolchains, SDK targets, database versions, wire formats, or architecture merely to silence a test. Request explicit approval for material changes.
6. Do not create branches, switch branches, commit, push, open/merge PRs, reset, clean, amend, rebase, or force-push without an explicit user request. Avoid destructive Git operations entirely unless specifically authorized.
7. Keep unrelated warnings and potential improvements in a separate report rather than expanding task scope without permission.
8. Never claim a build, test, emulator run, hardware verification, or code review occurred unless it actually did.

## 4. Android implementation conventions

- Use Kotlin, Jetpack Compose, existing state/view-model/navigation patterns, and the established package namespace `com.notebook.app`.
- Current Gradle configuration on the checked repository branch: `:app`, `minSdk = 26`, `targetSdk = 37`, `compileSdk = 37`, Java 11 source/target, Room and Kotlin serialization. Verify the local branch before relying on version details.
- Prefer existing modules, repositories, interfaces, and dependency injection/construction conventions; do not invent parallel implementations.
- Preserve lifecycle behavior, configuration-change resilience, accessible touch targets, and adaptive tablet/phone layouts.
- Keep heavy I/O, decoding, database transactions, and serialization off the input/rendering-critical path and main thread.
- Treat Jetpack Ink dependencies used in the debug-only evaluation as a probe, not proof that a production handwriting engine has been selected. Do not promote them to release dependencies or replace the canonical stroke model without an explicit architectural decision.
- Respect restricted APIs and lint requirements. Do not suppress lint or loosen CI checks to hide a defect.

## 5. Non-negotiable document/editor invariants

- The canonical note format stores editable typed objects, not flattened images or native-only stroke blobs.
- Pages are ordered, fixed-size, individually addressable, continuously scrolled in the viewport, and may have different dimensions.
- Page geometry is independent of viewport zoom, pan, device pixels, orientation, and Compose layout coordinates. Coordinate conversions must be explicit and testable.
- A visible element belongs to exactly one page. Preserve stable IDs, element ordering, and explicit page ownership according to the applicable command semantics.
- Page clipping applies to both live previews and committed rendering; interactions must not silently transfer content to another page.
- Preserve affine transforms, stroke samples, pressures/timestamps where supported, shape parameters, text metadata, image references, and undoable editor command semantics.
- Partial-stroke erase must not silently turn into whole-stroke erase or lose unaffected geometry. Group logical gestures into appropriate atomic commands.
- Unknown schema versions or unsupported required element types must fail explicitly or use a proven lossless preservation path. Never silently drop or flatten unsupported content.
- Changes affecting portable JSON/ZIP schema, brush compatibility, geometry units, ID behavior, import/export, or cross-platform semantics need corresponding validators/fixtures, tests, and joint design review/ADR where appropriate.

## 6. Local storage, assets, and durability

- Library hierarchy: optional nested folders -> notebooks -> notes -> ordered pages -> editable elements. Root notebooks are allowed; folders cannot directly own notes; folder cycles are prohibited.
- The Android local store uses Room/SQLite plus app-managed asset files. Keep library hierarchy metadata separate from canonical page geometry and portable archives.
- Respect atomic transaction boundaries, foreign-key/reference integrity, stable ordering, revisions, and migration safety. Never use destructive migration fallback for real user data.
- A user-visible 'Saved' state must correspond to a successful durable commit; memory-only state is not a completed save.
- Image/PDF originals are immutable asset-backed data. Validate actual bytes, MIME/signatures, dimensions, hashes, byte lengths, resource budgets, and cancellation boundaries before registration or use.
- Never weaken validation or expand accepted malformed input just to make a failing test pass. Specifically preserve corrupt PNG/JPEG, decompression, over-budget, and malformed archive rejection where applicable.
- Coordinate staged files and database references so a committed note never points to an absent/unverified asset. Cleanup must not delete an asset still referenced by live or trashed content.
- Preserve crash-safe import/export/restore and explicit failure reporting. A failed operation must not silently discard existing notes, assets, or backups.
- Preserve V1 full-library backup **and** restore requirements, including safe import into a populated library without silently overwriting content.
- Treat every imported file/ZIP/PDF/image and external path as untrusted; constrain allocations, recursion, decompression, path traversal, duplicate entries, and format mismatches.

## 7. Testing and verification

Run the narrowest relevant checks first, then broaden according to risk. Do not substitute compilation for runtime behavioral evidence.

From the **`android/`** directory on Windows (PowerShell):

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:lintDebug --console=plain
```

For targeted JVM verification, use the existing `--tests` filter with the fully qualified test class or method when Gradle supports it. For instrumented verification on a connected emulator/device, prefer a targeted class/method before the full suite:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ExampleTest" --console=plain
```

`com.notebook.app.ExampleTest` is a placeholder: discover the actual fully qualified test name first. Emulator must be booted and `adb devices` must show an available device. Verify target API level and report it. Device tests can be expensive and may require explicit selection to avoid running on unintended connected devices.

The current GitHub Android CI runs `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:lintDebug`. Match those checks when appropriate; also run shared-validator commands found in the repository when changing portable schemas or fixtures. Do not guess invocation flags for shared scripts—inspect their usage or CI workflow.

For security-sensitive work (archive parsing, image decoding, asset finalization, recovery, ownership, DB migrations), add positive, negative, boundary, and failure-path tests; do not relax existing security-gate assertions. For editor/input changes, consider gesture cancellation, API 26 compatibility, viewport transforms, lifecycle transitions, and persistence after relaunch.

When a task shows `UP-TO-DATE` or `FROM-CACHE`, distinguish that from a fresh run. Use an appropriate Gradle rerun option if genuinely fresh execution matters; confirm actual option support rather than inventing flags. If a test fails, inspect the assertion, stack trace, report, and logs; classify source regression versus emulator/infrastructure failure. Never repeatedly change production code based on a single flaky signal without investigating it.

If a check cannot run, state exactly why. Explicitly distinguish: compiled, JVM-tested, instrumented on device, manually tested, and **not verified**.

## 8. Android Studio / Codex tool use

- Prefer read-only inspection before execution or editing. For Android Studio, use available IDE project/search/build/logcat/UI tools when useful, and shell/Gradle/ADB as needed.
- Before interacting with an emulator, confirm which device and API are selected. Avoid clearing app data, uninstalling, wiping, rebooting, or changing emulator state without authorization.
- Running commands and editing files may be permitted by the Codex sandbox; do not assume the approval policy forces confirmation for every action. Honor explicit read-only/no-modification tasks.
- Do not bypass sandbox approval, disable safety prompts, or change agent permissions to finish a task.
- Never expose local credentials, auth files, keystores, or sensitive log content in responses.

## 9. GitHub collaboration and pull requests

- `main` is the stable integration branch. Work normally happens on short-lived `feat/`, `fix/`, `docs/`, `test/`, or `chore/` branches.
- Follow `CONTRIBUTING.md` and the PR template. Each PR should be focused, traceable to an issue, and include evidence for relevant tests and manual verification.
- The other developer reviews substantial changes. Do not self-approve or auto-merge. A fresh approval may be needed after substantive review changes.
- Squash merging is the established workflow after successful checks and approval. Never push directly to `main`.
- Schema/contract modifications, architecture changes, persistence migrations, and security-sensitive adjustments require explicit coordination with the collaborator.
- Do not treat locally passing tests as authorization to commit, close issues, or merge.

## 10. Privacy and safe data practices

- Use synthetic test notes, images, PDFs, and fixtures. Never commit real personal notes or sensitive datasets.
- Do not commit API keys, authentication tokens, `.env` secrets, Android keystores, signing certificates, provisioning material, or local IDE/device configuration.
- Keep user-owned notes private; do not upload their contents, logs with personal data, or extracted assets to external services without explicit permission.
- Preserve local-first/offline behavior. Do not add analytics, user accounts, cloud dependencies, or network upload behavior without a product decision.

## 11. Completion and handoff format

At the end of an implementation or review task, report:

1. **Scope:** issue/task and short summary of what changed (or why no changes were made).
2. **Files:** relevant modified/added files, and whether unrelated working-tree changes were preserved.
3. **Verification:** exact commands, device/API where relevant, pass/fail counts or outcomes, and any checks not run.
4. **Risks:** compatibility, reliability, security, or remaining open questions.
5. **Git state:** current branch and whether any files are staged; do not imply a commit or PR exists if it does not.
6. **Next step:** the smallest reviewable follow-up action.

If asked for a read-only investigation, provide findings and a proposed plan without implementation. If a task is partially blocked, preserve existing changes and supply the exact blocker and safe next step rather than bypassing guards.
