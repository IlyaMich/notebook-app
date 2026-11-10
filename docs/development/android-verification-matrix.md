# Notebook Android — Verification Matrix

**Status:** Working verification policy; review against the currently checked-out repository and issue before use.  
**Prepared:** 2026-11-10. **Scope:** Android-first Notebook app, shared portable validators, and CI/device evidence.  
**Related:** repository `AGENTS.md`, `CONTRIBUTING.md`, `.github/workflows/android-ci.yml`, Android Gradle configuration, STO/EDT issue-specific docs and `tools/` runners.

> This document defines **what to check**, not a claim that every test listed has passed on the current branch. Code/test inventory and actual evidence must be confirmed locally. Old docs and conversations may describe historical checkpoints. A green compilation task, Gradle `UP-TO-DATE`, or an emulator-only run is not a substitute for the missing runtime/physical-device gate.

## A. Baseline, environments and evidence labels

- Repository: `https://github.com/IlyaMich/notebook-app`, with `android/` the Gradle root and `:app` the current application module. On the inspected main branch the app uses Kotlin/Jetpack Compose, Room, Kotlin serialization, `minSdk=26`, `compileSdk=37`, `targetSdk=37`, AndroidJUnitRunner, and debug-only Jetpack Ink evaluation dependencies. Confirm the branch before relying on versions.
- Known local development example (not a fixed requirement): Windows Android Studio, API 37 `Pixel_Tablet` AVD, commonly `emulator-5554`. API 26 must be tested separately when required. Another **disposable** `STO07_S03B_Disposable` AVD is used by guarded failure-injection tooling; historically it used `emulator-5556` but serial numbers can change.
- JVM/Gradle tasks may run without an emulator. Instrumentation requires a booted, correctly selected Android device. UI/Logcat tools available in the Codex Android Studio agent do not imply the instrumentation suite has been executed.
- Record each check as: **NOT RUN**, **BLOCKED**, **COMPILED**, **EXECUTED—PASS**, **EXECUTED—FAIL**, **SKIPPED**, or **HISTORICAL EVIDENCE**. Report cache status separately: `FRESH`, `UP-TO-DATE`, `FROM-CACHE`, or `UNKNOWN`.

## B. Routine checks (from `android/`, Windows PowerShell)

| Gate | Command | When to run | What success proves / does not prove |
|---|---|---|---|
| Android build | `.\gradlew.bat :app:assembleDebug --console=plain` | Normal Android code or build changes, PR prep | Debug APK builds; not Android runtime behavior |
| JVM unit suite | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | Almost every Android code change | JVM assertions pass if executed; not emulator behavior |
| Lint | `.\gradlew.bat :app:lintDebug --console=plain` | PR prep, UI/Android API/dependency changes | Static lint passes; not device verification |
| Instrumentation compile | `.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain` | Adding/changing androidTest or verifying test compile | Android test Kotlin compiles; no tests necessarily ran |
| Targeted JVM class | `.\gradlew.bat :app:testDebugUnitTest --tests "fully.qualified.ExistingTestClass" --console=plain` | Focused behavior / regression triage | Selected JVM test(s), if matched and actually executed |
| Targeted instrumentation | See section C | Behavioral API/Room/UI/storage tests | Selected device tests, on identified device/API |
| Shared schema/fixture validators | Inspect `shared/validators/`, CI and `--help` in current checkout | Portable format/ZIP/fixture changes | Tests only the fixtures and checks actually invoked |

The inspected `.github/workflows/android-ci.yml` runs `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` under JDK 17. Inspect other active workflows for shared validation rather than assuming their names. A typical pre-review local sweep uses the same three app tasks. For a fresh JVM run, use Gradle `--rerun-tasks` as appropriate and inspect actual execution (older conversation snippets may abbreviate this as `--rerun`; prefer the supported full option).

## C. Ordinary targeted instrumentation

**Preflight** (read-only):

```powershell
adb devices -l
emulator -list-avds
adb -s emulator-5554 shell getprop ro.build.version.sdk
adb -s emulator-5554 emu avd name
```

**Replace `emulator-5554` with the actually selected serial.** For a physical device, `emu avd name` is inapplicable. When more than one device is connected, verify Gradle's intended device selection rather than assuming `adb -s` constrains `connectedDebugAndroidTest`.

**Run a specific existing test class** (replace placeholder after inspecting source):

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=fully.qualified.ExistingAndroidTest" `
  --console=plain
