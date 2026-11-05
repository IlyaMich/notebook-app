# STO-08 — Room v4 Ownership and Migration Design V0.1

**Date:** 2026-11-05
**Status:** C1B-1 architectural direction jointly approved; **Room v4 implementation requires separate review**
**Issue:** [STO-08 #31](https://github.com/IlyaMich/notebook-app/issues/31)
**Verified Android baseline:** `0edc939` (PR #111, C1A read-only inventory)
**Target document location:** `docs/architecture/sto_08_room_v4_ownership_migration_design_v0_1.md`
**Related contract:** `docs/architecture/sto_08_asset_reference_lifecycle_contract_v0_1.md` (AR-01–AR-10)

## 1. Scope and non-goals

This review chooses a safe direction for durable page/element asset ownership and historical database upgrades. It **does not** authorize a production Room migration, enable image/PDF editing, change permanent purge, introduce file deletion, or establish garbage-collection eligibility. Both developers approved the architectural direction in §9. Exact SQL, migration tests, and implementation safety gates still require review before C1B-2.

The baseline is Android Room v3: six entities (`folders`, `notebooks`, `notes`, `pages`, `elements`, `assets`), with exported schemas `1.json`, `2.json`, and `3.json`. Production `AppDatabaseProvider` installs explicit migrations `MIGRATION_1_2` and `MIGRATION_2_3` and no destructive fallback. The library callback creates hierarchy/Quick Notes safety triggers. Current page/element foreign keys use `RESTRICT`, and their canonical JSON lives in `pages.background_json` and `elements.element_json`.

STO-08 C1A added `DocumentAssetReferenceInspector` and `RoomAssetOwnerInventory` to identify direct media references and report ambiguous/incomplete scans. **The C1A `Completed` result is diagnostic, not proof of GC safety.** It has bounded scan limits, including 2,000 notes, 10,000 pages, 50,000 elements and 10,000 assets.

## 2. Source of truth and direct owners

1. Canonical stored page/element content is authoritative for **which** asset is referenced.
2. A `pdfPage` background has at most one direct asset owner: the page ID.
3. An `image` element has at most one direct asset owner: the element ID.
4. Note active/Trash status does **not** create or remove these edges. Folder/notebook and virtual library views add no asset owners.
5. Many distinct pages/elements may share the same `assets.id`, including across active and trashed notes.
6. The persisted reference index is a **transactional projection**, not an independently editable counter. A zero-row query against this index is never sufficient deletion authorization.
7. Exports/importers may require *additional protection* but are not direct document owners. Export protection is designed in C3.

## 3. Physical representation — approved direction; exact schema pending

**Preferred design for review:** two small typed reference tables, rather than one polymorphic `owner_kind`/`owner_id` pair:

- `page_asset_references`: one row per PDF-backed page, keyed by `page_id`, referencing `pages.id` and `assets.id`.
- `element_asset_references`: one row per image element, keyed by `element_id`, referencing `elements.id` and `assets.id`.

Each table would index `asset_id` for shared-owner lookup, require non-null asset IDs, and use restrictive foreign keys against both the owner row and asset metadata. Each owner ID is unique within its table. Two typed tables permit **database-enforced owner foreign keys**; a single polymorphic owner-ID column does not automatically enforce FKs to two different tables. Specific Room entity annotations, SQL names, index names, FK update actions and constraints require C1B-2 review and schema validation.

A viable alternative is one table with nullable `page_id` and `element_id`, two owner FKs, and an exactly-one-owner constraint. Compare Room schema compatibility, SQL integrity checks and migration complexity before choosing. **Do not select a bare polymorphic owner ID without a separately justified integrity mechanism.**

No `ref_count` stored on `assets` is recommended: counts are queries derived from owner edges. Neither proposed representation alone enforces JSON↔index equivalence; repository transactions and a verification mechanism must do that.

## 4. Required durable completeness / safety gate

C1B-2 must define a durable, independently reviewable **reference-index completeness state**, initially conservative after upgrading v3. Recommended semantics:

- `UNVERIFIED`: historical content has not been completely classified/backfilled; **all committed-original reclamation disabled**.
- `UNRESOLVED`: an attempted audit found missing metadata, unrecognized/corrupt content, unreachable records, limit exhaustion or other ambiguity; **all committed-original reclamation disabled**. Record limited reason codes without user content.
- `VERIFIED`: reserved for a later reviewed, transactionally consistent full audit/backfill. **Not set by the schema migration merely because new tables exist**, nor by a bounded C1A scan. Reaching this state does not by itself authorize GC; export/import races and C3/C4 gates remain outstanding.

The approved direction is a dedicated singleton database table with durable `UNVERIFIED`, `UNRESOLVED`, and `VERIFIED` states, initialized to `UNVERIFIED` on both fresh and upgraded installations. Exact SQL constraints, lifecycle, and invalidation rules still require C1B-2 review. It must survive restart and rollback; lack of a valid state must fail closed. Future writers must not claim a verified index after editing document JSON without updating corresponding edges atomically.

**Critical invariant:** An empty newly created reference table on an upgraded installation is *unknown coverage*, not evidence that all existing assets are unowned.

## 5. Migration strategy options and recommendation

### Option A — Full validated backfill in v3→v4 migration

Create reference tables and parse every stored page/element, including Trash, within the migration transaction. Insert all recognized edges; if content is ambiguous, either abort the migration without destroying v3 or persist unresolved state without claiming completeness.

**Tradeoff:** Strong immediate coverage when successful, but startup latency, memory bounds, corrupt/unsupported content and missing asset metadata require careful failure handling. SQLite migration/Room startup is a poor place for unbounded, expensive filesystem verification.

### Option B — Additive schema plus durable `UNVERIFIED` gate (preferred for C1B-2)

Create the new reference structure and completeness gate without rewriting any legacy note, page, element, Trash or asset row. Initialize the gate to `UNVERIFIED`. Keep current media mutations disabled. A separate, future reviewed bounded/resumable backfill or full audit may populate edges and advance the gate only after checking the complete authoritative dataset under defined consistency/locking semantics. **No GC or ownership-dependent deletion uses the new tables until that proof exists.**

**Why preferred:** A short, additive, rollback-friendly migration is safer than asserting completeness during app startup. However, an incomplete index is **not yet usable as an authoritative reference ledger**; every future media writer and purge path must respect that limitation.

If the team cannot guarantee a durable conservative gate and future repair path, stop and revisit Option A before shipping v4. Never silently produce an apparently complete but empty ledger.

## 6. Non-destructive upgrade rules

1. Preserve all existing six tables and their records, primary keys, note/page/element revisions, sorting, timestamps, deletion batches, Trash status, `assets` metadata and paths. Do not rewrite canonical JSON as part of this additive migration.
2. Preserve preexisting SQLite hierarchy/Quick Notes triggers, including independently installed test triggers; do not drop/recreate unrelated tables.
3. Register explicit `MIGRATION_3_4` alongside `MIGRATION_1_2` and `MIGRATION_2_3`. Retain historical migrations. Do not use `fallbackToDestructiveMigration` or a downgrade-destructive fallback.
4. Export `4.json` as a *new* Room schema; never edit `1.json`, `2.json` or `3.json`.
5. Keep the internal Room database version distinct from portable `.notebook` `manifest.schemaVersion` and from Apple GRDB's physical version.
6. If migration fails, the database must remain on its previous valid schema/version with all content intact. A later retry must work after the failure cause is removed.
7. Database structure alone does not verify immutable asset bytes; filesystem staging, SHA-256/MIME/path verification, export protection and cleanup remain separately gated.
8. Ensure any new owner FKs and `RESTRICT` behavior agree with future C2 mutation ordering and C3 child-first purge, without enabling those operations yet.

## 7. C1B-2 implementation boundaries (after approval)

Allowed: new Room entity/DAO definitions for reference edges, a durable conservative completeness-state representation, explicit v3→v4 migration, exported `4.json`, registration in `AppDatabase`/`AppDatabaseProvider`, and isolated migration/constraint tests.

Not allowed: changes to canonical JSON, auto-backfill falsely marked complete, asset-file deletion, metadata deletion, reference-count GC, media insertion/replacement enablement, active export pins, relaxing `CanonicalContentValidator`, or changing the existing asset-free permanent purge semantics.

If a table is introduced before its transactional writers exist, it must be clearly marked **not yet authoritative** and must not be used by purge, export or cleanup flows.

## 8. Minimum historical and fault test matrix

| Test ID | Fixture / event | Required evidence |
|---|---|---|
| V4-01 | v3 active and trashed notes with pages/elements | Every pre-existing row, ID, revision, timestamp and Trash marker survives |
| V4-02 | v3 asset metadata, with shared use and with no recognized owners | Metadata unchanged; new gate is `UNVERIFIED`, never GC eligible |
| V4-03 | v3 synthetic PDF/image JSON, unknown kind, malformed JSON, missing asset ID/metadata | No content discarded; no false `VERIFIED` state or deletion permission |
| V4-04 | v1→v2→v3→v4 and v2→v3→v4 historical upgrades | Original data preserved; each explicit migration used |
| V4-05 | Injected exception midway through v3→v4 | Transaction rollback; v3 remains intact; later retry succeeds |
| V4-06 | Reopen v4 database / process restart | Gate and edges (if any) remain durable; no false completeness |
| V4-07 | Direct invalid asset/owner FK insertion and duplicate edge | DB rejects inconsistent/duplicate rows as selected schema permits |
| V4-08 | `PRAGMA foreign_key_check`, `integrity_check`, Room schema validation | All pass; exported 4.json matches runtime schema |
| V4-09 | Original Quick Notes, hierarchy and custom test triggers | All still exist and enforce existing rules |
| V4-10 | Oversized library / bounded C1A `Incomplete` result | Does not promote persisted gate or allow asset cleanup |
| V4-11 | Upgrade under constrained storage / forced migration failure on disposable isolated DB | Last valid database retained; no destructive fallback |
| V4-12 | Attempted downgrade / unknown newer DB | Existing DB is preserved; no fallback recreation |

Use isolated Room databases and synthetic fixtures. Do not wipe or apply storage pressure to the primary emulator. For any destructive/fault-injection device test, verify `emulator-5556` is `STO07_S03B_Disposable` first.

## 9. Joint C1B-1 architectural decisions

**Joint review outcome (2026-11-05):** Both developers approved the following directions.

- **B1 — Approved:** Use two owner-specific tables, `page_asset_references` and `element_asset_references`, with restrictive foreign keys. Exact Room/SQL definitions remain subject to C1B-2 review.
- **B2 — Approved:** Use Option B: an additive v3-to-v4 migration without rewriting existing content or automatically declaring reference completeness.
- **B3 — Approved:** Use a dedicated singleton completeness-state table. Initialize it to `UNVERIFIED` on new and upgraded installations. Missing or invalid state must fail closed.
- **B4 — Approved direction:** Perform legacy classification and backfill in a separate, reviewed audit checkpoint. Incomplete or ambiguous results cannot authorize cleanup.
- **B5 — Approved boundary:** Future asset-backed mutations must update document content and ownership edges transactionally. Existing media guards remain enabled until C2 verification.
- **B6 — Approved:** Android owns Room implementation; both developers review lifecycle and cross-platform safety semantics. Apple persistence may use a different physical schema.

### Outstanding implementation gates

These approvals do not finalize physical SQL, migration failure handling, state transition APIs, audit concurrency, or transactional writer ordering.

C1B-2 requires separate review of the exact schema and migration test plan.

No asset deletion, garbage collection, unsupported media writes, or automatic `VERIFIED` promotion is authorized by this decision.
## 10. C1B-1 exit checklist

- [x] Both developers reviewed baseline facts and alternatives.
- [x] B1–B6 are approved or assigned to explicit later gates, without silently enabling cleanup.
- [ ] Historical fixtures and failure conditions are assigned test owners.
- [ ] No Room version bump, production schema change or destructive code in this checkpoint.
- [ ] C1B-2 may begin only after a reviewed implementation plan and migration test strategy are agreed.
