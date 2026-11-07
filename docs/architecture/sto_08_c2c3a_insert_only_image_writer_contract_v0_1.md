# STO-08 — C2C-3A Insert-Only Verified Image Writer Contract V0.1

**Status:** APPROVED C2C-3A DESIGN — C3-D1 through C3-D6 jointly accepted on 2026-11-07. Documentation approval only; **not authorization for image owner writes, UI activation, or decoder implementation before C2C-3B design review**.

**Data-layer baseline inspected:** `1162731` (C2C-2C, PR #120). **Integration baseline:** `c8939a3` (PR #121, editor input tools); its changes were reviewed and do not alter the inspected data-layer, ownership, Room-schema or shared-format interfaces.

**Issue:** STO-08 #31 (keep open)

**Upstream:** `docs/architecture/sto_08_c2c2_verified_attachment_publication_protocol_v0_1.md` (P1–P7 jointly approved); `docs/architecture/sto_08_c2a_transactional_ownership_writer_contract_v0_1.md` (C2-01–C2-09); Notebook document model V0.2 (planning specification, not frozen wire format).

## 1. Observed source facts, not proposals

- `DocumentEdit` is a sealed interface with generic page/element commands; it has no media-specific operation or expected-revision field. `DocumentRepository.commit(noteId, edit)` has no expected-revision argument.
- `RoomDocumentRepository.commit` owns the existing `AppDatabase.withTransaction`, enforces active note/notebook and page ownership, and increments the note revision once for a changed command. It does not compare against a caller-supplied persisted expected revision.
- `CanonicalContentValidator.element` explicitly rejects `type = image`; `background` explicitly rejects `pdfPage`. Existing generic commands and C2C-1 media guards remain the only safe behavior today.
- `AssetOwnershipDao.insertElementReference` uses `ABORT`, and the edge's `element_id` and `asset_id` have restrictive foreign keys. `readFailClosedGateStatus` returns only `UNVERIFIED`, `UNRESOLVED`, or `UNSAFE`.
- `NoteDao.update`, `ElementDao.insert` and ownership insertion are available inside the transaction. Existing element ordering is governed by the repository's V2 two-phase ordering logic.
- `ReadOnlyFinalizedAssetVerifier.verify` checks finalized metadata, canonical path, no-follow opens, bounded byte count, SHA-256 and MIME signature. It **does not fully decode** image content or provide a persistent lease.
- `AssetAcquisitionLeaseCoordinator.withLease` holds `.asset-publication.lock` across suspension; `ManagedAssetFileStore` shares the same lock domain. The reviewed lock order is **lease first, then the existing Room transaction**.
- The ownership projection is not complete for legacy media; gate promotion to `VERIFIED`, audited backfill, and asset reclamation are not implemented.

## 2. Approved first writer scope (C3-D1)

Permit **one insert-only PNG/JPEG image element** into an **existing active note and existing, non-PDF page** that has no conflicting or unverifiable media/ownership state. The image asset must already have a registered, immutable `finalized` row; the insertion command must never call `ManagedAssetRegistrar.register` or `stageAndFinalize` while holding the lease.

No new image assets are created by this command; no PDF page backgrounds, replacements, transfers, deletion, duplication, import-to-note, editor UI enablement, undo for media, export pins, audit/backfill, metadata/file cleanup or garbage collection are authorized. Preserve generic media rejections.

**Initial conservative target-page restriction:** If the target page already has a PDF background, unexpected page ownership edge, legacy image, indexed media element, or mismatch between element kind, JSON and typed edge, reject without changing anything. This deliberately defers *second image on the same page* and legacy repair; two independent owners may still attach the same finalized original to **different otherwise-safe pages**.

## 3. Approved command shape and JSON contract (C3-D2; exact Kotlin API deferred)

A dedicated internal command (provisional name `InsertVerifiedImage`) carries:

| Input | Constraint |
|---|---|
| `noteId`, `pageId`, `elementId`, `assetId` | Nonempty canonical IDs; page belongs to active note; new element ID globally unused; asset metadata finalized |
| `expectedNoteRevision` | Required `Long >= 0`; checked against actual persisted note revision **inside the owner transaction**, before any write |
| `at` | Optional insertion index; use existing `insertionAt` / two-phase ordering semantics |
| `transform` | Six finite doubles `[a,b,c,d,tx,ty]`; nonzero finite determinant; existing transform convention |
| `frame` | `{x,y,width,height}` finite; width and height positive; page-document units |
| `opacity` | Finite number in `[0,1]` |
| `locked` | Explicit `false` for first writer; no new editing of locked media |

No caller-supplied raw image JSON, arbitrary path, MIME, hash, or crop is accepted. The repository/media-specific validation path constructs the V0.2 canonical element envelope itself:

```json
{
  "id": "<element-uuid>",
  "type": "image",
  "transform": [1, 0, 0, 1, 0, 0],
  "locked": false,
  "payload": {
    "assetId": "<registered-finalized-asset-uuid>",
    "frame": {"x": 280, "y": 160, "width": 80, "height": 53.333},
    "opacity": 1.0
  }
}
```

`elements.kind` must be `image`; `element_asset_references(element_id, asset_id)` must refer to exactly the same `elementId` and `payload.assetId`. No `crop` in the first writer. Canonical JSON is source of truth; edge is its typed projection. IDs and JSON field rules must be reconciled with the final shared schema before implementation; V0.2 is a planning specification.

**Approved API direction:** Retain `DocumentRepository.commit(noteId, edit)` for existing non-media callers. Add a **dedicated internal entry point owned by `RoomDocumentRepository`** or its tightly controlled collaborator, which coordinates the lease and media verification before invoking the repository-owned transaction. Do not add a freely callable generic `DocumentEdit.InsertElements` bypass and do not expose an unverified asset/JSON injection seam as an application-facing API. Exact Kotlin visibility, injection seams and API form must be specified and reviewed before C2C-3C implementation; the existing generic API remains unchanged.

## 4. Approved publication and revision sequence (C3-D3, C3-D4, C3-D5)

1. Validate command shape, finite geometry, IDs, and supported operation without mutation.
2. **Acquire the existing asset lease**. Reject cancellation, timeout and invalid lock node without opening an owner transaction.
3. Under the same lease, resolve the registered `finalized` asset, call the existing read-only verifier and perform **full bounded PNG/JPEG decoding**. Verify actual decoded type, dimensions and allocated pixel budget. No untrusted external path is accepted. Keep acquisition/verification/decoding before the Room transaction; do not call the synchronous registrar while holding the lease.
4. Enter **the existing document transaction owner**. Re-read the note and notebook; require active state and exact `expectedNoteRevision`. Reject exhausted revision. Confirm target-page ownership, valid non-PDF background, canonical-safe existing contents/edges, and asset identity/status consistent with the verified result. Require `readFailClosedGateStatus() == "UNVERIFIED"`; reject `UNRESOLVED`, `UNSAFE`, missing/invalid or unexpected gates. Revalidate insertion position and unique ID against persisted state.
5. Inside **that same transaction**, insert canonical image `ElementEntity` (using existing ordering rules), insert exactly one matching `ElementAssetReferenceEntity` with `ABORT`, and update note revision/timestamp once. Enforce checked row counts; propagate SQL/FK/uniqueness exceptions.
6. Return `DocumentCommit` **only after transaction success**. Release the lease in `finally` after commit or rollback. Do not delete a finalized original on failure.

**Concurrency:** If any other command changes the note revision between earlier validation and Step 4, the new image operation rejects on stale expected revision with a distinguishable failure (never a successful no-op or unexpected DB error), without applying content, edge or revision changes. Select the exact existing `LibraryErrorCode` or a reviewed dedicated conflict result before C2C-3C implementation. Ordinary non-media `commit` retains its existing public behavior; changing that API is a separate decision.

**Owner consistency:** For the first writer, assume a target page contains only recognized non-media elements with no indexed owner edges, and no indexed page edge. Never infer from the lack of an edge that legacy media is absent; inspect canonical JSON/kind as well. An unrelated legacy reference elsewhere in the database does not make the global gate complete.

## 5. Full-decoding prerequisite (C3-D6; numeric/API specifics deferred)

The current verifier is **signature-plus-hash**, not an image decoder. Before writing any owner reference, define and test a full PNG/JPEG decode component with fixed, reviewed bounds for compressed bytes, positive dimensions, total pixels, estimated decoded memory, malformed/truncated images, and exceptional decoders. The implementation must keep private-path confinement/no-follow safety and avoid resolving an untrusted path from a command.

**C3-D6 approval is of the mandatory bounded full-decoding requirement, not of particular numerical limits or a decoder implementation.** Before implementing C2C-3B, separately review and record the exact maximum compressed bytes, width/height, decoded pixels, memory budget, Android API 26+ decoding strategy, how the same verified original is opened under the lease, cancellation/resource release, and genuine full-decode tests (not just signature-valid placeholder bytes). A successful decode is not proof of file immutability against non-cooperating filesystem actors; the lease coordinates only cooperating clients.

## 6. Minimum acceptance matrix for C2C-3C (C3-D5)

| ID | Scenario | Expected outcome |
|---|---|---|
| I01 | Valid finalized PNG, safe page, current expected revision, gate UNVERIFIED | One canonical image row + exactly one matching edge + one revision increment; survives DB reopen |
| I02 | Valid finalized JPEG | Same; original bytes preserved |
| I03 | Same finalized asset attached to two separate safe page owners | Distinct correct edges sharing one asset row; no ref count |
| I04 | Stale expected revision from an intervening editor commit | Reject; page/element/edge/revision unchanged |
| I05 | Element ID collision, unexpected owner edge, or inconsistent canonical kind/JSON | Reject; unchanged |
| I06 | Missing asset, missing original, symlink, wrong path, size/digest/signature or corrupt metadata | Reject before owner mutation; no repair |
| I07 | Valid file header and digest but corrupt/truncated/undecodable PNG/JPEG | Full-decoder rejection; no owner mutation |
| I08 | Pixel/byte/dimension limits exceeded | Reject before owner mutation, no unbounded allocation |
| I09 | `UNRESOLVED`, `UNSAFE` or missing/invalid gate | Reject; gate not changed |
| I10 | Injected failure after element insert, before edge, or after edge before note update | Entire transaction rolls back; revision unchanged |
| I11 | Transaction cancellation, app process death, or conflicting concurrent insert | No acknowledged partial owner state; reopen consistent; lock released |
| I12 | Generic `DocumentEdit.InsertElements` still receives image JSON | Reject; existing media guards retained |
| I13 | PDF background, preexisting legacy image, or inconsistent target-page index | Reject conservatively; no implicit backfill |
| I14 | Insert at middle, first and last indices | Existing two-phase ordering invariant preserved |
| I15 | Reorder/Trash/restore after image insertion | Edge remains; existing unsupported image remove/replace/transfer/purge still rejected |

Use isolated Room v4 databases and UUID-named app-private asset sandboxes. Android instrumentation only on a freshly identity-verified **`STO07_S03B_Disposable`** emulator. Require fresh class-specific JUnit results. Include JVM tests, Android compilation and lint; no testing on unknown/personal devices.

## 7. Approved decisions, deferred details, and checkpoint gates

- **C2C-3A (this document):** Joint review of C3-D1 through C3-D6 complete on 2026-11-07. Documentation only; no Kotlin source or Room changes.
- **C2C-3B:** Review numerical/API decoder design first, then implement a standalone *read-only full bounded image decoder/verifier* with genuine PNG/JPEG fixtures and rejection tests. Separate PR; no owner writes and no UI hook.
- **C2C-3C:** Implement approved internal insert-only writer in existing document transaction owner, with full end-to-end tests. Keep it unreachable from production UI until separately approved for activation.

### Joint decision record — C3-D1 through C3-D6 (2026-11-07)

1. **C3-D1 — APPROVED:** Insert-only PNG/JPEG on an otherwise asset-free existing non-PDF page. No crop, replacement, deletion, PDF or second image on the same page in the first writer.
2. **C3-D2 — APPROVED DIRECTION:** Dedicated internal entry point under `RoomDocumentRepository` transaction ownership; do not relax `DocumentEdit.InsertElements`. Exact Kotlin API shape and dependency seams require review before C2C-3C implementation.
3. **C3-D3 — APPROVED WITH CLARIFICATION:** Explicit, nonnegative expected note revision checked inside Room; each accepted insertion advances the revision exactly once; stale requests fail distinctly and never count as no-ops. Select/review the exact error category before C2C-3C implementation.
4. **C3-D4 — APPROVED:** Acquire the lease, validate finalized bytes and fully decode image under the lease, then use the existing Room transaction with fresh owner, asset, revision and gate checks; release only after commit/rollback.
5. **C3-D5 — APPROVED:** `UNVERIFIED` permits only an independently verified new attachment after separate writer approval. Reject `UNRESOLVED`, `UNSAFE` and absent/invalid gates; fail closed on legacy/inconsistent target-page ownership, never backfill or promote the gate, and never delete files or metadata.
6. **C3-D6 — APPROVED REQUIREMENT, DESIGN DEFERRED:** Full bounded PNG/JPEG decoding is mandatory. Exact byte, dimension, pixel and decoded-memory limits, Android API 26+ strategy, and decoder test approach must be reviewed and approved in C2C-3B **before** decoder implementation.

**Review outcome:** C3-D1–C3-D6 approved as design direction with the above implementation gates. This approval authorizes C2C-3B design and test planning, not a production owner writer, editor attachment activation, PDF support, legacy reconciliation, completeness certification, cleanup or garbage collection. A separate C2C-3B decoder-design approval and C2C-3C writer/code review remain mandatory. STO-08 #31 stays open.
