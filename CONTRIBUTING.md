# Contributing to Notebook App

This repository contains two independent native applications (`ios/` and `android/`) and shared cross-platform document-format assets (`shared/`). All changes to `main` use reviewed pull requests.

## Local workflow

1. Update your local `main` from `origin/main` (`git switch main` and `git pull --ff-only origin main`).
2. Create a short-lived branch named `feat/<topic>`, `fix/<topic>`, `docs/<topic>`, `test/<topic>`, or `chore/<topic>`.
3. Make one focused change, with tests or verification instructions. Do not implement unrelated work in the same PR.
4. Inspect the files to be committed (`git diff --cached`) and ensure no private data or secrets are staged.
5. Push the branch and open a PR targeting `main`, using the PR template.
6. Request review from the **other developer**. Resolve review comments and get approval **after the final substantive change**.
7. Merge using **Squash and merge** after review and any currently configured checks pass. Delete the remote branch.
8. Pull updated `main` before starting the next task.

Do not push directly to `main`, even if the current GitHub plan does not enforce branch protection. We will configure required automated checks in FND-07; until then, provide manual build/test evidence in each PR.

## Commit and PR naming

Prefer short, descriptive titles:

- `feat: add library navigation shell`
- `fix: correct note list selection`
- `docs: document coordinate assumptions`
- `chore: configure repository conventions`

Include the task identifier (for example, `FND-05`) in the PR description. Use GitHub Issues as the backlog source once issues have been created.

## Review checklist

The reviewer checks: scope and architectural fit, new or changed behavior, evidence of verification, data/format compatibility where relevant, accessibility considerations for UI changes, and absence of secrets or personal data.

The author must not approve their own PR. Review changes on GitHub and use **Request changes** when corrections are required. A fresh approval is expected after meaningful code changes.

## Architecture decisions and shared formats

Record consequential technical choices as Architecture Decision Records (ADRs) under `docs/decisions/`, using `docs/decisions/0000-template.md`. ADRs describe both the decision and its tradeoffs.

Changes to portable document schemas, geometry conventions, asset references, or cross-platform behavior must be jointly reviewed. Once shared validators/fixtures are incorporated, update the relevant fixtures and tests alongside format changes. The draft format is not yet frozen; do not silently treat it as a production contract.

Do not select or implement the production handwriting engine during M0. Engine evaluation belongs to M2.

## Security and data hygiene

Never commit access tokens, signing keys/certificates, keystore files, `.env` secrets, provisioning profiles, private documents, or actual user notes. Use synthetic documents for tests. Keep platform-specific build output and local IDE state out of version control.

## Definition of a merge-ready change

- Focused scope, traceable to a task or issue.
- Correct file layout and clear documentation when behavior changes.
- Appropriate local tests or an explanation of why testing is not yet available.
- Reviewed and approved by the other developer.
- Passing required GitHub checks, when configured.
- No secrets, credentials, or personal notes in the diff.
