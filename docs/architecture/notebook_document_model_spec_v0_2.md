# Notebook App - Document Model Specification V0.2

**Date:** 2026-10-21  
**Status:** Working draft for implementation planning; **not** a frozen production wire contract  
**Replaces:** `notebook_document_model_spec_v0_1.md`  
**Companion artifacts:** `notebook_schema_v1_draft.json`, `notebook_sample_v0_2.notebook`  
**Platforms:** iPadOS/iOS (Swift/SwiftUI + native ink adapter); Android tablets/phones (Kotlin/Jetpack Compose + native ink adapter)

> **Version distinction:** `V0.2` is the version of this planning document. `schemaVersion: 1` is the proposed initial version of the portable JSON format. During the prototype stage the wire schema can still change. Once a production format ships, any incompatible change requires a migration/version policy.

## 1. Purpose and scope

Provide a platform-independent document contract for a note-taking app with editable handwritten ink, basic shapes, basic styled text boxes, images, and annotations on PDF-backed pages. Documents must survive save/reopen, import/export, and cross-platform read/write without losing the editable object model.

**Confirmed V1 product behavior**

- Primary target: iPad and Apple Pencil. Also Android stylus tablets and Android phones, with finger drawing as an Android phone interaction mode.
- Native app implementations: Swift/SwiftUI plus UIKit/PencilKit integration; Kotlin/Jetpack Compose plus Jetpack Ink integration.
- Fixed-size, individually addressable pages with **continuous vertical scrolling** and free zoom/pan. All visible marks are clipped to their page bounds.
- User-selectable A4 or US Letter in portrait/landscape; templates: blank, ruled, grid, dotted. Model allows heterogeneous page sizes (including PDF pages). Individual-page manual resizing is **not** a V1 UI feature.
- Editable content: pen/pencil/highlighter strokes, line/rectangle/ellipse/arrow shapes, basic styled text boxes, images. Imported PDF pages act as immutable backgrounds; new annotations above them are editable elements.
- Single unified lasso can select mixed editable types. **V1 lasso selects within one page**; between-page transfer uses cut/paste. Future cross-page selection must not require a persisted schema redesign.
- Partial-stroke and whole-stroke erasers, with **partial-stroke as default**.
- Undo/redo applies to the active editing session only; saved document edits persist after closing the editor but the undo stack does not.
- Local-first/autosave. Cloud synchronization, audio capture, rich text spans, and collaborative editing are deferred.

## 2. Principles, invariants, and boundaries

1. **Canonical content is cross-platform.** Native PencilKit/Jetpack Ink byte blobs or meshes are never the only persisted editable representation.
2. **Ownership is page-local.** Each element is contained in exactly one page. Element identifiers are unique within a note. An element reference can always be represented as `(pageId, elementId)` for future multi-page interactions.
3. **Page geometry is independent of view state.** No zoom, scroll offset, screen density, or orientation is encoded in element coordinates.
4. **Drawing order is explicit.** Pages are ordered by `manifest.pages[]`. Elements are ordered by `page.elements[]` from back to front; no independent `zIndex` is stored in V0.2.
5. **Geometry remains editable.** Pages are not saved as flat bitmaps. Flattening for PDF/print export does not replace canonical objects.
6. **Compatibility failures are explicit.** Unknown required versions or object types cannot be silently discarded. Open read-only or decline editing when lossless preservation is not possible.
7. **Mutations are atomic and undoable.** A completed gesture or logical action is one command; no partially applied mixed-object transaction is considered committed.
8. **Canonical files never trust imported paths/assets.** All references are validated before editing an imported document.

## 3. Data entities

```text
Library metadata (local app database, separate from portable note)
  ├── Notebooks / folders
  └── Note index / search index

Portable Note (.notebook archive)
  ├── manifest.json
  │    ├── schemaVersion, note id/title/timestamps
  │    ├── ordered page references
  │    └── asset registry
  ├── pages/<page-id>.json
  │    ├── page id, size, background
  │    └── ordered elements (stroke, shape, text, image)
  └── assets/<sha256>.<png|jpg|pdf>
```

