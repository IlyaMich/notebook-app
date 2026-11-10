---
name: notebook-android-verification
description: Verify or troubleshoot Notebook Android changes, including Gradle build/JVM/lint checks, targeted instrumentation suites, emulator and API 26/37 compatibility, Room/persistence/asset reliability, Jetpack Ink/editor gestures, and device evidence. Use for test plans, regression gates, failing tests, release verification, and evidence summaries; not as permission to perform destructive emulator or Git operations.
---

# Notebook Android verification

## Contract

Follow repository-root `AGENTS.md` and read `docs/development/android-verification-matrix.md` before selecting commands. Use the **checked-out code and current issue's acceptance criteria** as the authority for existing test classes and expected counts. Historical documentation and conversations are leads, not proof of current coverage or passing results. Never alter production code simply to get a green test.

## 1. Preflight — read only

1. Locate repository root and Android project (`android/settings.gradle.kts`, `android/gradlew.bat`). Do not assume the developer's machine uses a fixed absolute path.
2. Inspect `git status --short --branch`, `git diff --name-only`, `git diff --cached --name-only`, and the task's acceptance criteria. Identify overlapping work by another developer; leave all unrelated files alone.
3. Inspect changed files and tests under `android/app/src/test/` and `android/app/src/androidTest/`; search tests by behavior and verify exact fully qualified class/method names before using filters.
4. Classify the requested gate: compile, JVM, lint, ordinary instrumentation, manual UI, isolated storage/recovery, cross-process/process-death, pressure/fault injection, shared portable-format validation, or physical-stylus testing.
5. Report proposed actions and any risk to emulator/app data before running them. A request to **inspect or plan only** is not authorization to execute tests.

## 2. Run the cheapest safe checks first

From the `android/` directory in Windows PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:lintDebug --console=plain
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain
```

Pick the narrowest useful subset first; use all four when warranted by modified code/risk. A targeted JVM class is run as:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "fully.qualified.ExistingTestClass" --console=plain
```

Only when fresh execution is important, consider Gradle's **`--rerun-tasks`** (supported Gradle option) and confirm the requested test task actually executed. Never describe `UP-TO-DATE` or `FROM-CACHE` as a fresh run. If Gradle reports no matching tests, verify class name/source set and test task before calling it a pass.

When `shared/` validators, fixtures, archives, or schemas change, discover the exact Python invocation in the current repository's CI/docs or script help and run applicable positive **and** invalid fixtures. Do not assume an older command remains valid.

## 3. Device verification: identify, isolate, then execute

1. Discover connected devices with `adb devices -l` and available AVDs with `emulator -list-avds`; use Android Studio device inspection where available.
2. For the chosen serial, inspect `adb -s <serial> shell getprop ro.build.version.sdk` and (for emulators) `adb -s <serial> emu avd name`. Do not assume `emulator-5554` or `emulator-5556` always maps to the same AVD.
3. If multiple devices are connected, **do not run** unqualified `connectedDebugAndroidTest` unless the intended target selection is unambiguous. Use a verified single-device state or a purpose-built, documented targeted runner. `ANDROID_SERIAL`/ADB selection alone may not constrain Gradle's connected-device task; verify actual selection.
4. Normal targeted Android instrumentation (from `android/`):

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=fully.qualified.ExistingAndroidTest" `
  --console=plain
```

The quoted fully qualified class is a **placeholder**: replace after source inspection. Confirm that the test actually ran and was not skipped. Method selectors must follow Android instrumentation runner syntax if supported by the suite.

5. `connectedDebugAndroidTest` can install packages, reset test process state, and interfere with multi-phase experiments. Never run it during an in-progress process-death, filesystem ownership, storage-pressure, or recovery sequence that forbids reinstalling test APKs. Read the relevant dedicated `tools/.../README.md` and script guards first.
6. Use Android Studio `studio_ui_state` and `studio_read_logcat` (if exposed) to investigate failures. Note exact serial/API, app/process state, test method, timestamps, and filtered errors. Avoid posting personal note content or tokens.
7. API 26 is the minimum supported Android API in the inspected Gradle configuration; API 37 is the currently configured target/test platform. **Running on API 37 does not verify API 26.** Where the change touches platform-specific APIs, schedule an API 26 runtime gate on a separate suitable emulator/device. Do not claim it passed until executed.

## 4. High-risk gates require special handling

- **Asset and image validation (STO-08):** test malformed signatures, invalid image structure, limits, cancellation, path/hash/byte-length checks, original asset lifecycle, and fail-closed behavior. The agreed V1 acceptance boundary is **strictly validated PNG and single-scan baseline JPEG only**; progressive and baseline multi-scan JPEG are unsupported and must fail closed. This decision does **not** imply image attachment UX and durable registration are already shipped. Verify both when in scope.
- **Room/storage:** test isolated database, migrations using authentic historical fixtures, foreign keys, revisions, rollback, asset references, crash/retry/reopen. Never use destructive migration or accidentally open/delete the production DB in a test.
- **EDT ink/eraser/viewport:** cover canonical page coordinates, transforms, clipping, gesture ownership/cancel/recreation, eraser fragment semantics, undo, and persistence. Visual/manual inspection complements tests; an emulator does not prove physical stylus/palm rejection.
- **Pressure/fault-injection:** `tools/sto07-s03b/README.md` describes an opt-in test that deliberately fills a **disposable** AVD. NEVER run the pressure scripts on the normal Pixel_Tablet, an AVD containing valuable work, or a real device; do not enable the `sto07Phase=pressure` path in CI. Require explicit user authorization, verified exact AVD identity/serial, free-space preflight, a fresh baseline, and cleanup/recovery plan. When uncertain, report the risk and stop.
- **Dedicated runners:** `tools/sto04/README.md` and other task-specific tooling may intentionally bypass the ordinary Gradle connected-device task. Follow each runner's guardrails instead of substituting a convenient command.

## 5. Evidence and interpretation

For each gate, distinguish **configured**, **compiled**, **ran successfully**, **passed/failed**, **skipped**, **not run**, and **blocked**. Compilation proves tests build, not that assertions passed; a past successful run is historical, not current-branch evidence. Check Gradle's test XML/HTML or instrumentation output when counts matter. Record an observed assertion failure separately from flaky emulation, unsupported API/environment, or test harness failure.

Use the matrix's report template. Include current commit/branch, worktree state, command and cwd, exit code, test count if observed, cache/rerun status, target device/API for instrumentation, and logs/artifact locations. Explicitly identify unverified behavior and avoid a blanket 'all tests passed' if any relevant suite was not executed.

## 6. Handoff

Summarize verified invariants, failures, missing device gates, whether production code was touched, and the next smallest safe step. Never commit, push, edit test expectations, wipe data, or change emulator identity without separate explicit authorization.
