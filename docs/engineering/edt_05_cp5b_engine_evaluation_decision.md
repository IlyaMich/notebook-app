# EDT-05 / CP5B — Android handwriting engine evaluation decision

**Decision date:** 2026-11-02  
**Decision status:** Conditional / evaluated candidate; **not production-qualified**  
**Scope:** Android-only Jetpack Ink 1.0.0 debug evaluation on `feature/edt-05-android-ink-evaluation`  
**Related work:** EDT-05 (#36), FMT-04, EDT-03; CP1A–CP5A  
**Peer review:** Pending. The developer reports the CP5A JVM tests passed and CP5A was committed; no merge, physical-device acceptance or production adoption is implied.

## 1. Decision

**Keep Jetpack Ink 1.0.0 as the leading, replaceable Android handwriting-engine candidate for future gated integration.** It is suitable to retain as a **debug-only, canonical-first evaluation implementation** and to merge its reviewed evaluation code and evidence if CI passes. **Do not promote it to the production editor or declare it fully production-ready on the current evidence.**

This is not a claim that Jetpack Ink is demonstrably superior to all alternative engines: no head-to-head engine benchmark was conducted. The engineering choice is to avoid replacing an implemented, promising candidate without evidence, while preserving the ability to change engines later.

**Portable authority:** The shared `.notebook` `sampledPointsV1` stroke payload, page-local double-precision geometry (top-left origin, 1/72-inch document units), existing document schema, and Room repository semantics remain authoritative. Native Jetpack Ink objects, meshes, input layers and rendering caches are rebuildable/replaceable platform details. Never write engine-specific meshes as the only durable representation of a stroke, and never change the shared format merely to make the native renderer easier to use.

**Version:** Use stable `androidx.ink` 1.0.0 for the evaluated code. Do not merge the attempted 1.1.0-alpha10 dependency switch. A preview comparison is deferred and *inconclusive* because Android instrumentation and APK installation became blocked; this is not evidence of a preview regression.

## 2. Evidence supporting the decision

| Evaluation area | Existing evidence | Result / scope |
|---|---|---|
| Native capture and conversion | CP1A/CP1B debug probe; sample conversion/rebuild; rotation retention | Functionally exercised in debug implementation; canonical samples independent of native meshes |
| Canonical repository and Room | CP2A/CP2B tests; CP2C Room-backed capture/save/reopen demo | Existing Android persistence integration passed its earlier targeted validation; no new Android runtime run in CP5A |
| Page and viewport mapping | CP3A tests; CP3B multi-page debug gesture arbitration | Page-local DU conversion, zoom/pan and whole-gesture rejection for cross-page strokes evaluated |
| Wet/dry handling and input lifecycle | CP3C tests/manual checks; debug clipping and rejection recovery | Later valid strokes accepted after rejection; Clear works in successful manual sequence; **native crash risk remains** |
| Short-run memory and UI frames | CP4A: three reproducible emulator sessions and raw `dumpsys` evidence | Final PSS ~82.5–82.7 MiB; no app PID change in the three captured runs; zoomed drawing UI-frame p95 ~51–52 ms |
| Canonical fidelity | CP5A: three new deterministic JVM regression tests, reported passed and committed | Geometry reprojection across page shapes/zooms; brush width, timestamps and optional sample attributes; **not native pixel/pressure fidelity** |
| Alternate Ink preview | CP4B1 1.1.0-alpha10 attempt | Debug/test compilation succeeded; instrumentation and streamed/non-streamed APK install hung; no valid preview measurements |

Sources within the repository: `docs/engineering/edt_05_cp3c_wet_dry_boundary_fidelity.md`, `docs/engineering/edt_05_cp3c_renderthread_clear_isolation.md`, `docs/engineering/edt_05_cp4a_three_run_results.md`, `docs/engineering/edt_05_cp5a_fidelity_evidence_protocol.md` and the corresponding debug and test source files. The full raw CP4A capture ZIPs remain developer-held evidence, not required in Git.

### Measurement interpretation

- **UI-frame p95 is not pen-to-display latency.** The repeated ~51–52 ms `gfxinfo` p95 for zoomed drawing warrants profiling, not an unsupported conclusion about wet-ink latency.
- Final PSS clustered closely in three **short** runs, and dropped after Clear, but that does **not** rule out long-session memory leaks, GPU lifetime problems or document-scaling problems.
- CP5A's tiny deterministic geometry error bounds and CP3C's provisional 0.5 px handoff diagnostic are **not** approved visual-fidelity thresholds.
- Earlier intermittent Android 17 x86_64 `libhwui.so` RenderThread `SIGSEGV` after zoom/draw/Clear remains **unresolved**. The tombstone identifies the thread/library, not the causative component. Three later crash-free runs do not close this risk.

## 3. Explicit production-readiness gates

These are **gates to production enablement**, not prerequisites for peer-reviewing and merging the debug-only evaluation. Record results and reviewer sign-off in follow-up work; avoid inventing numerical pass thresholds before device measurements.

| Priority | Gate | Evidence needed to close |
|---|---|---|
| Blocker | Native renderer and Clear/cancel lifecycle | Repeatable extended zoom/draw/Clear, rejection and cancel stress on real hardware and a second API/device profile; capture full native crashes/ANRs; isolate wet vs dry rendering if failure returns |
| Blocker | Physical stylus behavior | Pressure, tilt/orientation presence and fallback, palm touches, stylus/finger coexistence, hover/eraser where available, cancellations, handoff appearance, rotations and zoom on target stylus tablet |
| Blocker | Wet/dry responsiveness and visual fidelity | Measured physical-device latency methodology; visual before/after handoff comparisons at fit and zoom; document acceptable thresholds from observed user/device behavior |
| Blocker | Zoomed UI-frame jank | Repeat workload with Perfetto/Android Studio trace; attribute time to Compose/app drawing/Ink/HWUI, then set and verify a target-device performance budget |
| Blocker | Long-session scaling/durability | Long drawing sessions, many strokes/pages, suspend/relaunch, forced termination during save, memory and cache bounds; previously saved canonical content must remain correct |
| Blocker for cross-platform release | Editable round-trip interoperability | Native iPad/PencilKit implementation plus bidirectional `.notebook` Android↔iPad editable stroke tests; preserve FMT-04 import/export fixtures |
| Feature gate | Product tool/editor integration | Production UI semantics for brush settings, whole/partial eraser, undo/redo, selection and autosave state; cross-page rejection is currently a **debug policy**, not a final UX decision |

The V1 product/document specification expects **partial-stroke erasing by default** and canonical editable geometry. Do **not** assume the currently evaluated native Ink eraser, or a preview's mesh-editing eraser, meets that contract. Erasure must preserve canonical storage semantics and interoperable geometry; this requires its own design and tests.

## 4. Architecture and scope guardrails

1. Continue the canonical-first boundary: `native input → page-local canonical sampledPointsV1 → Room/portable document → native rebuild for display`. Reject non-finite geometry and preserve the specified optional sample fields.
2. Isolate the replaceable native capture and renderer adapter behind an app-owned interface before production integration; no native mesh as an irreplaceable persisted payload.
3. Keep viewport transforms, active page ownership and live/dry clipping independent of Ink implementation. Zoom/pan must never mutate canonical stroke coordinates.
4. Retain the current debug-only probe, Room demo and gesture demo as evaluation tools, not production navigation destinations. Production integration requires a separate scoped and reviewed issue/PR.
5. Preserve the known CP3C normal rejection/Clear behavior: no ordinary native-layer remount on a rejected finished stroke or Clear. Explicit cancel/mode changes remain separately tested. Avoid speculative renderer lifecycle refactoring.
6. No changes to shared schemas, Room migrations, package version or iOS code as a result of this CP5B decision.

## 5. Separate follow-up work (create/associate GitHub issues; IDs TBD)

- **EDT-05 follow-up — Native stability and target-device ink profiling:** Physical stylus acceptance, Clear/cancel stress, prolonged memory and frame tracing, and concrete thresholds.
- **EDT-05 follow-up — Preview evaluation blocked by Android package-install infrastructure:** Restore a healthy test target, retry comparison only if justified, and report stable vs candidate results. The 1.1 preview is not approved or rejected by the failed installation attempt.
- **Future editor integration work item:** Feature-gated production-editor adapter with canonical storage, tool/eraser, undo/redo and data-durability acceptance. This is **not** part of the EDT-05 evaluation PR.
- **Cross-platform validation work item:** iPad editable two-way fidelity after PencilKit support exists; FMT-04 remains provisional for appearance parity until verified.

Do not presume these issues have already been created, and do not invent issue numbers. Link their actual GitHub URLs during review.

## 6. EDT-05 evaluation PR review/exit checklist

- [ ] Confirm all intended CP1–CP5A commits are on the feature branch; CP4B preview Gradle switch is absent.
- [ ] Confirm `inkProbeVersion` (or equivalent declaration) remains `1.0.0` and the production editor flow remains unchanged.
- [ ] Verify the repo contains no superseded CP3C repair notes, transient ZIP files, crash logs, generated reports, or untracked CP4B experimental scripts staged inadvertently.
- [ ] Verify shared `.notebook` schemas/fixtures and Room migrations were not changed for this evaluation.
- [ ] Re-run applicable repository CI / JVM tests; if a healthy device is available, run focused existing Android instrumentation checks. Record skipped device checks explicitly rather than presenting them as passed.
- [ ] Review the debug-only boundary, code quality and tests with the second developer; record this decision and the open risk issues in the PR.
- [ ] Commit this CP5B decision document after review and open the EDT-05 PR for peer review. Merge only when CI/review pass and **without enabling production inking**.

### Disposition

**EDT-05 evaluation exit:** Conditionally complete once this decision is peer-reviewed and the debug-only evaluation PR passes CI and is merged.  
**Jetpack Ink 1.0.0 production adoption:** **NOT APPROVED** on current evidence.  
**Preferred next engineering move:** Track target-device/profiling blockers and separately plan the production editor integration behind replaceable adapter boundaries, while allowing unrelated V1 work to continue.
