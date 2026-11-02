# EDT-05 CP5A — Canonical fidelity evidence and acceptance boundary

**Status:** Pending local JVM verification and review.  
**Scope:** Jetpack Ink 1.0.0 debug evaluation only. No native engine upgrade, production editor integration, schema change, or Room migration.  
**Related:** EDT-05 issue #36; FMT-04 sampledPointsV1; EDT-03 continuous-page viewport; CP3C native-lifecycle findings; CP4A three-run baseline.

## Why CP5A is intentionally JVM-only

The CP4B1 preview comparison could not reach meaningful device tests: the Android 17 Pixel Tablet emulator became stuck during both streamed and non-streamed APK installation, after instrumentation reported no results. The installed package state is not proof of a Jetpack Ink regression. The developer restored the Gradle dependency to stable `1.0.0`, and confirmed no unintended Gradle changes. Do not delete app data or keep retrying the same stalled install as part of CP5A.

The new test exercises a longer, deterministic path through *existing* application code: viewport pixels -> page-local canonical samples -> viewport pixels at several camera transforms. It complements rather than replaces CP1B/CP3A, FMT-04, CP2A/CP2B, and the Android instrumentation tests.

## Evidence matrix

| Contract / risk | Prior evidence | CP5A evidence | Acceptance boundary |
|---|---|---|---|
| Canonical sample coordinates independent of camera | CP1B, CP3A | Two page shapes and three camera scales, numerical reprojection | Data geometry only; not pixels |
| Brush width remains in DU | CP1B, CP3A | Reprojection width checks at 0.85, 1.4, 2.25 px/DU | Tested pen brush only |
| Relative timestamps preserved | CP1B, FMT-04 | Long 64-sample curve and zoom recapture | No physical timing benchmark |
| Pressure/tilt/orientation optionality | CP1B, CP2A | Mixed-present/absent samples, unchanged values | No stylus sensor fidelity claim |
| Wet/dry geometric handoff | CP3C | Handoff diagnostic on 64 confirmed samples | Input geometry only; not visual mesh/pixel equality |
| Room persistence and reopening | CP2B, CP2C | No new test; retained earlier verification | Android runtime needed for repeat |
| Page gaps, clipping, gesture rejection | CP3B, CP3C | Retained previous regression evidence | No new native input execution |
| Native crash stability | CP3C incident, CP4A three runs | Not addressed by JVM tests | **Open**: intermittent RenderThread SIGSEGV |
| Zoomed-frame performance | CP4A three runs | Not addressed by JVM tests | **Open**: p95 about 51–52 ms for sampled UI frames |
| Actual stylus, palm rejection, pressure dynamics, latency | Not measured on hardware | Not addressed | **Open**: requires physical Android stylus hardware |
| iPad / PencilKit cross-platform editable round trip | FMT-04 defines contract | Not addressed | **Open**: future iOS implementation required |

The 0.5 px handoff value in CP3C is provisional and used only for confirmed geometry diagnostics. It is **not** a user-perceived fidelity threshold or a latency target. Because these JVM tests use double-precision synthetic values, their very small error tolerances must not be carried over unchanged to real native drawing.

## Test instructions (PowerShell)

From the repository root:

```powershell
cd C:\Users\ilmih\Desktop\develop\notebook-app

git status --short --branch

cd .\android
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

This task runs local JVM tests and does not require ADB, APK installation, or instrumentation. Locate `InkFidelityContractRegressionTest` in the test report under `android/app/build/reports/tests/testDebugUnitTest/index.html` (Gradle path relative to repository root). No Kotlin source or Gradle dependency is changed by the CP5A package.

## Manual review checklist (evidence, not new emulator work)

1. Confirm current Gradle Ink version is `1.0.0` and `git diff -- android/app/build.gradle.kts` is empty.
2. Confirm the three new JVM tests pass together with existing debug unit tests.
3. Verify the earlier CP3C and CP4A evidence documents are still tracked; do not duplicate the abandoned CP3C repair notes.
4. Carry forward the unresolved native SIGSEGV and zoomed UI-frame slowdown explicitly.
5. Record CP4B as **deferred / inconclusive due to APK installation stall**, not a preview regression.
6. Confirm the Android editor production path and shared format remain untouched by CP5A.

## CP5A exit criterion

Accept CP5A when local JVM tests pass and the evidence matrix is peer-reviewed as a *debug adapter/data fidelity* assessment. This is **not** an approval to merge Jetpack Ink into the production editor. Physical-device ink quality, long-duration memory/renderer stability, native latency, and iOS cross-platform fidelity remain acceptance blockers for full engine selection.

## Next checkpoint

CP5B: record an engine decision with an explicit scope: Jetpack Ink `1.0.0` is a promising replaceable Android candidate, backed by functional and canonical-contract tests, **not yet fully production-qualified**. Identify further physical-device and comparative profiling as future tasks. Keep the original EDT-05 evaluation PR peer-reviewed and avoid shipping the debug prototype as production code without those gates.