Notebook/folder location is *library organizational state*, not part of the portable page geometry. Exporting and importing a note must not require a source notebook to exist on the destination device.

### 3.1 Note manifest

| Field | Type | Contract |
|---|---|---|
| `schemaVersion` | integer | Starts at `1` in draft |
| `id` | UUID string | Stable note identity; local importer defines collision policy |
| `title` | non-empty string | User-readable note title |
| `createdAt`, `updatedAt` | RFC3339/ISO8601 UTC strings | Durable metadata |
| `pages` | array of `{id, path}` | Ordered; at least one page; unique IDs |
| `assets` | array of asset records | Registry of embedded PDF/image bytes |

An asset record contains `id`, `path`, `mimeType`, `sha256`, and `byteLength`. V0.2 supports PNG, JPEG, and PDF assets. No arbitrary filesystem paths or network URLs appear in the canonical archive. Asset paths are relative to the package root.

### 3.2 Page

| Field | Type | Contract |
|---|---|---|
| `id` | UUID | Equal to the manifest reference ID |
| `size` | `{width, height}` | Positive double-precision values in document units |
| `background` | tagged union | `blank`, `ruled`, `grid`, `dotted`, or `pdfPage` |
| `elements` | ordered array | Back-to-front order; unique element IDs within note |

The page list can contain mixed dimensions: e.g., A4 portrait followed by PDF pages with original media/crop sizes or a landscape page.

### 3.3 Background variants

- `blank`: `{ "type": "blank", "color": "#FFFFFF" }`.
- `ruled | grid | dotted`: `{ "type": "ruled", "color": "#FFFFFF", "patternColor": "#D8E7F7", "spacing": 24 }`. Spacing is in page units; background patterns do **not** enter lasso selection.
- `pdfPage`: `{ "type": "pdfPage", "assetId": "<uuid>", "pageIndex": 0, "displayBox": "crop" }`. `pageIndex` is zero-based. A source PDF may already contain its own annotations; those remain part of the immutable PDF background in V1, while app-created annotations are separate elements above it.

For PDF backgrounds, page size is the **displayed width and height** in page units after applying crop box and PDF page rotation. The adapter must consistently map source PDF coordinates to the top-left page coordinate space, including crop offsets and orientation. Do not discard the original PDF bytes.

## 4. Coordinate systems and precision

- Page origin at upper-left; X rightwards, Y downwards.
- One document unit corresponds to 1/72 inch for standard paper dimensions and PDF output (not to a physical on-screen pixel).
- Use finite IEEE 754 double values in serialization and calculations; reject NaN/infinity, invalid sizes, and singular element transforms.
- Common page dimensions: **A4 portrait approximately `595.276 × 841.890`**; **Letter portrait `612 × 792`**; landscape reverses these values.
- Viewport coordinates and page layout offsets are ephemeral. The viewport first determines which page receives input, then applies the inverse of the view transform to obtain coordinates in that page.
- PDFKit often uses lower-left page coordinates; adapt by explicit rotation/crop/axis transforms. Apple documents PDFKit page-space orientation and page box/rotation behavior; do not assume a simple global Y-flip covers all PDF files.

**Element transform:** store `[a, b, c, d, tx, ty]` with column-vector convention:

```text
x' = a*x + c*y + tx
 y' = b*x + d*y + ty
```

