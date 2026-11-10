---
name: notebook-change-review
description: Perform a read-only, evidence-based review of Notebook Android/shared code changes, a working-tree diff, commit, or pull request. Use when asked for review, pre-commit audit, regression/security assessment, implementation readiness, PR review, or acceptance-check gaps involving Room, asset safety, document model, editor/Jetpack Ink, tests, or Git scope. Do not modify files or approve/merge a PR unless separately requested.
---

# Notebook change review

## Scope and sources

Read repository-root `AGENTS.md`, `CONTRIBUTING.md`, the task/issue acceptance criteria, and `docs/development/android-verification-matrix.md` when assessing tests. Inspect current code and relevant docs; treat dated implementation notes as historical unless the current tree confirms them. Default to **read-only review**: don't modify source/tests, stage, commit, reset, checkout, push, post to GitHub, approve, or merge.

## 1. Establish what is being reviewed

1. Identify repository, current branch/HEAD, baseline (`main` or stated PR base), staged and unstaged changes, and untracked files.
2. Ask for a precise base only if indispensable; otherwise use the user's requested scope or the current feature branch versus its known base and state that assumption.
3. Use `git status --short --branch`, `git diff --stat`, `git diff --check`, `git diff`, `git diff --cached`, and relevant `git log`/`git merge-base` commands as appropriate. Avoid `git diff main` when stale local `main` would misrepresent the PR; inspect refs and note limitations.
4. Read complete relevant changed functions and callers/tests, not only hunks. Distinguish new issues from pre-existing problems and unrelated collaborator work. Don't silently include unrelated changes in a review verdict.
5. Identify issue-level goals, requirements, and acceptance evidence; note when instructions, tests, or the branch differ.

## 2. Review by risk and contract

Use only applicable checks, not boilerplate findings:

- **Document and editor:** canonical editable objects, page-local IDs/ordering, coordinate transform and inverse behavior, clipping, mixed page sizes, erase modes/fragment identity, undo transaction semantics, wet/dry ink consistency, cancellation, gesture routing, lifecycle/reopen persistence.
- **Room and library:** isolation of production DB versus test DB, nested folder cycle guards, non-destructive Trash/purge behavior, revisions and Saved status, migrations/exported schema and authentic fixtures, SQLite transaction boundaries.
- **Assets / STO-08:** stage → validate → hash → finalize → register ordering, immutable finalized files, ownership/reference reconciliation, cancellation and bounded decoding, malformed input fail closed. V1 accepted images: **strictly validated PNG and single-scan baseline JPEG**; progressive or baseline multi-scan JPEG unsupported and rejected. Do not infer this decoder boundary completes image attachment UI/registration.
- **Portable format:** schemaVersion and database migration version are distinct; preserve IDs/transforms/order/asset hashes; reject unsupported types and invalid ZIP paths; require shared fixtures/tests and joint approval for incompatible contract changes.
- **Android and UI:** minSdk 26 compatibility, target/compile SDK confirmed from local build, Compose state/coroutines/threading, restricted APIs/lint, accessibility and adaptive tablet/phone behavior. Jetpack Ink debug probe should not silently become a release requirement.
- **Security / privacy:** files and paths untrusted, bounded resource use, no secret or personal-note leakage, no weakening of negative tests or fail-closed error handling.
- **Change quality:** smallest viable diff, clear layering, no duplicate state/ownership, meaningful errors, needed test coverage, no unrequested dependency/toolchain change.

## 3. Cross-check tests and evidence

Find the relevant tests and their assertions. Separate a test's existence from its successful execution. Examine exact Gradle command, task outcome, device/API, reports and skipped tests. For high-risk code require positive, negative, boundary, and failure/recovery cases. For device-only behavior, do not claim JVM tests or compilation prove it. Check for regression surface and cross-API gaps (especially API 26 versus API 37). Read dedicated runners before recommending storage-pressure or process-death commands; do not execute destructive verification casually.

Review may be done without running tests. If the user explicitly requests execution, follow `notebook-android-verification`, preserve existing state, and identify any actions that affect devices or files. Never represent recommendations as results.

## 4. Findings format

Report **findings first**, ordered by severity and with concrete evidence:

- **Blocker:** likely data loss/corruption, unsafe validation, incompatible migration, false-success durability, or security boundary breach.
- **High:** likely functional regression or unmet acceptance criteria.
- **Medium:** meaningful edge case, untested risk, or maintainability defect with a realistic failure path.
- **Low:** limited-scoped improvement; do not crowd out correctness issues.

For each finding include: severity, precise `path:line` or symbol, observed behavior or reasoning, reproducible scenario, why it violates the contract, and the narrowest suggested remedy/test. Avoid speculative 'could be bad' findings without a realistic scenario. Separate **confirmed defects** from **risks needing verification**.

Then provide:

1. **Review scope** — base/head or staged/unstaged set inspected, any exclusions.
2. **Summary** — readiness assessment, not an approval/merge action.
3. **Verification** — tests actually run with command/result; existing evidence reviewed; tests not run.
4. **Remaining risks / questions** — especially missing device evidence, API 26, recovery or manual stylus checks.
5. **Working tree and next step** — whether this read-only review changed anything (normally no).

If no findings, say `No actionable findings in the reviewed scope`, not `bug-free` or `all tests pass`. Don't close an issue or claim CI/peer-review approval on behalf of another developer.
