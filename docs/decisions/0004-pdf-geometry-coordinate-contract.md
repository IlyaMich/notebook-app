# ADR-0004: PDF page-space to canonical page geometry

- **Status:** Proposed — requires joint Android/Apple review
- **Date:** 2026-11-12
- **Decision makers:** Android and Apple developers
- **Related tasks / issues:** PDF-01, #57; FMT-01; future PDF-03
- **Scope:** Mathematical and validation contract only. No PDF parser, importer, renderer, or export implementation is authorized by this ADR.

## Context

The approved Notebook Document Model V0.2 defines fixed-size mixed-dimension pages, a top-left origin with X right/Y down, and one document unit (DU) equal to 1/72 inch. The portable `pdfPage` background refers to an immutable original PDF asset by `assetId`, zero-based `pageIndex`, and `displayBox: "crop"`; app-created annotations remain separate editable page-local elements. The EDT-01 viewport contract intentionally excludes PDF crop/rotation conversion. PDF-01 must provide deterministic source-to-canonical and inverse geometry for both future Android and Apple adapters without changing the document schema.

## Proposed decision

### 1. Coordinate spaces and metadata

- **S — PDF default user space:** Unrotated source page coordinates, X right/Y up, in PDF user-space units. These are *not* device pixels, viewport coordinates, a PDFKit screen/view point, or coordinates already transformed by a native rendering API.
- **P — canonical page space:** Top-left page origin, X right/Y down, in DU (1/72 inch); the page is displayed after the effective crop and page rotation.
- **V — viewport:** Existing transient page-to-viewport conversion defined by EDT-01/EDT-03. PDF-01 does not modify it.
- Extract from each selected source page: effective inherited `/MediaBox`, effective inherited `/CropBox` if present, inherited `/Rotate` (default 0), and page-local `/UserUnit` (default 1). `/UserUnit` is not a page-tree inheritable field.
- All boxes are in S before page rotation. `/Rotate` describes clockwise page-display rotation and is normalized modulo 360. Negative and out-of-range multiples of 90 may be normalized; non-multiples of 90 are rejected.
- Use effective crop `C = MediaBox ∩ (CropBox or MediaBox)`; do not assume a crop outside the media box remains visible. If the intersection has nonpositive width or height, reject the page geometry.
- Let `C = [x0, y0, x1, y1]`, with `w = x1 - x0 > 0`, `h = y1 - y0 > 0`, `s = UserUnit > 0`. A valid `s` converts one PDF default user-space unit into `s` DU. Do not silently replace a present unsupported `/UserUnit` with 1.
- PDF content streams may contain additional current transformation matrices (CTMs); the page-level map below operates on coordinates **already expressed in PDF default user space**. Transforming a path from its own content-local space to default user space is a separate responsibility.

### 2. Source point to canonical page point

With source point `(x,y)` expressed in S, define `u=x-x0`, `v=y-y0`. The following mappings define coordinates `(X,Y)` in P. The 0/90/180/270 values are *clockwise PDF page-display rotations*.

| `/Rotate` | `X` | `Y` | Canonical page width | Canonical page height |
|---|---|---|---|---|
| 0 | `s*u` | `s*(h-v)` | `s*w` | `s*h` |
| 90 | `s*v` | `s*u` | `s*h` | `s*w` |
| 180 | `s*(w-u)` | `s*v` | `s*w` | `s*h` |
| 270 | `s*(h-v)` | `s*(w-u)` | `s*h` | `s*w` |

The operation order is: resolve inherited metadata and effective crop; translate by the crop lower-left; apply the specified clockwise quarter-turn and the Y-direction reversal required by P; scale uniformly by `/UserUnit`. The displayed size is derived from the same operation. Always check finite intermediate and final results.

Using the existing canonical column-vector affine convention, `X = a*x + c*y + tx`, `Y = b*x + d*y + ty`, the source-to-page matrices `[a,b,c,d,tx,ty]` are:

| `/Rotate` | Source-to-page affine array |
|---|---|
| 0 | `[s,0,0,-s,-s*x0,s*y1]` |
| 90 | `[0,s,s,0,-s*y0,-s*x0]` |
| 180 | `[-s,0,0,s,s*x1,-s*y0]` |
| 270 | `[0,-s,-s,0,s*y1,s*x1]` |

These arrays describe a change between source and canonical coordinate systems; they are **not** persisted element transforms. In particular, they must not be applied to a PDFKit point that was already crop/rotation-normalized by the native adapter.

### 3. Canonical point back to source PDF coordinates

For a canonical point `(X,Y)`, the inverse transformations are:

| `/Rotate` | Source `x` | Source `y` |
|---|---|---|
| 0 | `x0 + X/s` | `y1 - Y/s` |
| 90 | `x0 + Y/s` | `y0 + X/s` |
| 180 | `x1 - X/s` | `y0 + Y/s` |
| 270 | `x1 - Y/s` | `y1 - X/s` |

For each valid case, both directions must be tested independently against committed expected points, then round-tripped. Transform PDF source rectangles, paths, and annotation anchors by mapping the actual corner/path points; do not assume the original width/height orientation survives quarter-turns. Points outside the effective crop can be mapped mathematically, but rendering is clipped to `[0,pageWidth] × [0,pageHeight]` and they do not become new page-owned elements.

### 4. Deterministic worked examples for fixture review

Take `/MediaBox=[0,0,300,400]`, `/CropBox=[20,30,220,330]`, and `/UserUnit=1`. Effective crop is `[20,30,220,330]`, `w=200`, `h=300`.