Element payload geometry is specified in its local coordinate space, which initially coincides with the page coordinate system. The element's affine transform maps that geometry into page space. `identity = [1,0,0,1,0,0]`. A page-space drag transform `M` updates an existing element transform `T` as `T' = M * T`. Hit-testing applies the inverse of `T` before testing the local geometry.

**Boundary policy:** no visible ink or object drawing outside the page rectangle. All renderers clip every page to `[0,width] × [0,height]`; live-stroke preview is clipped too. Creation/edit tools should constrain the *visible footprint* of movable/resizable content to the page where possible. For imported or previously transformed content, clipping remains the final safety rule. Any hidden portion must not be interpreted as a second-page object.

## 5. Common element envelope

Every saved element has:

```json
{
  "id": "<UUID>",
  "type": "stroke | shape | text | image",
  "transform": [1, 0, 0, 1, 0, 0],
  "locked": false,
  "payload": {}
}
```

- The *containing page* is authoritative for ownership. Local SQLite may additionally store `page_id` as a foreign key.
- Bounds are **derived** from the payload's painted geometry and its transform, then optionally cached. No second authoritative `bounds` field is persisted.
- `locked` is reserved for future UI controls. Locked elements are rendered but not modified by editing gestures. V1 tools should not silently change them.
- New element types require documented import/export behavior and version management. An unknown type in an imported document must not be dropped during a save.

## 6. Exact V0.2 element payloads

### 6.1 Stroke

```json
{
  "encoding": "sampledPointsV1",
  "brush": {"kind":"pen", "width":2.5, "color":"#2255AA", "opacity":1.0},
  "points": [
    {"x":80, "y":110, "tMs":0, "pressure":0.45},
    {"x":99, "y":100, "tMs":18, "pressure":0.65},
    {"x":119, "y":113, "tMs":36, "pressure":0.52}
  ]
}
```

- `points[]`: at least one sample; monotonically nondecreasing relative `tMs` (milliseconds from stroke start).
- `x`, `y`: local geometry; `pressure` optional in `[0,1]`; optional `tiltRad` in `[0, π/2]` and `orientationRad` in radians. Missing pressure must have a defined fallback (proposal: `1.0`, to be verified visually).
- `brush.kind`: `pen`, `pencil`, or `highlighter`; `width` is a positive document-unit value; `color` uses `#RRGGBB` in sRGB; `opacity` in `[0,1]`.
- The app's standard brush mapping must be deterministic and consistent *enough* across platforms. Texture, pressure response, and stroke fitting are **not yet guaranteed pixel-identical**. Define an ink-fidelity acceptance threshold after prototype measurements.
- All erasing, selection, transformation, and hit-testing of a stroke operate on the canonical geometry or a geometry derived from it, not a disposable native rendering cache.

**Risk - pencil input fidelity:** PencilKit exposes B-spline `PKStrokePath` and sampled point APIs; Jetpack Ink strokes include pointer input and generated geometry. Converting to `sampledPointsV1` may change curved stroke appearance and textured brush effects. The portable encoding remains **provisional** until iPad→Android→iPad and Android→iPad→Android tests pass. Keep native capture data only as optional disposable cache, never as a required canonical dependency.

### 6.2 Shape

```json
{
  "kind": "arrow",
  "geometry": {"x1":170, "y1":160, "x2":250, "y2":110},
  "stroke": {"color":"#008F70", "width":3, "opacity":1},
  "fill": null,
  "arrowHead": {"length":12, "angleDeg":30}
}
```

- `line` and `arrow`: geometry `{x1, y1, x2, y2}`.
- `rectangle` and `ellipse`: geometry `{x, y, width, height}` with positive width/height.
- `stroke` has color/width/opacity; `fill` is either null or `{color,opacity}`. For `arrow`, `arrowHead` specifies tip appearance.
- Shapes remain parameterized and editable: never replace shape geometry with a rasterized screenshot.
- Rotations are represented using the common element transform; no separate shape-specific rotation parameter.

### 6.3 Text box

```json
{
  "text": "F = ma",
  "frame": {"x":80, "y":190, "width":165, "height":42},
  "style": {
    "fontFamily":"sans", "fontSize":20, "color":"#202020",
    "bold":true, "italic":false, "underline":false, "alignment":"left"
  }
}
```

