# STO-08 — C2C-3B V1 JPEG Encoding Scope Amendment (Approved V0.2)

**Status: JPEG ENCODING SCOPE JOINTLY APPROVED — 2026-11-10.** Both developers confirmed the narrowed V1 JPEG encoding decision in project discussion. This document amends only the accepted *encoding subset* of `sto_08_c2c3b_bounded_image_decoder_contract_v0_1.md`, jointly approved 2026-11-07. It is **not** approval of exhaustive malformed-image rejection, decoder PR/merge, the C2C-3C writer, or production attachment activation. The original contract's B-D1–B-D6 safety gates remain binding, including the conditional B-D4 gate.

## 1. Jointly approved V1 encoding policy

- **Registered MIME `image/png`:** support PNG only after bounded structural/compressed-data checks and full-resolution Android decode succeed, subject to the approved limits and B-D4 review.
- **Registered MIME `image/jpeg`:** accept only **single-scan, baseline sequential-DCT JPEG** (SOF0, exactly one SOS) for which the strict structural validator, baseline Huffman entropy-completeness validator, and Android `BitmapFactory` bounds/full decode succeed.
- **Genuine progressive JPEG (for example SOF2):** reject fail-closed as an **unsupported V1 encoding**, even if Android can render it.
- **Genuine baseline multi-scan JPEG:** reject fail-closed as an **unsupported V1 encoding**, even if Android can render it.
- Do not silently convert, recompress, normalize, truncate, substitute, or attach an unsupported original. A rejection must preserve original bytes, the finalized asset row, owner edges, document state, and ownership gate.
- The decision narrows the supported JPEG subset; it **does not remove image attachments from the V1 product** or add external URI input, PDF, WebP, writer authorization, automatic fallback, or any new attachment workflow to C2C-3B.

## 2. Rationale and actual verification evidence

Android `BitmapFactory` accepted a JPEG with shortened entropy and a preserved EOI marker. The strict baseline entropy checker was introduced to reject that failure class. Expanding this security-critical parser to progressive or multi-scan encodings would require separately reviewed format-positive and adversarial fixtures, parser changes, and Android compatibility testing.

The implementing team reported these results for the hardened C2C-3B I3 snapshot on 2026-11-10:

- **Android API 26:** six suites, **39/39 passed** (Step 71A, `STO08_API26_Disposable`).
- **Android API 37:** the same six suites, **39/39 passed** (Step 71B, `STO07_S03B_Disposable`).
- **Both platforms:** single-scan JPEG accepted; genuine progressive and multi-scan JPEG rejected as unsupported (three JPEG compatibility tests per API); full bounds/pixel-limit suites passed.
- **PNG regression:** Step 69 proved that valid-CRC, malformed zero-length `gAMA` and `tRNS` ancillary chunks were previously accepted by the full API 26 decoder. Step 70 hardened the structural validator; both new adversarial tests subsequently passed on API 26 and API 37, as part of the respective 39-test matrices.
- **Other verification:** structural-validator JVM tests and Android lint passed after the PNG hardening; all 13 I3 paths remained staged without writer/ownership changes during the gates.

These are executed regression results for identified cases, **not** proof that all malformed PNG/JPEG variants will be rejected. They do not waive B-D4.

## 3. V1 product requirement and staged delivery

**Production V1 must support editable image attachments.** The Notebook V1 PRD's R-CNT-02 requires inserted image objects that can be selected, moved, and resized; the present read-only decoder is a safety prerequisite, **not the delivered image-attachment feature**. The distinct R-CNT-03 PDF workflow also remains in the V1 plan and is not enabled by this JPEG amendment.

1. **C2C-3B (this increment):** internal, read-only inspection of registered finalized originals, returning verified metadata only. The returned metadata is not attachment authority. No writer/Room mutation, editor insertion, UI activation, gate promotion, repair, or garbage collection is authorized by this scope approval.
2. **C2C-3C (required for V1):** implement and review lease-aware, revision-checked, atomic owner/document attachment insertion under the already approved writer direction, with rollback and interrupted-operation tests. Do not proceed to production activation until B-D4 and the writer/ownership gates are explicitly resolved.
3. **V1 editor/UI integration:** support insertion, rendering, persistence and editable transformations of supported image objects, with clear unsupported-format and corrupt-file feedback. These are separate future implementation and acceptance tasks, not effects of this amendment.
4. **V1 release acceptance:** verify import/attachment failure behavior, note/trash/export asset reference retention, recovery and ownership integrity, and cross-platform/export behavior before shipment. Keep STO-08 #31 open until its own acceptance criteria are met.

The current progressive/multi-scan rejection is a **supported-format boundary**, not a decision to omit images from production V1. Widening JPEG support later requires a separate documented review and regression matrix.

## 4. Safety boundaries and remaining decisions

- **B-D4 remains conditional.** The PNG validator's recognized ancillary handling and the tested JPEG parser scope do not establish exhaustive malformed-input rejection. Document residual acceptance risk and obtain explicit joint review before any production attachment activation; passing 78 Android test executions alone is not a waiver.
- Existing **B-D1** limits (16 MiB encoded, 4096 per dimension, 8,388,608 pixels, 32 MiB ARGB_8888 budget), **B-D2** registered-finalized verified bytes under a lease, **B-D3** API 26+ full decode, **B-D5** cancellation and cleanup, and **B-D6** Android/device and existing regression coverage remain unchanged.
- Native `BitmapFactory` full decode is synchronous; cancellation is checked before/after it, but cannot be promised to interrupt the native operation. Successful allocation checks do not bound total process peak memory.
- Unsupported-format failure currently has no independently approved end-user error classification. C2C-3C/UI must select and test an appropriate typed outcome instead of silently treating rejection as success.
- No owner edge, Room row, revision, ownership-completeness gate, deletion/reclamation, attachment UI, or document content modification is authorized as part of C2C-3B.

## 5. Decision and checkpoint status

- **Joint V1 JPEG format choice:** APPROVED by both developers in project discussion, reported **2026-11-10** — PNG and strictly validated single-scan baseline JPEG; progressive and baseline multi-scan JPEG rejected safely as unsupported.
- **Production V1 image attachments:** REQUIRED. Not removed or permanently deferred by this scope choice.
- **C2C-3B regression gate:** 39/39 on API 26 and 39/39 on API 37, plus JVM structural tests and lint, per team-provided logs. Final independent implementation review/PR decision is separate.
- **B-D4 residual malformed-input assessment:** OPEN. No guarantee of exhaustive rejection or permission to activate the production attachment writer is recorded here.
- **C2C-3C writer and editor integration:** FUTURE WORK; require their own scoped implementation, tests, reviews and explicit production authorization.
- **STO-08 issue #31:** remains OPEN; asset reference tracking/cleanup acceptance criteria have not been satisfied by this decoder increment.
