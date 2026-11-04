# STO-07 S03-B — disposable-emulator storage recovery

## Scope and evidence

This opt-in Android instrumentation suite tests a real low-storage condition on the production Room/WAL database configuration. No production source files are modified by this change. `Sto07S03bDeviceBaselineTest` creates a durable revision-7 notebook/note/blue-line fixture; `Sto07S03bRealStorageRecoveryTest` uses a separate note to verify that a large autosave reports `FAILED` under low guest storage, retains one pending command, preserves committed revision 7, retries after cleanup to revision 8, and preserves both notes across a new app process.

Observed on 2026-11-03 with API 37 and disposable `STO07_S03B_Disposable` AVD: guest `/data` reduced to about 16 MiB; FAILED (pending 1, revision 7); all 37 dedicated filler files removed; retry and new-process verifications returned `OK (1 test)` each; free space restored to approximately 4.51 GiB. See host execution output in the PR description. Both tests use `Assume.assumeTrue` to skip phases during normal CI. **No CI runner should enable `sto07Phase=pressure`.**

The storage-related error predicate accepts `SQLiteFullException`, `SQLITE_FULL`, `database or disk is full`, *or* the secondary `cannot rollback - no transaction is active` SQLite error. Because the latter can mask the original failure, this device test establishes a real low-storage `FAILED`/rollback/retry outcome but **does not prove the exact underlying SQLite error code**. Follow-up: improve storage-specific error classification/user-facing messages separately if needed.

## Requirements and safety

- Windows PowerShell, local Android SDK, and a repository checkout; default `-RepoRoot` is `C:\Proj\notebook-app`, or pass your own path.
- Branch `feature/sto-07-s03b-device-low-storage` (intentional guard for this experimental driver).
- A **disposable, expendable AVD** named exactly `STO07_S03B_Disposable`, connected as `emulator-5556`, Android booted.
- Host free space at least 20 GiB, guest `/data` initially at least 256 MiB free.
- Never run on the normal `Pixel_Tablet` AVD, a real device, or an AVD with valuable data.
- The pressure script uses dedicated `/data/local/tmp/sto07_s03b_pressure/fill_NNN.bin` files, with a target of ~16 MiB remaining and safety bounds. This may destabilize the disposable AVD. Automatic cleanup executes in a `finally` block; use the dedicated cleanup script if interrupted. The cleanup checks the exact AVD identity and deletes only those named files.
- **Do not repeat the destructive pressure test on the already recovered note.** The fixture deliberately refuses to run again once its recovery marker is set. Use a fresh disposable AVD for a new full reproduction.

## Reproduction (only if deliberately authorized on a fresh disposable AVD)

From repository root, once the emulator is running:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\sto07-s03b\Run-S03B-Baseline.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\sto07-s03b\Preflight-S03B.ps1
# WARNING: intentionally fills /data of disposable AVD; do not run casually.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\sto07-s03b\Run-S03B-RealPressure.ps1
```

If cleanup is needed:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\sto07-s03b\Cleanup-S03B-Pressure.ps1
```

## Non-destructive verification after a successful run

Do **not** reinstall app/test APKs or run `connectedDebugAndroidTest` between the phases of an in-progress pressure test. After the completed run, verification-only phases are safe:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5556 emu avd name  # must output STO07_S03B_Disposable
& $adb -s emulator-5556 shell am force-stop com.notebook.app
& $adb -s emulator-5556 shell am instrument -w -e class 'com.notebook.app.data.local.Sto07S03bDeviceBaselineTest#verifyBaselineAfterNewProcess' -e sto07Phase verify 'com.notebook.app.test/androidx.test.runner.AndroidJUnitRunner'
& $adb -s emulator-5556 shell am instrument -w -e class 'com.notebook.app.data.local.Sto07S03bRealStorageRecoveryTest#verifyRecoveryAfterFreshProcess' -e sto07Phase verifyPressure 'com.notebook.app.test/androidx.test.runner.AndroidJUnitRunner'
```

Each should return `OK (1 test)`. Normal unit and lint checks may be run without touching the AVD:

```powershell
Set-Location android
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:lintDebug --console=plain
```

## Review notes

The existing library move-destination UI test had an intermittent spinner timeout on some local runs, independent of this opt-in fixture. Do not claim the complete instrumentation suite passed unless it is separately verified. S01/S02 process-death tests also skip without their explicit two-phase driver flags.