- Text is a Unicode string. Font/style settings apply to the **entire box** in V1; no inline rich-text spans.
- `fontFamily` is a semantic family (`sans`, `serif`, `mono`) to avoid platform-specific installed font requirements. Each platform maps these to suitable fonts.
- Frame is defined in page units; `fontSize` is also in document units.
- For single-object resize, frame resizing and text reflow is allowed. For proportional **mixed-object** resize, scale the frame and text size together to preserve the group's visual proportions. A mixed transform is one undo transaction.
- Identical text content and styles are required on both platforms; exact glyph metrics and line-break identity are *not* guaranteed until cross-platform font and layout tests establish a compatible policy. Avoid silent truncation.

### 6.4 Image

```json
{
  "assetId": "<UUID>",
  "frame": {"x":280, "y":160, "width":80, "height":53.333},
  "opacity": 1.0
}
```

- `assetId` references an embedded image in the manifest registry.
- Optional `crop` uses a normalized source-image rectangle `{x,y,width,height}` with all components in `[0,1]`; **semantic validation** additionally requires `x+width <= 1` and `y+height <= 1`.
- Content remains an independent element. The app may cache a resized bitmap for rendering, but the original asset remains available for re-editing and export.

## 7. Interaction, selection, and transformations

### 7.1 Selection state (not saved to document)

Use a page-aware selection reference *now*, even though the V1 UI limits selection to one page:

```text
SelectionState
  scope: page
  activePageId: UUID?
  selectedRefs: Set<(pageId, elementId)>
  gesture: idle | lasso | moving | resizing
```

This permits future `scope: document` without changing any persistent content structures. In V1, a lasso starts on one page and can select editable content only from that page. The PDF background and page patterns are not eligible. Whole-object tap selection uses the same selection engine. Locked elements do not participate in destructive transformations.

Geometry hit-testing uses a fast bounding-box candidate pass followed by accurate intersection as needed: stroke painted segments, shape path/filled interior, transformed text frame, and transformed image frame. Lasso semantics for V1 are **intersects**, not only fully-enclosed. Text boxes/images are selected when their transformed visual frame intersects the region.

### 7.2 Commands

All committed mutations go through an editor command service. Proposed commands:

| Command | Effect / inverse |
|---|---|
| `AddElements(pageId, elements)` | Insert elements; inverse removes them |
| `RemoveElements(pageId, ids)` | Remove with original positions/snapshots; inverse restores |
| `ReplaceElements(pageId, before[], after[])` | Atomic modifications, including partial erase; inverse swaps before/after |
| `ReorderElements(pageId, ids, positions)` | Change visual stacking; inverse restores order |
| `AddPage`, `RemovePage`, `ReorderPages` | Reversible structural edits |
| `ChangePageBackground` | Replace background; inverse restores |
| `TransferElements(sourcePageId, destPageId, ids, newGeometry)` | Atomically re-home items on cut/paste; preserve IDs for a true move |

A single stylus stroke or shape insertion is one operation; a drag or mixed-object resize is one operation even if it produces hundreds of input updates. Undo/redo is in-memory/session-only: closing the editing session clears both stacks, but never reverts committed saved content.

**ID rules:** A copy/paste produces new IDs (and new image asset references only when necessary; content-addressed assets may be reused). A move/cut-and-paste in the same document preserves the element IDs and updates ownership atomically. On import-as-new, generate a fresh note ID and handle any locally colliding asset/page/element IDs deterministically, preserving references. Undo must restore the original serialized elements and ordering, not an approximate reconstruction.

### 7.3 Transform operations

For a selection, compute the group painted bounds in page space. While the user drags/resizes, preview a page-space transform; on release, apply it to every selected element in a single `ReplaceElements` transaction. Mixed-object scaling preserves aspect ratio by default. Element-specific resize handles can use different behavior (e.g., text reflow or image cropping) but these are separate editing modes.

