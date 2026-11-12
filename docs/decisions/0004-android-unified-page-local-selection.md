# ADR-0003: Android unified page-local selection and mixed-object transform

- **Status:** Proposed — pending joint developer review; no EDT-14 implementation authorization
- **Date:** 2026-11-11
- **Decision makers:** Apple and Android developers (both approvals required)
- **Related tasks / issues:** EDT-14 [#45](https://github.com/IlyaMich/notebook-app/issues/45); predecessors EDT-07, EDT-12 [#43](https://github.com/IlyaMich/notebook-app/issues/43); interfaces with STO-08 [#31](https://github.com/IlyaMich/notebook-app/issues/31), CNT-02 [#53](https://github.com/IlyaMich/notebook-app/issues/53), CNT-04 [#55](https://github.com/IlyaMich/notebook-app/issues/55), EDT-18 [#49](https://github.com/IlyaMich/notebook-app/issues/49)
- **Checkpoint:** EDT-14 C0 — design proposal only
- **Inspected implementation baseline (both Codex assessments):** `main` / `feature/android-unified-selection` at `4ad1f1727255cc6feb2b0470e558ede46a8e6b89` (EDT-12 PR #130). The STO-08 collaborator's checkout was `main` at the same commit, with four **untracked, research-only** files. Reconfirm current refs before implementation.
- **Approval record:** **None for this ADR.** Prior approvals of ADR-0002 and selected STO-08 design directions remain in force only within their own scopes.

## Context

EDT-14's issue acceptance criterion is: **“Lasso selects ink, shapes, text, and images on one page; mixed move/scale is one operation.”** The V0.2 document model already requires page-local mixed selection by intersection, retention of editable canonical elements, and a logical atomic/undoable operation. The portable schema is a draft rather than a frozen interoperability guarantee.

The merged EDT-12 implementation supplies shape-only selection/editing, painted-geometry hit testing, page containment, cancellation, and guarded undo/redo. Ink and shape rendering already share persisted element order and page clipping. `DocumentRepository.commitIfRevision` and `RoomDocumentRepository` own the note revision and transactional command boundary; `NoteAutosaveCoordinator` owns one confirmed-commit history sequence. None of this constitutes a completed unified lasso.

Canonical text and image envelopes exist, but production text/image rendering and creation UX are not complete. `CNT-02` and `CNT-04` depend on EDT-14, so EDT-14 must prove selection/transform with **actual supported canonical text** and **actual verified, registered image elements** via synthetic/approved test setup; placeholders or bounding-box-only simulations are insufficient for acceptance.

STO-08 has merged Room v4 typed asset ownership and a narrowly guarded **insert-only** image writer. The `element_asset_references` table projects image `elementId → assetId`; canonical JSON remains authoritative. Generic `ReplaceElements` refuses image edits; image removal, transfer, media-aware purge, cleanup and UI attachment activation remain restricted. A geometry-only retained-image transform is structurally compatible with the unchanged reference, but has **no existing approved writer**. STO-08 is currently researching export snapshots; its untracked B1 test files/document and provisional quotas are not production interfaces or evidence of executed tests. No STO-08 B2/B3 execution is authorized by those research documents.

### Sources / authority

- [Document model V0.2](../architecture/notebook_document_model_spec_v0_2.md), particularly page ownership, affine convention, unified lasso, undo, and selection/transform sections.
- [Portable draft schema](../../shared/schemas/notebook_schema_v1_draft.json), including `stroke`, `shape`, `text`, and `image` envelopes.
- [ADR-0002](0002-android-semantic-basic-shape-tools.md): accepted EDT-12 shape interaction and page-boundary behavior.
- [STO-08 C2A ownership contract](../architecture/sto_08_c2a_transactional_ownership_writer_contract_v0_1.md), [C2C-2 attachment/publication protocol](../architecture/sto_08_c2c2_verified_attachment_publication_protocol_v0_1.md), [C2C-3A insert-only writer](../architecture/sto_08_c2c3a_insert_only_image_writer_contract_v0_1.md), and [C3-A1 export-protection design](../architecture/sto_08_c3a1_export_protection_design_v0_1.md). Their approvals are limited to the scopes stated in those documents.
- [Android verification matrix](../development/android-verification-matrix.md) and [contribution rules](../../CONTRIBUTING.md).
- EDT-14 C0 read-only Codex assessment and the collaborator's STO-08 read-only integration assessment supplied to the joint reviewers on 2026-11-11. These reports are **evidence of inspected source and proposals**, not joint approvals or runtime verification.

## Decision (proposed; every D/INT entry pending joint approval)

### D1 — Page-local lasso membership and tap targeting

A completed freehand lasso is a transient, implicitly closed polygon in **one owning page's document units**. Select eligible elements whose **visible painted geometry intersects** the polygon; mere bounding-box overlap does not establish membership for ink/open shapes. Stroke hit geometry must match the current renderer's effective stroke footprint, not an assumed future variable-pressure footprint. For text and images use the **transformed visible frame** as the selection region, clipped to the owning page. Use the **even-odd** rule for self-intersecting lasso interiors. Edge contact counts as intersection, subject to robust numerical tolerances specified and tested before C1.

Exclude locked elements, page backgrounds/templates, PDF backgrounds, unavailable/unsupported elements, and content with no visible intersection. For fully transparent supported elements, **proposed default: exclude from painted-geometry lasso**, but preserve them unchanged in storage; define distinct frame-based selection semantics if accessibility or image/text editing requires them. No silent partial selection of an unavailable mixed group.

Tap selection chooses the topmost eligible visible object in reverse canonical render order; overlapping handles take precedence over object bodies with deterministic distance/handle-order tie breaks. Reuse EDT-12 hit tolerances where applicable. Tiny lasso motions do not accidentally form a selection polygon; they may be interpreted as taps only when the tool's tap policy is explicitly met. **Open for review:** exact tap/lasso slop and numerical boundary tolerances; propose from existing EDT-12 4 dp initiation and 8 dp hit tolerances without treating those numbers as final lasso thresholds.

### D2 — Transient selection state and identity

Selection is **ephemeral**, not a portable document field or Room entity. It contains one active page, stable `(pageId, elementId)` references, canonical render ordering, a captured note revision/snapshot identity, and any drag baseline. Never silently retarget a stale ID to a different element or page. Revalidate against current stored/confirmed content on reload, page change, configuration recreation, external edit, and lifecycle restoration; clear or reject stale members. Configuration-change retention is permissible only if every member can be revalidated; otherwise clear selection. No cross-page lasso or implicit transfer.

### D3 — Affine group movement and proportional scaling

Use one page-document-space group transform `G`, applied to each original element affine `T_before` by **left composition** `T_after = G × T_before` under the existing column-vector `[a,b,c,d,tx,ty]` convention. Movement is translation; group scaling is **positive, uniform** scaling about a deterministic group anchor/handle, based on the union of **painted visible bounds**, with explicit handling of degenerate/tiny bounds. Do not normalize existing rotations, shears or reflections.

All transient previews are recomputed from the **frozen original transforms** and original selection snapshot—never from the preceding preview. Validate finite coefficients, invertibility, resulting painted bounds, float-renderability and numeric overflow. Invalid/degenerate candidates do not corrupt source geometry. **Open for review:** minimum/maximum scale and near-singularity thresholds, grounded in current geometry tests and device behavior rather than arbitrary schema limits.

For text, affine scaling changes the **visual frame and glyphs together**; preserve frame and font-size/style payload fields rather than applying a second text-size scale. This is group scaling, **not CNT-02 text reflow**. For images, preserve source frame, optional crop, opacity and asset reference; scale their displayed appearance through the affine transform only. Retained rendering must apply complete transforms and page clipping consistently.

### D4 — Page boundaries, clipping and transient preview

A gesture captures page ownership, viewport/camera/layout generation, relevant note revision, pointer owner and starting canonical snapshot. Page exit or viewport exit **cancels** an unfinished gesture, as in ADR-0002; do not reassign to an adjacent page. A fully contained group may commit only a candidate whose full painted footprint stays within that page. While the pointer remains valid, show the **last valid** contained preview, but release must meet the completion policy. For preexisting partly clipped members, never normalize, shrink, or rewrite them upon selection. **Proposed conservative rule:** allow a commit when all members become fully contained; otherwise reject (and retain the untouched originals). Review whether this needs a less restrictive movement rule for legacy clipped documents before acceptance.

During preview, substitute the transformed member render commands **in their original canonical positions**. Do not lift selected objects into a separate always-on-top layer; alpha blending and overlap ordering must remain correct. Page clipping applies equally to retained paint, selection outlines, handles and hit tests.

### D5 — Input arbitration, cancellation and history admission

Extend the existing coordinated page-tool pointer ownership path; do not add a rival pointer input detector. Lasso, ink, eraser, shape-edit and viewport navigation remain mutually exclusive owners. Preserve stylus priority, eraser-button routing, palm/finger arbitration and second-finger takeover; a two-finger viewport pinch must not accidentally scale objects. Cancel unfinished selection/transform on consumed/platform-cancelled release, pause/disposal, tool/settings change, camera/layout change, selected-page disappearance, or relevant content/revision change; fresh pointer down is required after cancellation.

Selection alone and invalid/no-op previews never create a revision/history entry. A completed transform is submitted only against a confirmed, matching state with coordinator admission rules. **One confirmed changed gesture = one guarded durable edit = one chronological session-history entry.** Undo/redo is an exact guarded inverse/forward of the same approved transform operation; it must not skip incompatible top history entries, invent IDs, change selection membership invisibly or replay uncertain commits. Save status follows durable confirmation; a commit with an unknown outcome requires reconciliation rather than blind retry. General history/clipboard expansion stays in EDT-18.

### INT-01 — Narrow, unified transform command (STO-08 integration)

**Propose a dedicated, restricted multi-element affine-transform operation** through `RoomDocumentRepository`'s existing transaction owner and the existing `DocumentRepository.commitIfRevision` path (or a tightly scoped extension if implementation review proves necessary). Inputs identify one note/page, expected note revision, a bounded set of distinct element IDs, and **expected old and intended new six-value affine transforms**. Compare the expected complete current canonical representation or equivalent exact identity guard at transaction time to prevent same-revision/incorrect-baseline substitution. Neither the command nor callers may accept free-form replacement image JSON, untrusted asset paths or new media payloads.

The command must be accepted only through the guarded revision route; unguarded `commit` rejects it. Generic `ReplaceElements` continues to reject image content. Do not split a mixed gesture into separate image and non-image commits, and do not add a second independent transaction/undo service.

### INT-02 — Exact media ownership and non-geometry preservation

For every selected image, the old canonical element's type/kind/ID, `payload.assetId`, owning page, locked state, other envelope fields, frame, crop presence/value, opacity and render order must match the persisted row and remain **unchanged** after a transform; only the top-level six-value affine transform may differ. Verify exactly one matching `element_asset_references(elementId, assetId)` edge and compatible finalized metadata; missing/phantom/duplicate/inconsistent legacy media is rejected without repair. Preserve the edge byte-for-byte; do **not** delete/reinsert/CAS-change it for a geometry edit. Do not write asset metadata, files or gate state.

For other element types preserve stroke samples/brush data, shape parameters, text payload/style and all non-transform fields. Reject locked, unsupported or inconsistent members. Validate every selected row before any update and ensure a failed member causes rollback of the **whole** mixed command, including revisions. Require checks at the persisted transaction boundary, not only in the UI adapter.

### INT-03 — Transaction, revision, no-op and failure semantics

The existing Room owner performs one transaction: check active note/notebook and page, exact caller revision, all old rows, type/lock/ownership consistency, bounded cardinality, transforms and proposed group containment; then update all members and advance the note revision **once** for a changed command. Atomic rollback on any failure, cancellation, SQL error or stale data. No-op with identical transforms returns the current revision and creates **no** history entry. Distinguish a definitely rejected command from an unconfirmed commit outcome; neither is reported as Saved. An undo/redo must use the same restricted command and revalidate the prior/new transforms, images and note revision.

### INT-04 — Retained-image verification and lease policy (**open; no approval implied**)

Two legitimate candidates require joint STO-08 security review:

- **A — Database-only geometry verification (preferred *proposal*):** because no new media owner is attached and the existing typed edge is unchanged, check canonical image JSON, existing edge, finalized asset metadata identity and gate diagnostic inside the Room transaction; do not reopen or decode unchanged originals. This reduces lock contention and avoids image decoding on an ordinary editor transform. Explicitly analyze whether the proposed metadata/gate checks remain safe for `UNVERIFIED` legacy indexes and future GC.
- **B — Acquire and reverify asset bytes:** if STO-08 requires current-byte integrity or an explicit exclusion boundary even for retained-reference geometry edits, acquire the existing cross-process asset acquisition lease **before** opening Room; verify the original under bounded PNG/single-scan baseline JPEG rules outside Room, recheck immutable metadata/edge/revision inside Room, and hold the lease through commit/rollback. Never acquire a lease inside `withTransaction`, reacquire a non-reentrant lease or call a registrar inside it.

**Neither alternative is approved by this ADR draft.** Final approval must state the exact gate-state policy (`UNVERIFIED`, `UNRESOLVED`, missing/`UNSAFE`), verified metadata fields, required byte checks (if any), lock contention and cancellation behavior, and behavior with multiple image members. Regardless of approach, corruption or inconsistency is never silently repaired, zero edges never imply cleanup permission, and this operation must not introduce another durable asset owner or weaken C2C-3C insertion checks. A future export must snapshot a coherent old **or** new transform with the matching revision/asset set; concurrency with future purge/GC stays subject to STO-08's separate synchronization design.

### INT-05 — One chronological undoable operation

The coordinator records exact before/after canonical envelopes (or a lossless transform-specific inverse preserving the original bytes and non-transform fields), stable member IDs and render order, but admits history only after a confirmed transaction. Undo and redo each perform one equivalent restricted, revision-guarded mixed transform with unchanged image edge/payload. Do not silently replay unconfirmed mutations or bypass top-entry chronology with shape/whole-stroke/partial-stroke eraser entries.

### INT-06 — Protected scope and ownership

No general image replacement, asset substitution, image deletion, element transfer, attachment UI activation, PDF background edits, owner-edge repair, legacy certification/backfill, GC, original-file cleanup, export activation, schema/migration changes, or ownership-gate promotion is authorized. Keep the existing `UNVERIFIED`/`UNRESOLVED` fail-closed distinctions and all current STO-08 guards. Existing image insertion remains an independent, already reviewed *insert-only* operation; it must not be repurposed as a transform writer.

**Proposed work ownership:** EDT-14 developer owns selection geometry, rendering, input and session-history integration. The STO-08 collaborator owns or explicitly reviews the storage command implementation and retained-image safety tests; both approve its contract and final integration prior to C3. Neither owner may implement against an unresolved policy merely because this ADR has been drafted.

## Alternatives considered

| Alternative | Why not recommended |
|---|---|
| Treat lasso as an enclosing axis-aligned rectangle or select by bounds alone | Fails the specified intersection-with-visible-geometry semantics for thin strokes/open shapes |
| Only implement ink + shapes, defer text/images | Useful intermediate checkpoint but does not satisfy issue #45 |
| Flatten mixed elements into bitmap/stroke paths before transforming | Destroys semantic editing, canonical IDs, stroke samples and media references |
| Rewrite every stroke sample/shape/text frame and font size on group scale | Higher drift, width/font inconsistency and representation risk compared with affine composition |
| Extend unrestricted `ReplaceElements` for arbitrary image JSON | Weakens the deliberate media writer boundary and permits asset substitution or ownership mistakes |
| Separate media and non-media commits | Can produce partial mixed moves and two undo/revision entries |
| New selection persistence table, second autosave queue or undo service | Duplicates authoritative state and breaks session chronology |
| Block all EDT-14 work until STO-08 closes | Unnecessary: C1/C2 design can proceed without asset mutations, while C3 remains gated |

## Consequences and tradeoffs

- Selection and transform geometry become a shared derived editor capability; the canonical model and v1 draft schema remain unchanged.
- Reusing guarded revision commits simplifies durable semantics but requires a **new, narrowly reviewed transform command** that cannot bypass media restrictions.
- Preserving transformations on the element envelope retains text styling, image crop and native ink data, but demands accurate transformed rendering and hit-testing under rotation/shear/reflection and small magnitudes.
- A retained-image transform that preserves ownership need not modify the edge table; nevertheless the correct lease/verification policy and legacy gating require explicit STO-08 review and negative tests.
- Conservative page boundaries and cancellation can reject some edits to preexisting clipped content; that behavior must be visible and deterministic, not a silent geometry rewrite.
- No production text/image creation UI is implied. EDT-14 acceptance needs real typed rendering and synthetic persisted objects; later CNT issues implement rich content UX.
- Android-only test evidence does not certify Apple cross-platform rendering/format interoperability or physical stylus palm rejection.

## Verification / review plan (proposed; **not executed**)

The following are mandatory *planned* gates for the corresponding code changes, not claims of passing tests.

| ID | Area / scenario | Expected evidence |
|---|---|---|
| S01 | Lasso intersects painted stroke/shape paths, not empty unfilled interiors | Accurate selected set with page clipping and tiny/edge contacts |
| S02 | Concave/self-intersecting polygon, implicit closure and zero-area gesture | Deterministic even-odd and tap/slop behavior |
| S03 | Transformed/rotated/sheared ink, shape, text frame and image frame | Correct canonical-page hit geometry independent of zoom, pan and density |
| S04 | Overlap, render order, locked, transparent and unavailable elements | Topmost tap, handle precedence, no mutation of ineligible members |
| S05 | Mixed page sizes, gutter/page exit and already-clipped content | One-page selection; no accidental transfer or geometry normalization |
| S06 | Translation and positive uniform scale with degenerate/overflow cases | Left-composed affine transforms, exact frozen previews, invalid candidate rejection |
| S07 | Stroke + shape + text + image retained rendering and clipping | Same canonical order; mixed preview does not change alpha stacking |
| S08 | Actual registered, finalized image with matching canonical `assetId` and typed edge | Image selectable/movable/scalable; same original file/metadata/edge afterward |
| S09 | Missing/mismatched/extra image owner edge, legacy JSON, invalid gate/metadata | Fail closed; no partial selected-element updates, note revision or asset mutation |
| S10 | Locked member, stale revision, page ownership mismatch, concurrent writer | Whole mixed transform rejected, exact previous state retained |
| S11 | Injected database failure/cancellation, unknown acknowledgement, process restart | Atomic rollback or reconciled confirmed state; no false Saved or blind replay |
| S12 | Mixed move/scale followed by undo/redo and eraser/shape interleaving | Exact before/after envelopes, IDs/order and chronological session history |
| S13 | Real text content and image inserted through approved synthetic fixture setup | Four typed elements survive save/reopen, reselect, move and scale |
| S14 | Concurrent consistent export snapshot and editor transform (when separately authorized) | Snapshot captures entirely old or new revision; no mixed transform/asset registry |
| S15 | API 37 and API 26 targeted instrumentation; tablet and phone; physical stylus if available | Separate recorded runtime evidence, unknown/untested aspects labeled |
| S16 | Existing image writer, PNG/JPEG decoder, owner-edge, eraser, shape and viewport regressions | No guard relaxation or inappropriate expansion of device/test authorization |

Routine Windows `android/` checks: `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, and `:app:compileDebugAndroidTestKotlin`; focused Android instrumentation by discovered test class on a verified device/AVD; shared validator tests if draft schema fixtures are touched. Follow `android-verification-matrix.md`. Avoid running the collaborator's untracked B1 research, its unapproved B2/B3 execution, storage pressure, or destructive device tests without their separate authorization. API 26 device evidence and real stylus/palm fidelity must be reported distinctly from API 37 emulation. Review `git diff --check`, diff scope, actual test execution vs cache, peer approval, and CI on final committed changes.

## Implementation checkpoints / authorization boundaries

| Checkpoint | Proposed deliverable and gate | Authorization |
|---|---|---|
| **C0 — This ADR** | Jointly review D1–D5 and INT-01–INT-06, resolve specified open decisions, record explicit approval or amendments | **Document drafting only; no Kotlin/test execution** |
| **C1 — Derived geometry/model** | Unified transient page selection, four-type geometry/hit adapters, deterministic polygon/affine tests; no media writer | Separate scope approval after C0 |
| **C2 — Rendering** | Ordered retained text and verified asset-backed image painting, transform/clipping and preview substitution tests; avoid unapproved asset attachment UI | Separate approval; coordinate STO-08 read policy |
| **C3 — Restricted mixed writer** | Storage-owned, guarded atomic transform and negative owner/lease/rollback tests with exact INT-04 policy | **Blocked until joint storage-security approval and explicit implementation authorization** |
| **C4 — History** | Confirmed mixed transform admission, exact undo/redo and shape/eraser chronology | Separate approved checkpoint |
| **C5 — Production lasso/transform gestures** | Page-tool routing, select/drag/group-scale/handles, cancellation and manual usability | Separate approved checkpoint |
| **C6 — Acceptance/review** | Four-type mixed lasso→move→scale→undo/redo→save/reopen, regression and CI/device evidence | Close issue only after merge and stated gates |

## Open decisions for joint review — not implicitly accepted

1. **O1 / INT-04 — Must an unchanged retained image original be fully reverified/decoded on each geometry edit?** Choose A, B or a narrower justified policy. Specify lease order, gate semantics, multiple-image members and tests. **Blocking C3.**
2. **O2 — Gate handling for retained owners:** Which diagnostic gate states permit transform of an already committed, consistent owner? Distinguish *existing-reference editing* from a new attachment or cleanup permit. **Blocking C3.**
3. **O3 — Geometry interaction thresholds:** Exact minimum lasso, tap selection, scale limits, edge contacts and near-singular cutoffs; review EDT-12 dp conventions and test on device. **Blocking relevant C1/C5 choices.**
4. **O4 — Already-clipped group behavior:** Accept the conservative fully-contained completion rule or approve a narrowly justified alternative that preserves original geometry. **Blocking C5.**
5. **O5 — Transparent text/image selection and unavailable-object policy:** Agree whether whole-frame selection applies to zero-opacity images/text, and how unsupported but visible fallback elements are reported. **Blocking C1/C2.**
6. **O6 — Typed command form and history snapshot:** Exact Kotlin visibility, expected-old comparison (envelope vs transform plus invariant checks), no-op semantics, checked row counts, error categories and unknown-commit reconciliation. **Blocking C3/C4.**
7. **O7 — Rich-content rendering authorization:** Agree on asset read/decode/cache bounds, UI reachability and synthetic test strategy for real image elements without enabling CNT-04 acquisition UX. **Blocking C2.**
8. **O8 — Ownership of storage code:** Confirm that the STO-08 developer will implement or explicitly peer-review the narrow writer, and arrange branch integration without carrying unrelated untracked export research. **Blocking C3.**

## Follow-up work

1. Share this **proposed** ADR with the STO-08 collaborator for independent read-only source/contract review. Collect precise amendments, especially O1/O2/O6/O7 and concurrency tests.
2. Reconcile the two reviews. Both developers explicitly accept or amend the final D/INT decisions in a dated ADR approval record. Do not infer acceptance from silence, passing CI or a documentation commit.
3. Once approved, authorize each C1–C6 checkpoint separately with explicit file scope; preserve existing media fail-closed rules and current collaborator research branch state.
4. Implement a restricted mixed transform command only at approved C3, with storage-owner involvement. Add actual canonical four-type fixtures, targeted tests and CI evidence before closing EDT-14.
5. Keep CNT-02 text editor UX, CNT-04 image acquisition/edit UX, EDT-18 general clipboard/undo and STO-08 export/GC in their respective tasks.

**Current outcome:** ADR-0003 is a **review artifact only**. No new API, image editing permission, test execution, database migration, gate promotion, export activation or asset cleanup is authorized by creating or sharing this file.
