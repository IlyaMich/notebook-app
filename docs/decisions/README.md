# Architecture Decision Records (ADRs)

Use an ADR to record decisions that affect long-term architecture, cross-platform semantics, reliability, storage, security, or development workflows.

## Process

1. Copy `0000-template.md` to the next sequential filename, such as `0001-title-of-decision.md`.
2. Mark it `Proposed` while under review.
3. Discuss tradeoffs and alternatives in a pull request with the other developer.
4. Change its status to `Accepted` when both developers agree, or `Rejected` with rationale.
5. If a later choice supersedes it, keep the record and mark it `Superseded by ADR-NNNN`; do not erase history.

Use dates in `YYYY-MM-DD` format. State assumptions and any areas that remain provisional. Do not create an ADR just to document a routine implementation detail.

The first real ADR will be numbered `0001`. `0000-template.md` is a reusable template, **not an accepted decision**.