V1 does **not** offer cross-page lasso or direct dragging across page boundaries. A future version can add multi-page selection and a `TransferElements` gesture coordinator using the same element IDs and page-local coordinates.

## 8. Erasing and stroke splitting

### 8.1 Whole-stroke eraser

Sweeping the eraser over any painted part of a stroke removes that entire element. One continuous eraser gesture is grouped into one undoable command; it can remove multiple strokes.

### 8.2 Partial-stroke eraser (V1 default)

The eraser gesture describes a continuous swept region in **page coordinates**; the selected stroke's painted geometry is intersected against it. Each continuous retained piece becomes its own stroke object. The old stroke is deleted and its surviving segments receive **new element IDs**. Splitting must interpolate boundary-point geometry and available pressure/timestamp data rather than simply deleting nearest sampled points; otherwise the cut edge will jump. The new segments retain original brush style and their relative render order.

A stroke with no remaining visible segment is removed. Degenerate fragments below the minimum renderable length may be removed subject to documented tolerance. The entire gesture is one `ReplaceElements` command that records a full snapshot of every original stroke and all replacement segments. Undo restores the exact original strokes and ordering. This also applies when an eraser intersects a transformed stroke: convert eraser coordinates to the stroke's local geometry or equivalently transform the stroke's painted path.

**Important limitation:** partial erasing for V1 applies to freehand strokes. Shapes/text/images use whole-object deletion through selection. An element spanning outside page bounds is still clipped in render and eraser interaction.

## 9. Input, navigation, and render pipeline

```text
Native stylus/finger events
    -> Input adapter & gesture state machine
    -> Convert screen point -> viewport -> page-local point
    -> Temporary live-stroke / lasso / shape overlay (not persisted)
    -> Gesture completes; canonical object/command created
    -> Atomic document edit -> rendering invalidation
    -> Persistence commits in background; UI reflects save status
```

- Two-finger pan/pinch changes only the viewport, never geometry.
- On stylus-capable tablets, stylus writes and fingers navigate by default. Android phones can enable finger drawing. Coordinate arbitration and palm cancellation must be tested in native prototypes.
- Render visible/nearby pages; release or cache offscreen pages. Store asset decode caches separately from the document.
- Do not perform synchronous database writes or serialize whole pages in the per-sample stylus input loop.
- Transform/clip **live preview** consistently with committed ink so marks do not vanish or shift after release.
- PDF backgrounds are rasterized for display as needed at suitable resolution but exported from original PDF page content where feasible.

## 10. Storage and autosave contract

**In-app storage (proposed)**

- SQLite tables for notes, page order, page metadata, elements and ordering, plus references to app-managed PDF/image assets. Use SQLite foreign keys and WAL where appropriate.
- Serialize stroke point arrays efficiently in local storage only after benchmarking; local table layout need not mirror the ZIP package exactly.
- Large asset files are content-addressed by hash and may be shared among note references. Assets must not be deleted while referenced by committed document state.
- Each completed editing command increments a local revision. Saving is queued promptly and committed transactionally without blocking the live input path. Until durable commit, UI may display a pending-save state; do **not** claim a save succeeded merely because an in-memory command completed.
- On failure or app suspension, pending commands must either be durably committed/recoverable or explicitly not reported as saved. The recovery process must preserve the last valid committed revision.
- Import: stage archive and assets, validate, atomically register note and references, then cleanup temporary files. Asset and DB commit ordering must never leave a committed note pointing to nonexistent files.

**Portable package (`.notebook`)**

```text
note.notebook   [ZIP archive]
  manifest.json
  pages/<page-uuid>.json
  assets/<sha256>.png | <sha256>.jpg | <sha256>.pdf
```

