# ADR-0002: Android semantic basic shape tools

- **Status:** Accepted
- **Approval:** Both developers approved this ADR, as explicitly confirmed by the user on 2026-11-11.
- **Date:** 2026-11-10
- **Decision makers:** Apple and Android developers
- **Related tasks / issues:** EDT-12 [#43](https://github.com/IlyaMich/notebook-app/issues/43); prerequisites FMT-01 #11 and EDT-03 #34; boundaries EDT-14 #45 and EDT-18 #49
- **Checkpoint:** C0 - proposed behavior and acceptance cases only
- **Inspected baseline:** `a137dfdfc1b0e51e68e36ab25206c09fbea65b73`

## Context

EDT-12 requires creating and editing line, rectangle, ellipse and arrow as retained semantic objects. The current portable shape payload and Room validation already recognize all four kinds. Production UI still has a Shapes placeholder and retained rendering processes strokes only. General mixed-object selection and general session undo are separate tasks.

This record makes the reviewed implementation plan concrete. Joint approval is recorded above; acceptance does not claim a completed shape implementation, passing runtime tests, or cross-platform interoperability. Existing document/schema invariants remain authoritative, with the shape semantics clarified by this accepted ADR.

References:

- [Document model V0.2](../architecture/notebook_document_model_spec_v0_2.md), sections 4-9.
- [Version 1 draft portable schema](../../shared/schemas/notebook_schema_v1_draft.json), `shapePayload`, `lineShape`, `arrowShape` and `filledShape`.
- [Viewport/input contract](../architecture/edt_01_viewport_gesture_contract_v0_1.md).
- [Verification matrix](../development/android-verification-matrix.md).
- [Contribution and joint-review rules](../../CONTRIBUTING.md).

## Decision

### 1. Canonical representation and scope

Reuse the existing `type: "shape"` envelope, `StoredElement`, `DocumentEdit`, `DocumentRepository`, Room database and autosave coordinator. Typed shape adapters are derived views of canonical envelopes, not another authoritative document or persistence model.

Keep `schemaVersion: 1`, Room version, migrations and dependencies unchanged. Finite double-precision element-local document units, top-left page coordinates, page ownership, IDs, lock state and explicit element ordering retain their existing meanings. One DU is 1/72 inch; viewport state is never canonical geometry.

EDT-12 includes shape-only tap selection, endpoint/frame editing, movement, outline color/width and rectangle/ellipse fill controls. Reopened shapes remain editable. Exclude mixed-object lasso, group transformations, clipboard, cross-page transfer, rotation UI, opacity UI and arrowhead-settings UI. Preserve existing opacity and arrowhead values when editing other properties.

### 2. Creation, editing and page boundaries

Select a shape kind, then drag with an eligible writing pointer. Preserve endpoint direction for line/arrow; normalize rectangle/ellipse geometry to positive width and height. New shapes use identity transforms, fresh IDs and append to the owning page's element order.

Use these Android interaction defaults, subject to device review:

- A line/arrow requires start-to-release distance of at least 4 dp.
- A rectangle/ellipse requires each displayed frame dimension to be at least 4 dp.
- Endpoint/corner handle hit regions have a 24 dp radius, giving a 48 dp diameter target. Resolve overlapping handles by shortest distance, then stable handle order.
- Shape-body hit tolerance is 8 dp around the painted geometry. Filled interiors participate; unfilled interiors do not.

Convert dp to physical viewport pixels using density, then through the captured viewport scale; apply the inverse element affine for local editing as needed. These are input tolerances, not schema limits, saved fields or permission to enlarge existing small shapes. Test at the threshold and immediately below it.

Capture pointer, starting page, camera, viewport size, lifecycle generation, tool settings and rendered note revision at gesture start. Never reacquire another page during a gesture. Starts in gaps/chrome/surround have no page owner. Leaving the owning page or viewport cancels the entire unfinished gesture, matching current pen behavior; do not clamp individual samples to an edge. Page clipping remains mandatory for preview, handles, hit-testing and retained paint, including preexisting content outside the page.

For in-page movement/resizing, reject a candidate whose painted footprint extends outside the page when the starting shape was fully contained. Leave the last valid preview visible while the pointer remains on the page; release commits only that valid candidate. Preexisting out-of-page geometry remains preserved and clipped: do not automatically shrink or translate it on load. Permit edits that bring it fully inside; otherwise report that the edit cannot be applied. A pointer leaving the page cancels even if an earlier candidate was valid.

Resolve the topmost unlocked shape from reverse element order and only its visible painted geometry. Handles take precedence over body targeting. A non-shape object is never selected or changed by the shape tool. Movement composes a page-space translation on the left (`T' = M * T`); endpoint/frame changes use inverse element mapping. Preserve the shape ID and element order.

Second-finger takeover, consumed/platform-cancelled release, pause, disposal, document/layout/camera change, relevant snapshot change, or tool/settings change cancels uncommitted shape input. Existing stylus priority, finger navigation and eraser-contact routing remain authoritative. A new pointer-down is required after cancellation.

### 3. Approved arrow and paint semantics

The existing payload does not define the exact meaning of `angleDeg` or which endpoint is the arrow tip. The approved convention is:

- `(x2,y2)` is the tip.
- The head is an open V. With unit shaft direction `u`, reverse direction `v = -u`, head length `L` and angle `theta`, each head endpoint is `tip + L * rotate(v, +/-theta)`.
- `angleDeg` is the angle of each leg from the reverse shaft direction, not the full opening angle. Length is measured along each leg in element-local DU.
- New arrows default to `length: 12`, `angleDeg: 30`, matching the existing example. Do not silently shorten or rewrite a stored head when its shaft is short.
- Draw shaft and head as one stroked path to avoid double opacity at joins. Use round caps/joins consistently for preview and retained output. Closed shapes draw fill first and outline second.
- Line/open-arrow geometry has no closed interior. Preserve any schema-valid non-null fill on those kinds without creating an implicit filled region; new lines/arrows use null fill. This interpretation is included in the joint approval recorded above.
- A stored zero-length line/arrow renders a round dot using its outline width; a zero-length arrow has no head. New zero-size gestures create nothing.
- The schema accepts any finite arrow angle. Reduce it only for transient trigonometry if needed; preserve the serialized value. Invalid/nonrepresentable rendering calculations must fail explicitly without mutating the source.

No production renderer, schema validator or document-model wording changes as part of C0.

### 4. Rendering, persistence and history

Prepare supported stroke/shape commands in persisted element order. Apply element-to-page and page-to-viewport transforms to the complete paint footprint, followed by the owning page clip. Decode/cull/cache using the current viewport architecture. Viewing, pan, zoom and recreation must not create commands or revisions.

A completed creation produces one `InsertElements`; editing produces one `ReplaceElements`. Both pass through the existing coordinator and transactional `commitIfRevision`. Admit shape actions only against a current confirmed preview while autosave is idle. Cancel/no-op actions produce no write or history entry. Saved means durable commit confirmation.

Extend the coordinator's existing single history mechanism with tagged shape operations, rather than adding a separate shape stack/service. Store exact forward/inverse envelopes and insertion positions. Undo insertion uses guarded removal; redo restores the same ID. Undo/redo replacements restore exact before/after envelopes.

Preserve existing eraser-only histories and partial/whole-erase chronology. Shape-specific and eraser-specific actions may operate only on a matching top history entry; they must never skip another operation. Unsupported pen/page commands continue invalidating history. Append/move history only after confirmed durable commits. Ambiguous outcomes retain existing reconciliation behavior and must not replay automatically.

Retain asset/PDF guards and stroke-only erasing. No production Ink promotion, asset-aware writer expansion or general EDT-18 engine is authorized by this decision.

## Alternatives considered

- Raster shapes or stroke approximations: lose the retained parameterized object contract.
- A new shape table, document aggregate, queue or independent undo service: duplicates ownership and durability architecture.
- Implement mixed-object lasso first: expands EDT-12 into EDT-14 and delays the independent shape capability.
- Draw all shapes after all ink: violates canonical mixed element ordering.
- Tighten schema validity to match Android creation thresholds: would reject existing valid documents; interaction defaults must remain separate.
- Treat completion of this draft as joint approval: conflicts with the repository ADR process.

## Consequences and tradeoffs

The first implementation can reuse the existing portable format and Room commands. It still needs shape decoding, rendering, gesture/edit adapters and a reviewed coordinator history extension.

Strict page/camera cancellation is conservative and consistent with current pen behavior. Small/partially clipped imported shapes need the explicit editing rules above; device testing may justify changing interaction tolerances, but not silently changing canonical data.

The approved arrowhead and fill interpretations clarify cross-platform behavior without changing schema fields or validity. Physical stylus behavior and Apple interoperability remain separate evidence gates.

## Verification / review plan

These are proposed acceptance cases, not executed results:

| Case | Required result |
|---|---|
| Four kinds; reverse-direction drags | Correct endpoint direction or normalized positive frame; one semantic envelope |
| Threshold/below-threshold gesture | At-threshold valid geometry may commit; below threshold produces no write |
| Invalid/nonfinite geometry or singular transform | Explicit rejection; no partial replacement or changed saved revision |
| Existing tiny or zero-length geometry | Preserve canonical values; deterministic clipped rendering; no automatic normalization |
| Arrow tip (100,0), start (0,0), length 12, angle 30 | Head endpoints approximately (89.607695,6) and (89.607695,-6) in local DU |
| Nonuniform scale/rotation/reflection | Correct complete footprint, handles and inverse editing; no stored viewport coordinates |
| Stroke-shape-stroke overlap | Retained painter order matches element order |
| Page exit, second finger, pause or cancelled release | Entire unfinished gesture discarded; fresh down required |
| Stale snapshot or busy/failed save | Shape action rejected; no false Saved or blind replay |
| Shape insert/edit then undo/redo | Exact envelope/ID/order restored through guarded durable commands |
| Shape and both eraser modes interleaved | Chronological history; typed history actions cannot skip the top entry; shapes survive erasing |
| Save, leave, reopen, edit again | All four kinds remain independently editable |
| API 37, API 26 and physical stylus | Record separate runtime/hardware evidence; emulator does not establish palm fidelity |

C0 verification is documentation/source consistency and diff scope review. No JVM/instrumentation tests are added or run for this documentation-only checkpoint. Later checkpoints add shared fixture, JVM, Room, rendering and production-UI tests per the implementation plan; follow the verification matrix for exact commands and device selection.

Both developers approved creation/editing boundaries and tolerances, page-footprint handling, arrow/paint semantics and the limited history extension. Acceptance was explicitly confirmed by the user; it was not inferred from a passing build or implementation authorization.

## Follow-up work

1. After joint acceptance, incorporate the approved arrow/paint clarification into the document model and shared fixtures/tests without tightening schema validity.
2. C1: canonical adapters and contract tests; C2: retained ordered rendering; C3: guarded shape commands/history.
3. C4: production creation; C5: shape-only editing/reopen; C6: regression and device evidence.
4. Stop after each authorized checkpoint. C0 does not begin C1 or authorize commits, pushes, new dependencies, schema changes or Room migrations.
