# EDT-05 — Checkpoint 2C: Debug capture → Room save → reopen

## Goal

Demonstrate the **end-to-end, debug-only** path: live Jetpack Ink input →
FMT-04 `sampledPointsV1` canonical points → existing `DocumentRepository.commit`
→ real on-disk Room database → reopen the demo → load canonical samples →
reconstruct a Jetpack Ink dry stroke. This is **not** the production editor.

## Added files

- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkPersistenceDemoActivity.kt`
- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkPersistenceDemoViewModel.kt`
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkPersistenceDemoTest.kt`
- `android/app/src/debug/AndroidManifest.xml` (replaces the prior debug manifest;
  retains `InkProbeActivity` and adds a second launcher activity)

The demo opens a separate private database called `edt05-ink-demo-user.sqlite`.
It **never** uses the production `library.sqlite` database or the production
`AppDatabaseProvider.get()` singleton. Existing `InkProbeActivity` remains
available unchanged, and all new Kotlin production source is under `src/debug/`.

An instrumented test supplies a randomized session key to use a different
private DB and preferences name per test; tests delete only their own DBs.
No Room migration, schema or Gradle dependency changes are involved.

## Behavioral contract

- Each completed native stroke becomes a canonical stroke **before** any write.
- A newly captured stroke is shown immediately but counted as **pending**, not saved.
- Pending strokes are committed serially, one transaction per stroke.
- Only a successful commit marks that stroke as saved and updates the revision.
- A failed commit keeps the unsaved stroke in memory with a visible failure state
  and a **Retry save** button; no false saved acknowledgement.
- **Reload from disk** is disabled during writes or while pending edits exist.
  Reload explicitly closes and reopens the Room database and replaces the
  in-memory display from canonical JSON.
- Finishing the demo activity and launching it again creates a fresh ViewModel
  and reloads the note from the separate private SQLite database.
- Rotation retains the ViewModel and canonical samples; rebuilds are derived
  from current canvas width. The demo uses a fixed A4 595.276-DU width and has no
  production page navigation or full height clipping.
- The demo stores its note/page IDs in a separate debug SharedPreferences file.
  Missing or invalid saved references cause an explicit error, not overwriting
  or discarding a previous document.

## Local verification (Windows PowerShell)

Commit successful CP2B before extracting CP2C:

```powershell
cd C:\Users\ilmih\Desktop\develop\notebook-app

git add android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkRoomPersistenceTest.kt
git add docs/engineering/edt_05_cp2b_room_persistence_verification.md
git diff --cached --check
git diff --cached --stat
git commit -m "test(edt-05): prove Room-backed canonical ink durability"
```

Extract CP2C ZIP into repository root with `-Force` because it **replaces** the
existing `android/app/src/debug/AndroidManifest.xml`. Its existing Ink Probe
activity declaration is retained.

```powershell
cd .\android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkPersistenceDemoTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkRoomPersistenceTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkProbeRecreationTest
.\gradlew.bat :app:installDebug
```

All commands should show `BUILD SUCCESSFUL`. Confirm that the CP2C XML report
contains all **four** test methods and no failure entries (not a zero-tests run).

Launch the new *Notebook Room Ink Demo* via the emulator app drawer, or:

```powershell
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n `
  com.notebook.app/com.notebook.app.ui.editor.ink.InkPersistenceDemoActivity
```

Manual acceptance:

1. The debug Room demo opens. It shows 0 saved strokes and a durable initial
   note revision (`Saved revision 0 (reloaded from disk)`) on first launch.
2. Draw a line near the upper third. It changes from pending/saving to **1 saved
   stroke**, revision 1. More strokes advance the count and revision.
3. Tap **Reload from disk**. All strokes remain visible, status explicitly says
   the document was reloaded from disk, and counts/revision remain unchanged.
4. Exit/finish the demo, relaunch it from the drawer; the strokes are still
   present. Do not merely press Home (which may keep the Activity alive).
5. Rotate and rotate back; the canonical stroke count/revision remain stable,
   and strokes remain proportionally positioned where the surface is visible.
6. The old **Notebook Ink Probe** still starts as an independent in-memory probe.
7. The production Notebook app's library/editor still work and show no demo
   notes: this test must not touch `library.sqlite`.

There is intentionally **no Clear data action** in this checkpoint. If you want
an empty demo for another manual session, use a different emulator/app install
or add a dedicated explicit reset command in a later debug checkpoint. **Do not
use Android's Clear storage button**, since it can delete the normal Notebook
app's production library too.

## Known limits and risks

- The CP1B native adapter currently proves *pen* only. Pencil/highlighter
  mappings and live visual equivalence need further measurements.
- The demo is width-proportional and limited to a single test surface: no
  viewport pan/zoom, cross-page targets, page clipping or full-viewport gesture
  arbitration. Those are separate work.
- Input committed after a complete gesture is durable only after Room returns.
  On process death, not-yet-acknowledged strokes may be lost; this demonstration
  does not replace collaborator STO-06 autosave/error-recovery behavior.
- Debug database and preferences are kept across relaunch deliberately. The
  instrumentation suite always isolates and cleans its own test session data.
- On real emulator/hardware the UI may be showing a pending stroke while the DB
  write is in progress; it must never claim it is already saved.

**Do not mark EDT-05 Done** from CP2C alone. Full viewport integration and
measured native ink fidelity/latency remain required by issue #36.