- JSON is UTF-8; object ordering is non-semantic; ordered **arrays** are semantic.
- V0.2 prototype uses JSON for sampled strokes. A compact versioned binary format can replace/augment it **only** after benchmarks and migration design.
- Each embedded asset is verified by hash and byte length; every asset/page reference must resolve.
- Importers must defend against traversal (`../`), absolute paths, duplicate archive entries, symlinks, decompression bombs, oversized inputs, malformed PDFs/images, unsupported schema versions, and excessive element/point counts. Operational limits will be tuned empirically.
- Unknown `schemaVersion` or element types: open read-only or reject with a useful error unless round-trip preservation can be guaranteed. Never silently flatten or discard data on resave.
- Portable note export is distinct from PDF export: `.notebook` is editable; PDF is an external presentation format and may be flattened.

## 11. Concrete schema artifacts

`notebook_schema_v1_draft.json` is a JSON Schema (Draft 2020-12):

- Root validates `manifest.json`.
- `#/$defs/page` validates each page file.
- `#/$defs/element` validates the four V1 element envelopes.
- `#/$defs/asset` validates asset records.
- It is intentionally a **draft technical validator**, not a generated Swift/Kotlin model package.

`notebook_sample_v0_2.notebook` is a minimal ZIP example with two pages (A4 portrait ruled; Letter landscape grid), a pen stroke, arrow, text box, and embedded PNG image. It is a **format fixture**, not a working note displayed by the application; rendering will be built later.

### Semantic validations required *in addition* to JSON Schema

1. Manifest page IDs match referenced page-file IDs; unique page, asset, and element IDs within the note.
2. All referenced pages/assets exist exactly once, paths are safe, and content hashes/sizes match.
3. `createdAt <= updatedAt`; any array ordering field is honored.
4. All numeric values are finite; element transforms are invertible; shape geometry matches kind.
5. Stroke timestamps are monotonic; sampled points and brush widths fit operational limits.
6. Normalized image crop fits within the source image; asset MIME signature matches metadata.
7. PDF background uses an actual PDF asset and a valid source page index.
8. Visible geometry and input respect page bounds; editor does not transfer ownership invisibly.

## 12. Cross-platform fixtures and acceptance tests

| ID | Test | Expected result |
|---|---|---|
| F01 | Parse and re-export sample fixture in Swift and Kotlin | Same semantic content; preserved page/element ordering and IDs |
| F02 | Ink round trip iPad -> Android -> iPad and reverse | Editable strokes, acceptable fidelity; no disappearing samples or geometry drift |
| F03 | Mixed lasso: stroke + shape + text + image | All eligible unlocked objects are selected on a single page only |
| F04 | Mixed move/scale -> undo -> redo | Each intermediate operation is one command; exact before/after element snapshots restored |
| F05 | Partial erase across a stroke | Original replaced with retained fragments and new IDs; undo restores exact original |
| F06 | Whole erase across multiple strokes | Removes all hit strokes as one gesture command |
| F07 | Cut/paste between pages; copy/paste within page | Move preserves IDs; copy creates new IDs and keeps ordering valid |
| F08 | Zoom, pan, rotate device, change page layout | Persisted page coordinates unchanged |
| F09 | Write near/outside page edge | Live display and saved/exported result agree; no marks outside visible page |
| F10 | Open/export PDF-backed page including annotation | Source PDF remains intact; app-created objects stay editable in notebook package |
| F11 | Crash during DB edit, asset import, ZIP export | Last valid committed state recovers; no broken asset references |
| F12 | Unknown kind/version, invalid ZIP paths, duplicate files, bad hash | Safe rejection or non-destructive read-only fallback; no silent data loss |
| F13 | Long notes and rapid pen input | No severe frame stalls; memory stays bounded by nearby-page rendering/caches |
| F14 | Styled text and image on different platforms | Text/style and image pixels preserved; differences in font layout documented |

**Performance thresholds** (latency, memory, file size, stylus drop rate) should be captured from the two real target devices during the prototype and then made explicit test gates. Do not declare hard latency guarantees without measurements.

