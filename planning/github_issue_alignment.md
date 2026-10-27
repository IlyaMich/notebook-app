# GitHub Issues — Android-first alignment instructions

**Repository:** `IlyaMich/notebook-app`  
**Date:** 2026-10-26

The team has confirmed FND-01 and FND-02; no issue numbers or full existing GitHub Issue contents were available during preparation. This guide deliberately does **not** guess issue numbers, replace existing descriptions, or mark unverified setup tasks Done.

## Issue execution labels

These describe **sequence** rather than product priority; preserve existing P0/P1 labels if used in your backlog.

- `execution:now` — actively selected for the next work session (FND-04, FND-08A).
- `execution:next` — begin after prerequisites (FND-06, FND-07A).
- `execution:deferred` — retained but intentionally postponed (FND-03, FND-05, FND-07B, FND-08B).

## Issue titles and outcomes

| ID | Suggested title | Execution label | Dependency / acceptance |
| --- | --- | --- | --- |
| FND-04 | `FND-04: Set up Windows Android development environment` | `execution:now` | Verify stable Studio, SDK, emulator and device access |
| FND-08A | `FND-08A: Document Android OS targets and test devices` | `execution:now` | Choose supported API based on test hardware; record emulator and stylus hardware |
| FND-06 | `FND-06: Build Compose Library/Notebook/Editor navigation shell` | `execution:next` | After FND-04; builds and navigates on Android emulator/device |
| FND-07A | `FND-07A: Add Android build/test and shared-format CI` | `execution:next` | After FND-06; validator portion waits for actual shared artifacts |
| FND-03 | `FND-03: Provision Scaleway cloud Mac and Xcode` | `execution:deferred` | Resume after Android prototype checkpoint |
| FND-05 | `FND-05: Build SwiftUI navigation shell` | `execution:deferred` | After FND-03 |
| FND-07B | `FND-07B: Add Apple build/test CI` | `execution:deferred` | After FND-05 |
| FND-08B | `FND-08B: Document iPadOS targets and testing workflow` | `execution:deferred` | After FND-03 |

If existing Issues track FND-07 and FND-08 as combined work, retain them as umbrella issues and explain the A/B split; do not close them after Android-only work. If a matching Issue already exists, update its execution label rather than creating a duplicate.

## Recommended way to apply

### Option A: GitHub website (no additional tool installation)

1. Open <https://github.com/IlyaMich/notebook-app/issues>.
2. Review existing Issues for the eight IDs above.
3. Create the three execution-phase labels in **Issues → Labels** if absent.
4. Apply the labels to matching Issues. Create missing Issues using the table above and the detailed acceptance criteria in `android_first_backlog_priorities.md`.
5. Link or mention the backlog addendum in relevant Issue descriptions. Do not remove existing requirements or product priority labels.

### Option B: GitHub CLI with a review-first PowerShell script

If GitHub CLI (`gh`) is installed and authenticated, from your Windows repository root run:

```powershell
gh auth status
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\align_android_first_issues.ps1
```

The script's **default is a dry run**: it prints the changes that would be made but does not modify GitHub. Inspect the preview and only then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\align_android_first_issues.ps1 -Apply
```

This script creates missing FND Issues with acceptance text, labels existing matching Issues without replacing their descriptions, leaves closed Issues closed, and does not close or rewrite existing FND-07/FND-08 umbrella issues. Verify the final Issue list in GitHub.

Official GitHub CLI docs: <https://cli.github.com/manual/gh_issue_list>, <https://cli.github.com/manual/gh_issue_create>, and <https://cli.github.com/manual/gh_issue_edit>.