| Rotation | Canonical size | Source lower-left `(20,30)` | Source upper-right `(220,330)` |
|---|---|---|---|
| 0 | `200 × 300` | `(0,300)` | `(200,0)` |
| 90 | `300 × 200` | `(0,0)` | `(300,200)` |
| 180 | `200 × 300` | `(200,0)` | `(0,300)` |
| 270 | `300 × 200` | `(300,200)` | `(0,0)` |

For `/UserUnit=2`, every canonical dimension and coordinate offset from the page origin in this example doubles; source coordinates and original PDF bytes do not change.

Include separate fixture cases for an omitted CropBox, an inherited crop/rotation, a crop partially outside MediaBox (effective intersection), fractional coordinates, negative source origins, landscape/portrait mixed pages, and an interior annotation point so that checking only page corners cannot hide a sign error.

### 5. Validation and error semantics

- Reject missing/unresolvable MediaBox; malformed, nonfinite, or nonpositive box geometry; empty effective crop; unsupported display boxes; rotation not an integer multiple of 90; nonfinite/nonpositive `/UserUnit`; nonfinite/overflowed dimensions, mapped coordinates or affine coefficients; and numerically noninvertible results.
- `/UserUnit` limits and practical resource bounds need an explicit reviewed product policy. Do not adopt an arbitrary max as an undocumented cross-platform behavior. Unsupported magnitudes must fail clearly, not silently fall back to another scale.
- Numeric fixture comparisons should initially use `abs(actual-expected) <= max(1e-9 DU, 1e-12*abs(expected))` for ordinary-sized synthetic cases, subject to joint review and extreme-coordinate tests. Mathematical tolerance does not establish raster pixel-perfect equivalence.
- PDF parsing, password/decryption handling, unsupported PDF versions, actual page-count validation for `pageIndex`, and secure original-asset acquisition are **integration** responsibilities. The existing shared `.notebook` validator does not prove full PDF validity.

### 6. Persistence and adapter boundaries

- Keep the existing `pdfPage` background (`assetId`, `pageIndex`, `displayBox`) and page `size` fields. Keep the original PDF file immutable; do not persist a redundant source-to-page affine as a new authoritative field in schema version 1.
- The stored canonical page size for a PDF page must equal the derived displayed effective-crop/rotation size within a documented precision policy. Deciding how/when a native importer validates that invariant belongs to PDF integration.
- PDF source annotations are rendered as part of the immutable PDF background in V1. App-created annotations remain editable canonical elements, already in P; do not apply the source-to-page matrix to them again.
- Android and Apple adapters must agree on **which coordinate space a native API returns**. In particular, PDFKit `bounds(for:)` performs media-box intersection and does not mean the coordinates are already safe to treat as final top-left page coordinates. Prevent double crop, rotation, or `/UserUnit` scaling.
- Neither native runtime implementation is authorized in PDF-01-B1. Future adapters must conform to the committed shared golden fixture catalog. A shared Python test, or Android-only Kotlin conformance, is **not** proof of Apple runtime conformance.

## Alternatives considered

1. **Use each native PDF library's coordinate conversion as the source of truth.** Rejected: different conventions and hidden transformations risk cross-platform annotation drift.
2. **Persist a source-to-canonical matrix in every PDF background.** Deferred: duplicates information already derivable from the immutable asset and risks disagreement with page `size`; revisit only with evidence of a real need.
3. **Rasterize imported PDF pages and treat annotations as flattened pixels.** Rejected: violates immutable-original and editable-annotation requirements.

## Consequences and tradeoffs

The contract fixes a shared mathematical interface without tying the app to a PDF renderer. It adds responsibilities to platform adapters and requires explicit rejection where metadata cannot be interpreted safely. It does not yet prove visual alignment of rendering/export or successful cross-platform import, nor does it choose a PDF parser.

## Verification / review plan

1. Both developers review normalization, crop intersection, clockwise rotation direction, `/UserUnit` support, and equations **before** treating this ADR as Accepted.
2. PDF-01-C adds `shared/fixtures/pdf/` with independently reviewed expected coordinates, dimensions, inverse cases, fractional cases, and explicit invalid cases.
3. Run the existing shared test suite plus new geometry fixture tests. Add a Kotlin/JVM conformance consumer following current repository conventions; distinguish execution from compilation.
4. When an Apple adapter exists, execute the same fixtures in Swift/PDFKit and record platform-specific results separately. Full cross-platform acceptance remains pending until then.
5. PDF-03 integration must verify actual PDF metadata extraction, page rendering/crop behavior, and editable overlay placement against the same expectations.

## Follow-up work

- PDF-01-B2: incorporate approved decisions into a stable geometry specification and review numeric/error policy.
- PDF-01-C: add dedicated shared golden cases, independent expected results, executable tests, and Kotlin conformance.
- PDF-01-D: prepare documented platform handoff and PR evidence; do not assert unexecuted Apple tests.
- PDF-03: implement secure PDF parsing/import/rendering with provenance of coordinate conversions and validate `pageIndex` against PDF page count.

## References

- Notebook Document Model V0.2: `docs/architecture/notebook_document_model_spec_v0_2.md`
- EDT-01 viewport/gesture contract: `docs/architecture/edt_01_viewport_gesture_contract_v0_1.md`
- Shared golden fixtures: `shared/fixtures/README.md`
- PDF 32000-1:2008, clauses 7.7.3, 8.3 and 14.11: https://raw.githubusercontent.com/adobe/dc-acrobat-sdk-docs/master/docs/standards/pdfstandards/pdf/PDF32000_2008.pdf
- Apple PDFKit `PDFPage.bounds(for:)`: https://developer.apple.com/documentation/pdfkit/pdfpage/bounds(for:)
