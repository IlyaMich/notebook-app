# PDF-01 — Geometry Verification Matrix and Cross-Platform Handoff V0.1

- **Status:** Proposed for joint review; not a completion sign-off
- **Date:** 2026-11-12
- **Issue:** [PDF-01 — PDF geometry, annotation and test-fixture contract (#57)](https://github.com/IlyaMich/notebook-app/issues/57)
- **Owner lane:** Joint (Android and Apple developers)
- **Implementation branch:** `feature/pdf-01-pdf-geometry-contract`
- **Primary contract:** `docs/decisions/0004-pdf-geometry-coordinate-contract.md` (**Proposed**)
- **Acceptance criterion:** Crop, rotation, page sizes, and transforms have **cross-platform golden geometry tests**.

## 1. Assessment boundaries

This handoff covers the PDF-01 **mathematical and test-fixture contract**. It does not claim the Android PDF import/renderer, PDF export, editable annotation UI, asset registration, Swift/PDFKit adapter, or native PDF parsing is implemented. It does not change the portable wire schema or EDT-01/EDT-03 viewport mapping.

The established document invariants remain authoritative: page origin top-left, X right and Y down, one DU = 1/72 inch, finite double-precision page geometry, mixed-size ordered pages, immutable imported PDF backgrounds, and separate editable app-created annotation elements. The original PDF asset is preserved.

The proposal defines a source PDF default-user-space point **S** (unrotated; X right, Y up) and a canonical page-space point **P** (displayed crop and rotation; X right, Y down). The transform **S ↔ P** is distinct from page-to-viewport geometry. Native adapters must identify their API's actual input/output spaces to avoid applying crop/rotation/unit scaling twice.

## 2. Current repository artifacts and evidence

| Artifact / gate | Verified coverage | Evidence status |
|---|---|---|
| `docs/decisions/0004-pdf-geometry-coordinate-contract.md` | Coordinate spaces, effective crop, all quarter-turn forward/inverse formulas, `/UserUnit`, error behavior, persistence boundaries | Committed by developer; **Proposed**, joint review still required |
| `shared/fixtures/pdf/geometry_cases_v1.json` | 8 valid and 4 invalid raw page-metadata cases | Committed by developer |
| `shared/validators/tests/test_pdf_geometry_contract.py` | Four C1 Python test methods | Developer reported success; independently re-executed against the reviewed C1/C3 files on 2026-11-12, 4/4 |
| `android/app/src/test/java/com/notebook/app/data/portable/PdfGeometryFixtureConformanceTest.kt` | Four C2 Kotlin/JVM test methods against same C1 golden values | Developer reported targeted and full JVM suite success; not run in this assessment environment |
| `shared/fixtures/pdf/metadata_cases_v1.json` | 7 valid and 14 invalid synthetic root-to-leaf metadata/annotation cases | Committed by developer |
| `shared/validators/tests/test_pdf_metadata_geometry.py` | Four C3 Python test methods | Developer reported success; independently re-executed against the reviewed C1/C3 files on 2026-11-12, 4/4 |
| `android/app/src/test/java/com/notebook/app/data/portable/PdfMetadataGeometryConformanceTest.kt` | Four C3 Kotlin/JVM test methods against same C3 golden values | Developer reported targeted and full JVM suite success; not run in this assessment environment |
| Shared Python regression suite | All tests | Developer reported pass for C1–C3; full repo suite not re-executed in this assessment environment |
| Native Android PDF parsing/rendering alignment | None in PDF-01 C1–C3 | **Not verified** |
| Apple Swift/PDFKit fixture consumer and native adapter | None in PDF-01 C1–C3 | **Not implemented/verified** |

**Important:** C1–C3 fixtures contain *metadata objects and expected coordinates*, not a PDF file that exercises parsing inherited `/MediaBox`, `/CropBox`, `/Rotate`, or `/UserUnit` from actual PDF bytes. An existing FMT-03 ZIP has a simple embedded PDF and an editable annotation, but does not verify rotated/cropped native overlay alignment.

## 3. Acceptance matrix

| Contract behavior | C1 | C2 Kotlin | C3 Python/Kotlin | Native Android | Swift/PDFKit |
|---|---|---|---|---|---|
| 0°/90°/180°/270° map and inverse | Golden values tested | Golden values tested | Further normalized rotations | Pending | Pending |
| Effective crop, nonzero and negative origins | Golden values tested | Golden values tested | Inherited/intersected crop cases | Pending | Pending |
| Display width/height after rotation and `/UserUnit` | Golden values tested | Golden values tested | Inheritance and page-local scaling | Pending | Pending |
| Page-tree metadata inheritance/override | Not modeled | Not modeled | Synthetic root-to-leaf snapshots | Pending parsing | Pending parsing |
| Annotation point/rectangle corners/bounds | Point samples | Point samples | Golden rectangle corners/bounds | Pending overlay rendering | Pending overlay rendering |
| Invalid box, rotation, scale, overflow/underflow | Four invalid cases | Four invalid cases | Fourteen additional invalid cases | Pending parser/API failures | Pending parser/API failures |
| Same fixture executed in the native platform adapter | No | No (JVM test-only) | No (JVM test-only) | Pending | Pending |

The 15 valid and 18 invalid cases are catalog entries, not evidence that 33 separate app-level import tests ran. The new test code is **eight Python and eight Kotlin JUnit test methods**. A passing fixture consumer is not equivalent to a production integration test.

## 4. Contract decisions needing joint review

1. **Crop semantics:** Use `MediaBox ∩ (CropBox or MediaBox)` as the effective visible rectangle. Confirm normalization of absent, inherited, and invalid boxes and how native APIs present those bounds.
2. **Rotation:** Confirm clockwise page-display rotation relative to *unrotated default user space*, including `0`, `90`, `180`, `270`, negative multiples and multiples beyond `360`; confirm behavior for malformed/native-unsupported rotation metadata.
3. **`/UserUnit`:** The value is page-local and defaults to `1`. The PDF specification allows an implementation-dependent supported range. Agree on supported magnitude, numerical-stability policy, and explicit failure behavior; do not silently reset an unsupported value to 1.
4. **Numerics:** Review absolute/relative tolerance (`max(1e-9 DU, 1e-12 × |expected|)` for current fixtures), finite input/output requirements, overflow/underflow detection, and practical document-size bounds. Tests explicitly reject some extreme finite inputs; the general support policy is not frozen.
5. **Persistence consistency:** Keep `pdfPage` (`assetId`, zero-based `pageIndex`, `displayBox: crop`) and canonical `page.size`; derive conversion from immutable original PDF metadata. Specify where an importer verifies stored size against derived size; do not add redundant authoritative fields without separate review.

Further concerns to track (not implicit C1–C3 acceptance): differences in permitted integer rotation magnitude across language runtimes; actual PDF parser metadata normalization; password/encryption behavior; source annotation appearance; bitmap clipping and export alignment.

## 5. Native adapter handoff: required semantics

For each source page selected for import or rendering:

1. Preserve the immutable source PDF asset and identify the zero-based source page index. Validate that the page exists when a PDF parser becomes available.
2. Extract **unrotated PDF default-user-space** page metadata from source bytes. Resolve inheritable `/MediaBox`, `/CropBox`, `/Rotate` from the actual page tree and read `/UserUnit` **only from the leaf page**.
3. Resolve the effective crop and display rotation under ADR-0004. Reject unsupported, malformed, numerically unsafe, or unresolvable metadata with an actionable error, without mutating a note.
4. Produce canonical page width/height in DU, plus explicit source-to-page and inverse coordinate transformations. Compare the derived dimensions with persisted `page.size` using the reviewed precision policy.
5. Render the PDF background using a native API transform that produces the *same* displayed crop and rotation; do not apply conversion again to app-created canonical annotations. Preserve page-local clipping and page ownership.
6. Map native API point coordinates through an explicit named source-space adapter. Do not assume PDFKit, Android PdfRenderer, or a canvas/view coordinate already uses PDF default user space.
7. Test overlay anchors and rectangle corners after rendering and after export when those features are built. Record operating system, API/SDK version, rendering backend and whether the check was numerical, pixel/visual, or interactive.

**Android follow-up (PDF-03):** Native source extraction, rendering, original-asset lifecycle, import safety, overlay alignment, and later export testing. PDF-01 does **not** authorize edits to EDT-14 selection or STO-08 asset ownership.

### STO-08 integration boundary

PDF-01 defines only canonical PDF geometry and test fixtures.
It does not implement or authorize asset acquisition, registration,
ownership mutation, export source snapshots, or cleanup.

Future PDF-backed page creation must preserve the immutable source
asset and atomically maintain the canonical `pdfPage.assetId`
reference and its STO-08 page ownership projection. The asset must
be verified and finalized before its durable reference is committed.

Future PDF export must use the STO-08-approved protected snapshot
protocol, including its shared lease-before-Room lock ordering and
independently verified source copies. PDF geometry calculations
must not introduce a separate unprotected asset-reading pathway.

The STO-08 ownership-completeness gate remains conservative.
Neither PDF geometry fixture success nor export snapshot success
authorizes garbage collection or asset deletion.

Reference:
docs/architecture/sto_08_c3a1_export_protection_design_v0_1.md

**Apple follow-up (future Apple PDF adapter):** Implement a Swift consumer that reads **the same committed JSON fixture catalog** and asserts page size, crop, rotation, source-to-page, page-to-source, corner order, bounds, and reject cases. Then separately verify PDFKit extraction/rendering using synthetic PDF files with actual inherited boxes/rotation; document any API-coordinates normalization.

## 6. Remaining gate and disposition

**Current defensible result:** *Shared geometry specification and Python/Kotlin test-only golden conformance ready for joint review*. Mathematical fixture coverage is broad; platform integration and Apple runtime conformance are not demonstrated.

**Issue #57:** Keep **OPEN** until the agreed meaning of “cross-platform golden geometry tests” is satisfied with actual Apple execution and any other jointly approved acceptance requirements. A foundation PR may be merged separately **without** using `Fixes #57`/`Closes #57`.

**Recommended immediate sequencing:**

- D1: Land this verification matrix/handoff document after validating its references and peer review scope.
- D2: Jointly review ADR-0004's five open decisions. Amend ADR/tests with an independently reviewable commit if necessary. Keep ADR *Proposed* until explicit joint approval.
- D3: Prepare a narrowly scoped PR targeting `main` for the contract and fixtures, documenting test evidence and pending Apple/native gaps. Use `Refs #57`, not an auto-close keyword. Request the other developer's review and await CI.
- Follow-up: Reuse the shared catalog with the future Swift/PDFKit adapter and any native PDF metadata/overlay tests. Update the verification matrix with executed evidence; close #57 only after acceptance is genuinely met.

## 7. Local verification commands and PR evidence

Execute from `C:\Users\ilmih\Desktop\develop\nb-pdf-01` (on the PDF-01 branch). The first group requires Python 3; the second requires the existing Android toolchain. No emulator is required for the *test-only geometry* checkpoint.

```powershell
py -3 -m unittest discover -s shared/validators/tests -v

Set-Location .\android
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:lintDebug --console=plain
Set-Location ..

git diff --check
git status --short --branch
git log --oneline origin/main..HEAD
```

For PR evidence, record commands, execution outcomes, test counts, any Gradle cache-only results, and the exact commit(s) reviewed. Do not claim physical-device, Apple, or native PDF rendering tests ran from these commands. Do not push to `main` or change the PDF-01 branch's upstream to `origin/main`.

## 8. References

- `docs/decisions/0004-pdf-geometry-coordinate-contract.md`
- `docs/architecture/notebook_document_model_spec_v0_2.md`
- `docs/architecture/edt_01_viewport_gesture_contract_v0_1.md`
- `docs/development/android-verification-matrix.md`
- `shared/fixtures/README.md`
- `shared/fixtures/pdf/geometry_cases_v1.json`
- `shared/fixtures/pdf/metadata_cases_v1.json`
- PDF 32000-1:2008, sections 7.7.3.3, 8.3 and 14.11 (referenced in ADR-0004)
- [iText PDF page `/UserUnit` explanation](https://kb.itextpdf.com/itext/how-to-get-the-userunit-from-a-pdf-file) (implementation-dependent supported range)
