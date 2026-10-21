# Notebook App — Documentation Index

These files are the planning baseline for the native iPadOS and Android Notebook App.
They describe intended V1 behavior and architecture; **they do not certify that any
application feature has been implemented or tested**.

## Project references

| Area | Document |
| --- | --- |
| Product scope and delivery milestones | [V1 PRD and Delivery Plan](product/notebook_v1_prd_and_delivery_plan.md) |
| Portable document model (provisional) | [Document Model V0.2](architecture/notebook_document_model_spec_v0_2.md) |
| Library hierarchy and local storage | [Library Storage Plan V0.1](architecture/notebook_library_storage_plan_v0_1.md) |
| UI/UX decisions and interactions | [UI/UX Design System V0.3](ui-ux/notebook_ui_ux_design_system_v0_3.md) |
| Initial engineering tasks | [M0 Foundation Sprint Kickoff](development/notebook_foundation_sprint_kickoff.md) |

## How to interpret this baseline

- The V1 PRD consolidates later product decisions. When an older design document
  calls a feature "proposed" but the PRD records a later explicit approval,
  follow the approved decision and preserve the older document as historical context.
- V1 includes editable individual-note import/export **and full-library backup and restore**.
- The editor's drawing toolbar is **fixed at the top** in V1.
- The V0.2 portable document model and `schemaVersion: 1` are **drafts** until
  cross-platform validation. Do not treat them as a frozen shipping format.
- Do **not** select or implement the production handwriting engine in M0;
  its evaluation belongs to M2.
- Platform implementations are independent: SwiftUI on Apple and Jetpack Compose
  on Android. The shared contract resides in `shared/` once schema assets and
  validators are imported and verified.

## Future additions

As they are reviewed, add architecture decision records to `docs/decisions/`
and setup/development procedures to `docs/development/`. Keep reusable schemas,
format fixtures, and validators under `shared/`, not in `docs/`.
