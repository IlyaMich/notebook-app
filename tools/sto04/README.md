# STO-04: app-private filesystem probe

Optional instrumentation test for `ManagedAssetFileStore`. Run only on the verified
`STO07_S03B_Disposable` Android emulator (`emulator-5556`). The PowerShell runner
checks AVD identity and boot status before each app/test APK install and before
running the instrumented tests. It does not start, wipe, resize, fill, or delete
an emulator, and it does not use `connectedDebugAndroidTest`.

The test creates UUID-named subdirectories beneath `Context.filesDir` and cleans
up those directories. It does not read/write Notebook Room databases or modify
existing `filesDir/assets/` and `filesDir/staging/` directories.

Run from Windows PowerShell after installing the package:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'C:\Proj\notebook-app\tools\sto04\Run-STO04-DeviceProbe.ps1'
```

What it aims to establish on the specific emulator (only after all tests pass):
- `CREATE_NEW` exclusive destination creation and verified/synced copy succeed in app-private storage.
- Verified final content remains readable after the staging file is removed.
- Duplicate imports use a single final content-hash path.
- Intervening conflicting destination files are not overwritten.
- Production `ManagedAssetFileStore` now syncs created parent directory entries and the
  finalized asset's parent after syncing and verifying file bytes.
- `Os.open(... O_RDONLY)` + `Os.fsync()` works for the test directories.
- A simulated final-directory sync failure is covered by the JVM regression tests.

What it **does not** establish:
- Crash/power-loss durability, database references, file parsing or orphan cleanup.
- Support on all Android filesystems/devices (this is one AVD).

Caveat: `CREATE_NEW` makes destination creation exclusive, NOT file contents atomically
visible. A process death during copying can leave an unreferenced partial final file,
which must be detected/cleaned by a future crash-reconciliation step. Do not commit
database references until file sync, validation and directory durability succeed.
