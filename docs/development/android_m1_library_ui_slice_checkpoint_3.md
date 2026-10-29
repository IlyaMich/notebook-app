# Android M1 Library UI — Checkpoint 3: Rename and Move

Status: implementation candidate; requires Android device tests and a manual navigation check before commit.

## Scope

This is an incremental UI addition to the branch `feature/android-library-ui-m1` following checkpoints 1 and 2. The existing LIB-03 Room repositories remain the only source of truth. No database schema, DAO, navigation route, or content model changes.

- Rename folders and notebooks in the root and nested folder listings.
- Move folders and notebooks to root or into another active folder.
- Destination selection displays the active folder tree and excludes the moving folder and all descendants to avoid offering invalid cycles.
- Quick Notes can be renamed but has no UI move action. The repository continues to enforce the protected-root constraint.
- Refetch the current listing after successful changes. Errors remain in the dialog without claiming success; a failed destination-list load has a Retry control.
- No-op moves and unchanged names are disabled in the UI. Backend validation is still authoritative, and runs transactionally.
- Preserve IDs, note ownership and document contents. No Trash, note relocation, drag/drop or search added in this checkpoint.

## Source changes

- `android/app/src/main/java/com/notebook/app/ui/screens/LibraryScreen.kt` — management buttons and state, list refresh on success.
- `android/app/src/main/java/com/notebook/app/ui/screens/LibraryManagementDialogs.kt` — rename/move dialogs and destination enumeration.
- `android/app/src/androidTest/java/com/notebook/app/ui/AndroidLibraryManagementUiTest.kt` — four new instrumentation tests.

## Verification gate

With a booted Pixel Tablet AVD (the project's Android API 37 emulator), run from `android/`:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Manual scenario: create `Projects → Research` and root notebook `Planning`. Rename both a folder and notebook, move `Planning` into `Research`, then move `Research` to the library root. Confirm the previous location no longer lists moved items, the new location lists them, and opening a note still shows its persisted page. Force-stop/relaunch and verify names, parent relationships and IDs remain correct. The move picker must not offer the selected folder or its descendants. Verify the Quick Notes notebook cannot be moved.

## Known constraints

- Directory destination enumeration currently loads the active tree into a bounded list (up to 3,000 folders and 100 levels); oversized/invalid trees display an error rather than guessing a destination.
- Refresh is triggered when a successful mutation occurs in the displayed listing. Cross-screen live updates are not part of this checkpoint; navigation away and back reloads the listing.
- Asset-backed editing, deletion/Trash, note moves, search and a production drawing editor are not included.
