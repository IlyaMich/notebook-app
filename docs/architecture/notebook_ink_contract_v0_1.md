# Notebook App — Shared Ink Conversion and Brush Contract V0.1 (FMT-04)

**Date:** 2026-10-30  
**Issue:** [FMT-04 — Define common stroke conversion and brush subset](https://github.com/IlyaMich/notebook-app/issues/14)  
**Milestone:** M2 — Editable Ink Vertical Slice  
**Ownership:** Joint (Android and Apple developers)  
**Status:** Team-approved direction; fixtures and validation tests verified locally; pending peer review and merge.  
**Scope:** Platform-neutral stroke semantics, adapter boundaries, failure behavior, and evaluation gates. This document does **not** select a production handwriting engine or freeze the portable wire schema.

## 1. Purpose and relationship to existing contracts

FMT-04 defines the smallest loss-aware, interoperable handwriting representation and a means to evaluate native capture/rendering without coupling saved notes to one SDK. It refines, and does not replace:

- [`notebook_document_model_spec_v0_2.md`](notebook_document_model_spec_v0_2.md), especially §§2, 4–6, 8–10 and 12–13.
- [`shared/schemas/notebook_schema_v1_draft.json`](../../shared/schemas/notebook_schema_v1_draft.json), especially `strokePoint`, `strokePayload`, `brush`, and the stroke element envelope.
- Existing format validators and synthetic `.notebook` fixtures under `shared/`.

**Decision:** Retain the existing canonical `sampledPointsV1` structure and the V1 brush subset (`pen`, `pencil`, `highlighter`). Native engines are replaceable capture/rendering adapters. Canonical elements—not Jetpack Ink objects, PencilKit objects, native meshes, or raster images—are authoritative for edits and portable storage.

**Not established:** That Jetpack Ink and PencilKit can reproduce each other's appearance, sample timing, pressure response, smoothing, or textured brushes within an acceptable perceptual tolerance. These questions require EDT-05/EDT-04 evidence and eventual two-platform round trips.

## 2. Normative canonical representation

A stroke is an ordered, page-owned `type: "stroke"` element with the *existing* common envelope. The `payload` schema is unchanged:

```json
{
  "id": "27c51d88-7ba1-47df-a413-1edbf6d7ca14",
  "type": "stroke",
  "transform": [1, 0, 0, 1, 0, 0],
  "locked": false,
  "payload": {
    "encoding": "sampledPointsV1",
    "brush": {
      "kind": "pen",
      "width": 2.5,
      "color": "#2255AA",
      "opacity": 1.0
    },
    "points": [
      {"x": 80.0, "y": 110.0, "tMs": 0, "pressure": 0.45},
      {"x": 99.0, "y": 100.0, "tMs": 18, "pressure": 0.65},
      {"x": 119.0, "y": 113.0, "tMs": 36, "pressure": 0.52}
    ]
  }
}
```

The containing page is authoritative for ownership; page and element IDs, explicit element ordering, `locked`, and the six-number element transform follow the existing document model. Newly captured strokes begin with the identity transform. Future moves/transforms update the envelope transform rather than destructively changing native screen coordinates in the samples.

| Field | Required | Contract |
|---|---|---|
| `encoding` | Yes | Exactly `sampledPointsV1` for the supported V1 representation. |
| `points` | Yes | Ordered array with at least one sample. No silent sorting, smoothing, decimation or reordering of stored samples. |
| `x`, `y` | Per point | Finite numeric values in *element-local* document units. They initially coincide with the owning page's coordinate system. |
| `tMs` | Per point | Nonnegative integer milliseconds relative to stroke start, nondecreasing (equal timestamps allowed). Newly captured strokes start at `0`; imported valid strokes with another starting offset need not be rewritten. |
| `pressure` | Optional | Normalized `[0,1]`, when the input source supplies a meaningful mapped value; do not invent a saved value merely to fill an omission. |
| `tiltRad` | Optional | Finite `[0, π/2]`, angle of stylus deviation from the page normal: `0` means perpendicular to the page. |
| `orientationRad` | Optional | Finite radians describing tilt direction in page coordinates. The canonical reference is +X pointing right; positive angles rotate toward +Y (downward on the page). Native azimuth/axis conventions must be explicitly transformed. This definition is a **new semantic clarification**, not a JSON Schema change. |
| `brush.kind` | Yes | Exactly `pen`, `pencil`, or `highlighter`. |
| `brush.width` | Yes | Finite, positive width in document units, independent of zoom or screen density. |
| `brush.color` | Yes | Existing uppercase/lowercase-permitted `#RRGGBB` sRGB semantic value, with no implicit alpha channel. Preserve the value through round trips. |
| `brush.opacity` | Yes | Finite normalized `[0,1]`, independent of color. |

A document unit (DU) corresponds to 1/72 inch in the existing model. Page origin is top-left; X increases right, Y increases down. The model uses finite double-precision numbers; reject NaN/infinity. Visibility is clipped to the owning page in both wet and dry rendering. Keep canonical samples and element transforms independent of viewport scale, pan, screen density, and device rotation.

**Rendering fallback:** An absent pressure is interpreted as `1.0` *for rendering only*. The omitted source field remains omitted in canonical data. Optional tilt/orientation fields should likewise remain absent if a device cannot supply a trustworthy value; renderer-specific defaults are ephemeral.

**Timing and prediction:** A new stroke uses a monotonic elapsed-time source, normalizing its first committed input to `tMs = 0`. Native predicted points may be displayed for low-latency wet ink, but are not persisted unless subsequently received as confirmed input. Native coalesced confirmed samples are eligible for persistence. Do not write database updates on each sample.

## 3. Portable coordinates and native conversion

The input pipeline is logically:

```text
Device pointer events / native stroke samples
  -> gesture ownership and page ID
  -> inverse viewport/page transform (screen PX -> page DU)
  -> confirmed canonical input samples (element-local DU)
  -> finished canonical stroke element
  -> existing editor command / transactional document persistence
```

For rendering, use the reverse transform *only in memory*:

```text
Canonical stroke + element transform
  -> replaceable native dry-ink renderer
  -> page clipping + current zoom/pan
```

Requirements:

1. Resolve the owning page once for the gesture using existing viewport hit-testing and a stable page ID; an input event crossing the visual gutter must not silently transfer ownership.
2. Snapshot or otherwise correctly manage the coordinate transform for a live stroke. A concurrent change in pan/zoom must not rebase already captured canonical points accidentally; cancellation is preferable to corrupting geometry.
3. Convert local native coordinates to document-unit coordinates before building persistent elements. Do not round or quantize canonical values merely to satisfy a renderer's floating-point API.
4. Use canonical data as the source of truth for undo/redo, erase, selection, save/reopen and export. Native stroke caches and generated meshes are disposable.
5. When opening and displaying an existing stroke, keep the original canonical data intact. Rendering it via a native engine must **not** rewrite or resample the persisted element on the next save. Only an explicit editing command can change canonical content.
6. Cancelled/palm-rejected/interrupted strokes must not be committed as completed elements unless the interaction policy explicitly classifies them as finished; cancellation handling must be verified on hardware.

### 3.1 Native adapter seam (conceptual, not an API signature)

A platform-specific adapter should own **input collection**, **wet preview**, **canonical conversion**, and **dry rendering or rebuilding**; it must not own portable file format decisions, note revision policy or Room/GRDB transactions.

| Boundary | Input / output | Owner |
|---|---|---|
| Capture | Pointer/sample events + page targeting/transform → confirmed samples and live preview | Native ink adapter, coordinated with editor viewport |
| Canonicalize | Confirmed native samples + semantic brush → validated `sampledPointsV1` element | Adapter conversion layer |
| Persist | Completed canonical element/command → transaction and revision | Existing document repository/autosave pipeline |
| Rebuild/render | Stored canonical element + viewport transform → native ink preview/dry output | Replaceable native adapter |
| Edit | Select/erase/transform → canonical commands, never direct mutation of a native cache | Editor command layer |

Android EDT-05 should initially evaluate **Jetpack Ink** behind this seam, not expose its SDK types in the shared contract or Room entities. Apple EDT-04 should evaluate **PencilKit** at the equivalent boundary. Neither candidate is hereby selected as the production engine. The precise Kotlin/Swift interfaces must be designed against the actual APIs during those spikes.

## 4. Minimal semantic brush mapping

All native adapters must preserve **brush semantics** and their numerical canonical properties. They need not provide identical textures or fitting algorithms until fidelity tests establish reasonable equivalence.

| Canonical brush | Intended meaning | Native policy | Known risk |
|---|---|---|---|
| `pen` | Opaque or intentionally translucent continuous ink; width/color/opacity from payload | Prefer smooth solid round ink; use supported pressure response when meaningful | Spline fitting and wet/dry curvature can differ |
| `pencil` | Graphite-like, potentially textured stroke | Choose closest available native pencil behavior; preserve samples and metadata even if texture differs | Texture, grain and pressure response are engine-specific |
| `highlighter` | Broad translucent marker stroke | Preserve color, width and opacity; document blend/overlap policy separately | Alpha compositing, repeated overlaps, and stroke ordering may differ |

No fixed SDK brush-family names, native numeric width multipliers or pressure curves are approved in FMT-04 CP1. The EDT-05/EDT-04 evaluations must record the versioned, deterministic mapping used on each platform and whether a chosen renderer can faithfully represent the canonical brush.

**Brush fallback rule:** For a *recognized* canonical brush unsupported natively, show a clearly documented visual approximation if it preserves canonical content and keeps the document editable. The approximation must not change the serialized kind, width, color, opacity, or point data. If edits cannot be preserved losslessly, reject that editing operation or offer a safe read-only path; do not silently flatten.

## 5. Conversion and unsupported-information policy

| Case | Required result |
|---|---|
| Native pressure absent, unavailable, or not calibratable | Leave canonical `pressure` absent; use renderer-only fallback. |
| Native tilt/azimuth absent or semantically ambiguous | Leave the corresponding optional field absent; document the limitation. Do not fabricate precision. |
| Native pressure provided outside canonical range | Apply a *documented, tested* normalization if physically meaningful; otherwise decline that sample/gesture with an explicit error rather than silently clamp away required information. |
| Native smoothing, predicted samples or brush mesh | Treat as live/rendering behavior or disposable cache. Do not use as the sole saved editable representation. |
| Supported stroke decoded but displayed differently | Preserve the canonical payload unchanged; record a fidelity deviation. |
| Unknown `encoding`, `brush.kind`, required field, or incompatible document schema | Fail validation/import or use a non-destructive read-only mode if lossless preservation is possible. Never drop or overwrite unknown content on save. |
| Malformed finite/range/timestamp rules | Reject as invalid rather than silently repairing an imported canonical stroke. |
| Page crossing/outside-bound strokes | Keep ownership page-local, clip visible marks to their page, and do not create unintended second-page elements. |

Do not add an unversioned proprietary metadata field to `strokePayload` to work around native SDK limitations. Any required cross-platform format extension must be jointly reviewed, accompanied by schema/validator/fixture changes and a clear compatibility policy.

## 6. Fidelity tests and provisional numerical budgets

**Two different properties must be measured and reported separately:**

1. **Canonical preservation (mandatory):** Open/save/export and internal native reconstruction must preserve supported IDs, ordering, brush semantics, optional-field presence, timestamps and canonical samples unless an intentional edit occurs. Comparing parsed JSON values (not whitespace or key order) is appropriate. No cumulative drift from repeated open/render/save cycles.
2. **Native capture/render fidelity (experimental):** Compare source events and canonicalized samples, then compare reconstructed wet/dry ink appearance using fixed fixtures and documented device/engine settings. Record geometric and visual differences; do not treat native spline or texture equality as guaranteed.

Initial *candidate* targets to evaluate on ordinary A4/Letter-size page fixtures:

| Metric | Proposed CP2/EDT-05 target | Caveat |
|---|---|---|
| Document geometry after converting canonical Double → native Float → Double | Maximum absolute X/Y discrepancy ≤ **0.01 DU** per point | A measurement target on tested page coordinates, not a universal guarantee for arbitrarily large values. |
| Normalized pressure after an implemented round trip | Absolute discrepancy ≤ **0.005** per present sample | Valid only when the native SDK can represent the sample's pressure semantics. |
| `tMs` after canonical encode/decode | Exact nonnegative integer sequence; no reordering | No wall-clock guarantees. |
| Optional fields | Exact presence/absence in persisted data | Native *rendering* may not support all characteristics. |
| Color, opacity, brush kind and semantic width | Exact canonical values after unedited save/reopen | Do not conflate with visually identical output. |
| Native stroke silhouette / overlap / pressure-dependent appearance | **Record and compare; threshold deferred** | Establish meaningful visual/latency gates from EDT-05 measurements, then jointly review with Apple evidence. |

Record input source (stylus/finger/mouse), emulator versus physical device, OS/API version, SDK version, brush, page size, zoom, sample count, reference/test coordinates, error metric, wet-to-dry transition, CPU/memory/latency methodology, and any screenshots/recordings. **Emulator verification does not establish palm-rejection or real stylus latency.**

A tolerance failure in a conversion test is a finding, not a license to rewrite stored coordinates or relax the schema silently. Investigate double/float precision, rounding, transform composition and point fitting first.

## 7. Golden-fixture and validation plan (FMT-04 CP2/CP3)

All fixtures must contain **synthetic** data and use the existing `.notebook` package format and validator. Add these fixtures incrementally after CP1 review, rather than editing the schema in this checkpoint.

| ID | Fixture / assertion | Expected |
|---|---|---|
| INK-01 | Straight pen, constant pressure | Valid; exact canonical semantic round trip |
| INK-02 | Curved pen, varied pressure and repeated valid timestamps | Valid; preserve order and values |
| INK-03 | Pencil with optional tilt and orientation | Valid; canonical field preservation |
| INK-04 | Translucent highlighter | Valid; preserve kind/color/width/opacity |
| INK-05 | One-point stroke | Valid, renders as a mark where supported |
| INK-06 | Pressure/tilt/orientation all omitted | Valid; optional fields stay absent |
| INK-07 | Decreasing timestamps or negative `tMs` | Invalid |
| INK-08 | NaN/infinity, nonpositive brush width, out-of-range pressure or tilt | Invalid |
| INK-09 | Unknown encoding or brush | Unsupported/invalid; no destructive rewrite |
| INK-10 | Repeated canonical read/write with no edit at different zooms | No changed page-local samples, IDs, transform or order |

For fixture validation, test both schema shape and existing *semantic* constraints (especially finiteness and monotonicity). Add tests rather than modifying the format unless those tests demonstrate a real gap. Platform adapters should consume the same fixture vectors when they are implemented.

## 8. Explicit deferrals and decision triggers

Deferred to **EDT-05** (Android) and **EDT-04** (Apple): exact native API calls and brush families, pressure calibration, tilt/azimuth source conventions, spline/sample reconstruction details, wet/dry rendering ownership, gesture arbitration, predicted-point implementation, actual latency and fidelity measurements, and real stylus hardware validation.

Deferred to joint cross-platform tests: acceptable perceptual difference, brush interpolation agreement, texture/alpha overlap, repeated iPad → Android → iPad and reverse round trips, and any justified `sampledPointsV2` / versioned extension decision.

**Reconsider the contract** if either native adapter cannot recover required canonical fields, cannot preserve unedited imported content, introduces systematic geometry drift, or fails jointly agreed fidelity thresholds. An architecture decision record and compatible schema/fixture plan are required before a breaking change.

## 9. FMT-04 completion criteria and review record

- [x] The two developers have agreed in discussion to retain `sampledPointsV1`, the three canonical brushes, loss-aware compatibility behavior, and replaceable native adapters.
- [ ] Both developers review this written CP1 contract on the repository branch and confirm its new clarifications (notably tilt/orientation direction and unsupported-native-value treatment).
- [ ] CP2 adds synthetic valid/invalid golden fixtures and documents expected semantic outcomes.
- [ ] CP3 adds automated schema/validator regression tests and verifies existing valid/invalid packages remain unchanged in outcome.
- [ ] Reviewed PR passes applicable CI and is squash-merged; update the issue/ADR record to reflect the approved baseline.

No engine integration, schema migration, Room changes, or production UI changes are required to complete CP1. Completion of CP1 alone does **not** complete the FMT-04 issue or unblock EDT-05 until the agreed issue acceptance evidence is reviewed.