```

Record Gradle task outcome **plus** test method count, failures/skips, serial, AVD model/name, and `ro.build.version.sdk`. If multiple device test runs are involved, report each separately. An Android Studio shell run is valid; it is not necessarily Android Studio's separate build integration. Use `studio_ui_state`/`studio_read_logcat` to diagnose when available, not as substitutes for running assertions.

## D. Feature / risk matrix

| Area | Minimum automated coverage | Device/API and manual evidence | Blocker or caution |
|---|---|---|---|
| **Build/CI** | Debug assemble, JVM, lint; instrumentation compile if androidTest changed | Build/install/smoke on relevant form factors | Build success ≠ UI correctness |
| **Library & navigation (LIB)** | Folder cycle, Quick Notes restrictions, move/Trash/restore/duplicate/purge, stable note/pages and transaction rollback | Normal API 37 tablet plus compact phone UI where affected; relaunch and navigation checks | Never silently delete descendants/content or violate reference preservation |
| **Room/persistence (STO)** | DAO/repository transactions, revision/Saved semantics, FKs, migration using historical committed schema fixtures, failure and reopen tests | API 37 on-device isolated DB; API 26 for changed platform-sensitive I/O/migrations | No destructive fallback, false Saved, or committed dangling asset reference |
| **Viewport/input/eraser (EDT)** | Page coordinate conversion, zoom/pan invariance, clipped wet/dry rendering, gesture arbitration/cancel/lifecycle, eraser canonical hit-testing and undo/redo | API 37 tablet + phone UI as relevant; API 26 compatibility when affected; physical stylus for palm/pressure | Emulator cannot establish physical stylus or palm-rejection fidelity |
| **Asset/image decoder (STO-08)** | Strict PNG and JPEG validators, bad signatures/chunks/markers, truncated/malformed/multi-scan rejects, bounds/bitmap budget, cancellation, adversarial/duplicate cases | API 37 targeted image/decode device suites; API 26 when implementation depends on API behavior | **V1 accepts strictly validated PNG and single-scan baseline JPEG only.** Progressive and baseline multi-scan JPEG must fail closed. This is a decoder boundary, not proof attachment UX exists |
| **Asset ownership/finalization/recovery** | Staging→validate→hash→finalize→register, immutable originals, leases, inventory, DB reference safety, unreferenced cleanup, rollback/reopen, migration | API 37 on-device isolated DB/files; test cross-process as specifically required; API 26 compatibility as risk dictates | A committed note must not reference absent or unverified bytes |
| **Portable `.notebook` format** | Schema + semantic validation, valid and invalid archives, hashes/sizes, IDs/order/coords, version rejection, corruption/path traversal | Cross-platform round trip only after Apple implementation; Android-only evidence stated as such | No silent flattening/discard, distinct DB vs wire versions |
| **UI/accessibility** | Compose UI semantics/state tests, toolbar and page organizer, save/error status | Tablet/phone windows, rotations, TalkBack where applicable | Distinguish accessible controls from editable ink accessibility |
| **Backup/restore/import/export** | Atomic snapshot/reference consistency, safe archive parsing, collision-safe restore, recovery after interruption | On-device export→import/restore, empty and populated library when functionality exists | Requirement for V1; not automatically complete now |
| **Fault injection/low storage** | Isolated negative and recovery assertions; controlled failure injection | Dedicated disposable AVD only, task-specific opt-in runner | **Never** casually fill device `/data`, wipe valuable AVD, or run pressure in CI |
| **Performance & physical device** | Benchmarks and bounded resource tests with real counts | Real stylus and target tablets/phones; record measured latency/memory | No unmeasured performance or fidelity guarantee |

## E. Discovered examples of test locations and how to verify them

The examples below are **test discovery leads**, not immutable suites or authoritative test counts. Confirm each current class path and methods before calling it.

| Focus | Example existing source or reference | Usage guidance |
|---|---|---|
| Canonical stroke rendering | `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/CanonicalStrokeCanvasRendererTest.kt` | Target class for ink rendering regression when appropriate |
| Library management UI | `android/app/src/androidTest/java/com/notebook/app/ui/AndroidLibraryManagementUiTest.kt` | Check lifecycle, spinner/idling and emulator-specific intermittent behavior |
| Ink gesture arbitration | `com.notebook.app.ui.editor.ink.InkGestureArbitrationActivityTest`, `InkGestureCleanupRegressionTest` in EDT docs | Inspect current tests and later superseding docs before selecting |
| Ink Room persistence | `com.notebook.app.ui.editor.ink.InkRoomPersistenceTest`, `InkDocumentBridgeTest` | Verify assertions correspond to current canonical/Room boundaries |
| Asset ownership | `AssetOwnershipDaoAndroidTest`, `AssetOwnershipTransactionBoundaryAndroidTest`, `RoomAssetOwnerInventoryAndroidTest` | Use isolated databases; do not delete real note data |
| Asset leases / cross-process | `AssetAcquisitionLeaseCoordinatorAndroidTest`, `AssetAcquisitionLeaseCrossProcessAndroidTest` | Distinguish regular device assertions from risky multi-process fixture protocols |
| Read-only finalized verifier | `ReadOnlyFinalizedAssetVerifierAndroidTest` | Preserve immutable original verification and explicit corruption rejection |
| Room V3→V4 migration | `AppDatabaseMigrationV3ToV4Test` | Fixture should be an **authentic historical exported schema**, not synthesized from current code |
| Adversarial finalized decoder | `RegisteredFinalizedImageDecoderAdversarialAndroidTest` (recent project conversation; confirm current checkout) | Report actual class and method counts; don't infer upstream commit from discussion |

> The latest project conversation reported six decoder suites being run on API 37 `STO07_S03B_Disposable`. That is **historical dialogue evidence only**, not a confirmed complete current-run verdict. Another recent project agreement limits accepted JPEGs to single-scan baseline. Do not turn this into an unsupported 'all STO-08 passed' claim.

## F. Specialized device scripts — do not substitute normal test runner

| Runner/document | Preconditions | Do not do |
|---|---|---|
| `tools/sto04/README.md` / guarded STO-04 device probe | Verify dedicated disposable AVD identity, serial and boot | Do not replace with unconstrained connected-device task or touch existing app assets |
| `tools/sto07-s03b/README.md` / low-storage recovery | Fresh, expendable `STO07_S03B_Disposable`, guarded branch, host/guest free-space checks, explicit approval, scripts and cleanup instructions | **Never run on Pixel_Tablet/real device; never enable `sto07Phase=pressure` in CI; never rerun a one-shot pressure fixture on recovered note** |
| Multi-stage process death / recovery | Read exact issue/tool README and follow its install/force-stop sequence | Do not reinstall APK or run `connectedDebugAndroidTest` mid-sequence if docs forbid it |

Dedicated runners can be intentionally brittle: don't bypass guards to make them launch. If serial, branch, AVD identity, or remaining capacity differs, **stop** and report. Pressure/cleanup commands deliberately mutate guest filesystem, so run only on explicit authorization.

## G. Coverage selection by change type

**Small JVM-only logic change:** targeted JVM class → full JVM suite → build/lint if affected. Device checks only when behavior depends on Android runtime.

**Compose/ink UI change:** JVM + compile instrumentation → selected Android UI suite on identified API 37 → manual tablet/phone gesture check → API 26 if platform interaction is affected → physical stylus as needed.

**Room/schema change:** JVM + build/lint → isolated DB instrumentation including migration/rollback → rerun on API 26 if relevant → review generated `android/app/schemas/` schema and historical fixtures → relaunch/reopen evidence.

**STO-08 decoder/asset change:** positive/negative/boundary/cancel/adversarial JVM → instrumentation on API 37 (and API 26 compatibility if required) → reference/ownership/finalization/recovery tests → inspect memory/bitmap limits, resource leaks, and behavior after interruption → attachment UX/persistence separately when implemented.

**Shared format/export change:** validator tests for valid and invalid fixtures + semantic checks → app integration as appropriate → cross-platform evidence only if both platforms exist and ran.

**Pre-PR gate:** `assembleDebug`, full JVM, `lintDebug`, compile androidTest if impacted; targeted instrumented suites; task-specific manual evidence; `git diff --check`, scope and secret review; peer approval/CI per `CONTRIBUTING.md`.

## H. Failure triage and test evidence

1. Record exact command, working directory, environment and stdout/stderr; inspect HTML/XML under `android/app/build/reports/tests/` or `android/app/build/outputs/androidTest-results/` as applicable. Paths and filenames depend on AGP version; locate them instead of assuming a specific filename.
2. Separate `compile`/dependency configuration errors, assertion failures, Android API incompatibility, test harness/idling problems, emulator disconnected/unbooted state, device storage exhaustion, and flaky behavior. Keep the original evidence before reruns.
3. For assertions, inspect test and production boundary; reproduce focused class/method once before broad fixes. Don't relax validation, disable tests, add sleeps or change acceptable inputs merely to make a failure disappear.
4. Where a test is skipped by `Assume`/phase flag, report **SKIPPED** even if Gradle is green. For `UP-TO-DATE`, note cached result and only force rerun if fresh evidence is needed.
5. If risk is high and a required target wasn't run (e.g., API 26, physical stylus, process-death sequence), mark the gate **NOT RUN** / **BLOCKED**, not passed.

## I. Copyable verification record

```text
Task / issue:
Branch / HEAD:
Scope and changed files:
Working tree before / after:
Host / Android Studio / JDK (if relevant):

Gate | Exact command (cwd) | Result | Cache status | Device/API | Tests pass/fail/skip | Evidence location
-----|---------------------|--------|--------------|------------|----------------------|------------------
...  | ...                 | ...    | ...          | ...        | ...                  | ...

Manual UI/stylus checks (device, steps, outcome):
API 26 compatibility status:
Asset/Room/format negative-path evidence:
Not run / skipped / blocked and why:
Regressions or unresolved risks:
Approval/PR/CI state (do not infer):
Next smallest safe step:
```

## J. Updating this matrix

Change this document when a verification procedure or acceptance boundary changes, not merely because a test file is renamed. Confirm new suite names/counts in source, reflect special runner guards, and distinguish actual evidence from expected coverage. Document consequential cross-platform or security acceptance changes in the appropriate ADR and obtain joint review. Keep test output and run-specific dates in PR evidence; do not accumulate a misleading permanent 'everything passed' checklist here.