## 13. Implementation contract and platform boundaries

**Shared (format and test contract, not shared runtime library):**

- JSON Schema, test packages, semantic validation rules, geometry/eraser fixtures, brush compatibility descriptions, command behavior, versioning rules.
- Identical ordering and ID rules in both implementations.

**iPadOS app:** SwiftUI navigation/toolbars; a UIKit-based native ink/editor host as needed; PencilKit input adapter; canonical stroke converter; renderer; GRDB/SQLite repository; PDFKit adapter.

**Android app:** Compose screens/toolbars; Jetpack Ink input adapter; canonical stroke converter; renderer; Room/SQLite repository; Android PDF rendering adapter with export strategy validated separately.

The exact renderer arrangement (native ink view for both wet and dry ink vs. native wet ink with custom dry renderer) is **not decided** until a mixed-object prototype passes the round-trip/selection tests. PDF export capabilities and library licensing must be evaluated before committing to a particular Android PDF implementation.

## 14. Technical risks and explicitly deferred work

### Highest-priority risks

1. **Ink fidelity:** PencilKit's spline data and Android Ink's input/mesh semantics differ. Native conversion may lose textured brush appearance or pressure response. If unacceptable, change the canonical curve/brush model or rendering integration *before* production serialization is frozen.
2. **Unified mixed-element selection:** native ink views may compete with custom hit-testing and zoom gestures. Validate interaction boundaries early.
3. **Partially erased strokes:** fragment generation and subsequent round-trip scaling/selection can introduce seams or degenerate geometry. Build deterministic test cases.
4. **PDF imports:** crop boxes, rotation, source annotations, page dimensions, and exported overlay alignment must agree across Apple and Android.
5. **Recovery:** transactional SQLite plus filesystem assets requires coordinated staging; no dangling references after interrupted writes.
6. **Native Apple tooling:** cloud Mac provides Xcode, but remote physical-device debugging and code signing/test distribution require an explicit workflow.

### Deferred product features

Cloud sync, account system, real-time collaboration, audio recording, handwriting recognition/search, arbitrary user-defined element plugins, persistent undo across sessions, rich text spans, direct cross-page lasso/drag, and manually resizing individual pages.

## 15. Next engineering actions and definition of done

1. **Shared schema review:** both developers review every object field, units, IDs, unknown-kind policy, and import/export contract; update schema/fixtures together.
2. **Apple spike:** capture native Apple Pencil strokes, convert to canonical samples, reconstruct and render, perform mixed lasso over an added shape, export package.
3. **Android spike:** import same package, display strokes/shapes/text/image, edit and save; perform reverse round trip.
4. **Erasing/transform spike:** split pressure-varying curved strokes, undo/redo exactly, selection scale at zoom levels, clamp/clip at page edges.
5. **PDF spike:** annotate a cropped/rotated source PDF and compare view/exports on both platforms.
6. **Local save spike:** transactional element edits, image import staging, forced-interruption recovery.
7. **Freeze wire schema only after passing tests** with written acceptance thresholds and documented deviations.

**V0.2 completion criteria:** the draft schema validates the sample package; all V1 editor decisions have corresponding data and command representation; known unproven behavior is explicitly marked. **This does not imply** ink fidelity, rendering equivalence, or crash safety have already been demonstrated on hardware.

## 16. Official technical references used for risk identification

- Apple PencilKit `PKStrokePath`: https://developer.apple.com/documentation/pencilkit/pkstrokepath-swift.struct
- Apple PencilKit `PKStroke`: https://developer.apple.com/documentation/pencilkit/pkstroke-swift.struct
- Android Jetpack Ink stroke modules: https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-modules
- Android Ink zoom/pan state: https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-state-preservation
- Apple PDFKit page bounds/rotation: https://developer.apple.com/documentation/pdfkit/pdfpage/bounds(for:)
