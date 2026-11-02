# EDT-01 — Viewport, Page Layout & Gesture Contract V0.1

**Date:** 2026-10-29  
**Status:** Proposed for joint review; not yet accepted  
**Issue:** [EDT-01 — Specify viewport/page interaction and gestures (#32)](https://github.com/IlyaMich/notebook-app/issues/32)  
**Priority / milestone:** P0 / M2 — Editable ink vertical slice  
**Scope:** Cross-platform behavior contract; implementation follows in EDT-03 (Android) and EDT-02 (Apple)  
**Dependency:** FMT-01 (complete)

## 1. Purpose and scope

Specify continuous vertical pages, page-local clipping, top-left page units, input arbitration, and viewport zoom transforms. The contract enables the Android-first editor without choosing Jetpack Ink, PencilKit, or another handwriting engine. It does **not** replace the FMT-01 coordinates/schema or introduce a new persistence format.

**Authority order:** The merged FMT-01 decisions and portable schema invariants govern persisted document geometry. The V1 PRD and Document Model V0.2 provide product/document behavior; the UI/UX Design System V0.3 provides UI guidance. Where this document makes a new implementation choice, it is marked **Proposed** and requires both developers' review. Do not treat proposed UI defaults from earlier drafts as already confirmed.

**Included:** page layout and navigation, viewport coordinates, pinch/pan, pointer ownership, page mapping, clipping and hit-testing, ephemeral state, cross-platform acceptance tests.

**Excluded:** stroke capture/rendering SDK selection (EDT-05/Apple equivalent), brush fidelity, lasso geometry and command implementation, page CRUD UI (EDT-10), PDF crop/rotation conversion, image rendering/asset imports, persisted viewport state, and final device-tuned performance/zoom bounds.

## 2. Mandatory invariants inherited from FMT-01 and product contracts

1. Each page has a stable ID, explicit order, positive `width`/`height`, and a local top-left origin: `x` grows right, `y` grows down.
2. One document unit (DU) is **1/72 inch** for standard paper and PDF output. A4 portrait is approximately `595.276 × 841.890 DU`; US Letter portrait is `612 × 792 DU`. Landscape reverses dimensions. Mixed sizes are legal in one note.
3. Persisted page dimensions, object geometry, affine element transforms, and page/element order never depend on screen pixels, density, zoom, pan, device rotation, or window size.
4. Canonical element transform `[a,b,c,d,tx,ty]` maps element-local to page-local coordinates using the **column-vector** convention: `x' = a*x + c*y + tx`, `y' = b*x + d*y + ty`. Element drag in page space composes on the left (`T' = M*T`). The viewport never modifies these canonical transforms.
5. Element ownership is page-local; V1 selection is page-local. A pointer crossing into another page does **not** silently move a stroke/object between pages. Cross-page transfer is a separate explicit cut/paste action.
6. Clip all **page render output and live previews** to the owning page rectangle; patterns and PDF backgrounds are not selectable. Clipping is the final safety rule even if canonical object geometry extends beyond the page.
7. Zoom, pan, scroll, orientation changes, and merely opening a note are view-only events: they must not increment the note/content revision or trigger a document save. A future optional per-device view-state preference is separate from canonical content.
8. Toolbars/header/panels are *outside* the page coordinate system. The fixed editor tool strip remains at the top as previously confirmed.

## 3. Coordinate spaces and conversions

Use these distinct spaces; do not interchange their units.

| Symbol | Space / units | Purpose |
|---|---|---|
| `E` | Element-local DU | Stroke points, semantic shape geometry, text/image frames before element transform |
| `P_i` | Page `i` local DU | Canonical page content, top-left `(0,0)` |
| `W` | Continuous-document layout DU | Transient stacking of pages within the editor |
| `V` | Canvas-viewport-local native input units (VU) | Pointer positions, displayed page rectangles and visible viewport |
| `G` | Global window/screen coordinates | Hit-testing UI chrome only; convert to `V` at canvas boundary |

**Native unit adapter:** On Android, Compose pointer positions and `IntSize` normally use physical pixels; convert DP margins to pixels before combining values. On Apple, use the coordinate units returned by the chosen native gesture/view host (typically points). The equations are identical on both platforms because `scale` is measured in VU/DU. Never assume one DU equals one DP, one point, or one pixel.

### 3.1 Proposed layout of ordered mixed-size pages

Given ordered pages `i = 0..N-1`, with `w_i`, `h_i` in DU:

- `columnWidthDU = max(w_i)` across the note (page width *not* including a thumbnail/sidebar panel).
- `gapDU = 24` **(Proposed UI default, not canonical; tune after device review).**
- `origin_i.x = (columnWidthDU - w_i) / 2` (each page horizontally centered in the widest-page column).
- `origin_0.y = 0`.
- `origin_i.y = sum(h_j for j < i) + gapDU * i` for `i > 0`.
- `layoutHeightDU = origin_(N-1).y + h_(N-1)`.

Page layout changes when pages are added/reordered/resized, but never changes the pages' canonical coordinates. All layout origins are derived; do **not** store them in Room or `.notebook`.

### 3.2 Page-to-screen and inverse formulas

Let:

- `o_i` = transient page origin in world DU;
- `p` = page-local point in DU, *after* any canonical element transform;
- `s > 0` = current display scale in VU/DU;
- `t` = viewport translation vector in VU;
- `v` = canvas-viewport-local pointer/display point in VU.

Then:

```text
worldDU = o_i + p
v       = s * worldDU + t
worldDU = (v - t) / s
p       = (v - t) / s - o_i
```

For a transformed element, first transform `E -> P_i` with canonical `T`; only then apply page origin and viewport transform. For element hit-testing after choosing the owning page, invert `T` to test element-local geometry.

**Numerical example:** Page 1 is Letter portrait `612×792 DU`; page 2 is Letter landscape `792×612 DU`; gap is `24 DU`. The maximum width is 792. Page 1 origin is `(90,0)`; page 2 origin is `(0,816)`. On page 2, local `(10,20)` is world `(10,836)`. With `s=2 VU/DU` and `t=(30,-100) VU`, it displays at `(50,1572) VU`. Inverting produces page-local `(10,20)` exactly (within floating-point tolerance).

### 3.3 Initial fit and subsequent zoom

**Proposed initial layout:** Fit the widest page to the available *canvas viewport width*, with a `16 dp` Android / `16 pt` Apple horizontal visual gutter (converted to VU), without allowing zero/negative available width. The initial positive scale is:

```text
sFit = (viewportWidthVU - 2*gutterVU) / columnWidthDU
s    = sFit * zoomFactor
```

The screen X translation at initial fit centers the widest-page column; Y translation places page 1's top at the visual top gutter. Any blank portions of a shorter/narrower page remain visible as canvas surround.

**Proposed zoom limits:** `zoomFactor` between `0.5` and `6.0`, configurable rather than part of persisted content. These bounds are **not** a product acceptance threshold until tested on device. Scale must always remain finite and strictly positive. Invalid viewport size or malformed page dimensions must fail safely rather than divide by zero.

### 3.4 Pinch and pan invariants

A pan by viewport delta `deltaVU` changes only translation: `tNew = tOld + deltaVU`.

For a pinch centered at viewport point `fVU` and factor `k = sNew / sOld`:

```text
tNew = fVU - k * (fVU - tOld)
```

This keeps `worldDU = (fVU - t)/s` fixed under the fingers, **except where translation must be clamped at content edges**. If the zoom limit is reached, compute `k` from the *actual clamped* scale, not the requested scale. Two-finger pan and pinch may update scale/translation in the same event. No object transforms or page sizes change.

**Focal stability example:** `sOld=2`, `tOld=(30,-100)`, focal `f=(200,400)`, requested factor `1.5`: `sNew=3`, `tNew=(-55,-350)`. The world point under the focal position remains `(85,250)` before and after.

### 3.5 Proposed scroll/edge clamping

- Define the world **paper-column bounding box** as `x:[0,columnWidthDU]`, `y:[0,layoutHeightDU]`. The individual pages may not occupy every pixel inside it; gaps and margins are non-document areas.
- Convert bounds to VU using current `s`. With a visual edge gutter `gutterVU`:
  - If scaled column width fits into `viewportWidthVU - 2*gutterVU`, center the column horizontally; else clamp `t.x` to `[viewportWidthVU - gutterVU - scaledWidthVU, gutterVU]`.
  - If scaled content height fits into `viewportHeightVU - 2*gutterVU`, keep the document **top aligned** at `gutterVU`; else clamp `t.y` to `[viewportHeightVU - gutterVU - scaledHeightVU, gutterVU]`.
- Apply clamping after pan/pinch/resize and after computing an anchored target. Elastic overscroll and animation are **optional later UI refinements**; EDT-03 can start with deterministic hard clamping.
- Horizontal free pan is possible whenever zoom produces content wider than the viewport. Never force page content to expand or mutate to satisfy bounds.

## 4. Page targeting, hit-testing, and clipping

1. Convert the pointer position from window/screen space to viewport-local `V` *after excluding* the editor header, tool strip, thumbnails, system insets, and any side panels.
2. Compute world DU from the inverse viewport transform.
3. Find page `i` where `origin_i.x <= world.x <= origin_i.x + w_i` and `origin_i.y <= world.y <= origin_i.y + h_i`. Use a deterministic page-order tie-breaker on a shared boundary; pages are separated by a gap in this proposal. If no rectangle contains the point, target **no page** (canvas surround, gaps and panels are not writable).
4. Derive page-local DU by subtracting `origin_i` and dispatch to the selected tool's page-aware hit-test/capture logic. An element hit-test applies the inverse of its canonical affine transform before checking its local painted geometry.
5. Render each page/background/element under a clip rectangle that matches that page's bounds. The same clip must apply to the in-progress preview. The viewport scroll container clip is *additional* and does not replace per-page clipping.
6. **Capture ownership at pointer down:** a write/erase/lasso gesture starts only if it begins inside a page. Bind the gesture to its starting `pageId` until end/cancel. Crossing a page edge or an inter-page gap must not redirect it to another page. The preview is clipped to the owning page.
7. Do not independently clamp every sampled stroke point to the border: that can create artificial lines along an edge. The tool adapter may keep out-of-bounds intermediate geometry transiently and apply geometry-aware constraints at command completion. Final page render/hit-test still clips to visible page bounds. Canonical constraints remain governed by FMT-01/document model.
8. On page disappearance/reorder during active input, cancel that uncommitted gesture and rebuild layout using stable page IDs. Do not attach an unfinished gesture to whichever page moved into the old index.

For rendering, map both page background and elements through the **same** transforms as hit-testing. A visibly drawn point must map back to the correct owning page and local point regardless of density, zoom, pan, window resize, or device orientation.

## 5. Input ownership and cancellation

This is a **proposed arbitration policy** consistent with the confirmed product requirements. Separate the gesture coordinator from the eventual native ink SDK.

| Context | Pointer / gesture | Owner / result |
|---|---|---|
| Stylus-capable tablet, edit tool selected | Stylus down inside page | Active tool captures one page-local gesture; finger/palm contacts cannot draw |
| Stylus-capable tablet | One-finger drag | Viewport pan; no drawing |
| Tablet / phone | Two-finger drag or pinch | Viewport pan/zoom; no drawing |
| Android phone, finger drawing **off** | One-finger drag | Viewport pan |
| Android phone, finger drawing **on**, edit tool selected | One-finger down inside page | Tool captures page-local edit gesture |
| Android phone, finger drawing **on** | Second finger joins active finger edit | Cancel *uncommitted* finger edit, then hand ownership to two-finger viewport gesture |
| Any | Stylus input with no writable page / unsupported tool state | No document mutation; do not invent page ownership |
| Any | Back/system cancellation, pointer cancel, app loses interaction | Discard active transient preview; do not report a saved revision |

**Rules:**

- A gesture has exactly one owner at a time: `None`, `Viewport`, or `PageTool(pageId, pointerId, tool)`. UI chrome handles its own events before dispatch to the canvas.
- Stylus editing takes priority while actively captured; ignore incidental palm/finger contacts for drawing and do not change its viewport mapping mid-stroke. Start finger navigation after the stylus gesture ends. If the OS sends a cancellation, cancel; never silently commit a truncated stroke.
- On touch-only writing, distinguish a single-finger page gesture from two-finger navigation by active pointer count. When escalation occurs, cancel the incomplete single-finger command and begin viewport navigation with a fresh baseline; never commit both actions.
- Hover is preview-only; it must not save content. Stylus eraser button/side-button mapping is an adapter decision, not a canonical brush or persisted property.
- Mouse/trackpad and accessibility inputs should use platform-appropriate navigation; implementing their full behavior is outside EDT-01's minimum acceptance.
- Only a **completed and accepted** edit gesture may emit a document command to STO-02. Viewport gestures never do. Save status is driven by durable command commits (STO-06 integration), not transient previews.

## 6. State model and resize/navigation behavior

**Ephemeral viewport state (never stored in portable page data):**

```text
ViewportState
  pageLayout: ordered list of {pageId, originDU, widthDU, heightDU}
  canvasSizeVU
  scaleVUPerDU (> 0)
  translationVU: (x, y)
  zoomFactor (derived from current fit scale)
  activeGesture: None | Viewport | PageTool(pageId, pointerId, tool)
  focusedPageId (optional, for navigation/resize restoration)
```

**Event transitions (proposed):**

```text
Idle --stylus/eligible writing pointer inside page--> PageTool(pageId)
Idle --navigation touch/2-finger gesture-----------> Viewport
PageTool --accepted pointer up---------------------> Emit one editor command; Idle
PageTool --pointer cancel/system interrupt--------> Discard preview; Idle
PageTool --phone second-finger escalation---------> Discard preview; Viewport
Viewport --last navigation pointer up/cancel-------> Idle
```

- Loading/reopening a note computes the layout from saved pages and establishes a fresh viewport state; it never rewrites stored coordinates.
- **Proposed on first open:** fit to widest page and show the first page at the top. Future per-device focus restoration may be added without a document schema change.
- On resize, rotation, split-screen or thumbnail panel show/hide: preserve the currently focused page and a page-local anchor point if it still exists. Recompute fit scale for the new canvas viewport; preserve the user's *relative zoom factor* where feasible, reconstruct translation so the anchor stays near the same relative viewport position, then clamp. If focus disappeared, choose the nearest surviving page or first page.
- Recomputing layout uses stable `pageId` and order, not the on-screen slot index. Scroll position, selected tool, and viewport transformation are session/UI state and must not alter canonical page/element data.
- No requirement to render every page at once. Implementations should virtualize distant pages and render only visible/nearby pages (subject to performance measurement); offscreen pages still participate in layout/hit-testing calculations.

## 7. Worked acceptance scenarios and expected outcomes

The **EDT-01 definition of done** is a reviewed written contract that covers every issue #32 acceptance dimension. EDT-03/EDT-02 will later automate/execute interaction tests against platform viewports.

| Test ID | Scenario | Expected result |
|---|---|---|
| E01 | Two pages `612×792`, `792×612`, gap 24 DU | Origins `(90,0)` and `(0,816)`; dimensions stay unchanged |
| E02 | Page 2 local `(10,20)`, `s=2`, `t=(30,-100)` | Viewport `(50,1572)`; round-trip gives `(10,20)` |
| E03 | Pinch example in §3.4 | Focal world point `(85,250)` unchanged without clamping |
| E04 | Pan by `(40,-25)` VU | Translation changes by `(40,-25)` only; note/page/element revisions unchanged |
| E05 | Tap in inter-page gap or narrower-page side margin | No page targeted, no edit command |
| E06 | Begin stroke on page 1, move through gap into page 2 | Active `pageId` remains page 1; no visible page-2 ink |
| E07 | Element extends outside page, or preview crosses boundary | Neither committed rendering nor wet preview displays outside owning page |
| E08 | Stylus writing with incidental finger/palm contacts | Only stylus edits; no accidental touch strokes/viewport transform |
| E09 | Android phone single finger with finger draw off/on | Off pans; on edits inside owning page with an edit tool |
| E10 | Second finger joins uncommitted phone finger stroke | Cancel stroke without persistence; switch to two-finger navigation |
| E11 | Pointer canceled or app interrupted before gesture completion | No partial edit/revision or false Saved acknowledgement |
| E12 | Change zoom, pan, orientation, window density or thumbnail state | Page/element coordinates and stored revisions unchanged; anchor substantially preserved where not edge-clamped |
| E13 | Zoom creates content wider than viewport | Horizontal pan can expose either side; edge clamp prevents losing entire paper |
| E14 | Page removed/reordered during in-progress edit | Cancel uncommitted edit; future input targets stable page ID |
| E15 | Scale/viewport invalid or empty note state | No divide-by-zero, undefined page target or corruption; show safe empty/error state |

**Precision:** For pure coordinate inverses E02/E03, use reasonable floating-point tolerance (for example `1e-8 DU` in double-precision unit tests), not pixel-perfect rendering assertions. Platform gesture behavior depends on native event sampling and should later use practical device-test tolerances.

## 8. Dependencies, follow-on work, and peer-review checklist

**EDT-01 must not:** select/freeze a handwriting engine, add a new Room migration, persist screen-space coordinates, introduce cross-page live lasso or drag, or claim final pressure/latency fidelity.

**EDT-03 (Android) will:** implement the derived page layout, viewport transform, actual continuous scrolling, pinch/pan, page targeting and clipping in Compose, with meaningful unit/UI tests and phone/tablet emulator checks. A subsequent handwriting issue integrates its native input adapter and canonical stroke rendering.

**EDT-02 (Apple, deferred) will:** implement the same transform/ownership semantics using a native coordinate adapter. Both platforms must agree on page-local results even if VU differs.

**Review each point before accepting:**

- [ ] Confirm FMT-01 canonical coordinate/transform rules match §§2–3; resolve any discrepancies before implementing.
- [ ] Confirm page origin/stacking and zoom formulas independently with the numerical fixtures.
- [ ] Agree on proposed gap/gutters, initial fit, zoom bounds, and clamping defaults or document refinements.
- [ ] Confirm tablet stylus-vs-finger policy and phone finger-draw escalation/cancellation semantics.
- [ ] Confirm in-progress stroke clipping and no cross-page rerouting.
- [ ] Agree that view actions do not mutate `note.revision`, `page.revision`, or element geometry.
- [ ] Confirm EDT-03 and EDT-02 can implement this without changing the portable wire schema.
- [ ] Both developers approve the contract in PR review and note any changed choices in the PR/ADR.

## 9. Source contracts

- [`docs/architecture/notebook_document_model_spec_v0_2.md`](notebook_document_model_spec_v0_2.md) — canonical geometry and interaction boundaries.
- [`docs/ui-ux/notebook_ui_ux_design_system_v0_3.md`](../ui-ux/notebook_ui_ux_design_system_v0_3.md) — fixed toolbar, canvas, and proposed navigation behaviors.
- [`docs/product/notebook_v1_prd_and_delivery_plan.md`](../product/notebook_v1_prd_and_delivery_plan.md) — milestone and V1 acceptance goals.
- [`EDT-01 GitHub issue #32`](https://github.com/IlyaMich/notebook-app/issues/32) — authoritative acceptance criteria.
- [`EDT-03 GitHub issue #34`](https://github.com/IlyaMich/notebook-app/issues/34) — Android implementation dependency.

**Sign-off:** This version is a review draft. Treat only inherited invariants as already approved; all explicitly **Proposed** values/policies are awaiting joint sign-off.
