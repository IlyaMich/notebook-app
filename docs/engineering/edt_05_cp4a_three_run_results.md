# EDT-05 / CP4A — Three-run Android Ink measurement baseline

**Observation date:** 2026-11-02  
**Status:** CP4A baseline evidence collected; production Ink performance/stability not approved  
**Source:** `EDT05_CP4A_Tablet_Run01.zip`, `EDT05_CP4A_Tablet_Run02.zip`, `EDT05_CP4A_Tablet_Run03.zip` provided by the Android developer. All figures below are transcribed/derived from their `summary.json` and `gfxinfo.txt` files.  
**Device:** Android SDK 37, Android 17 `sdk_gphone64_x86_64` emulator, `emulator-5554`, same build fingerprint across runs. App package `com.notebook.app`.  
**Ink version:** capture tool labels the version as `1.0.0 (verify against local Gradle file)`; confirm the resolved dependency from the build before comparative testing.

## Protocol

Each run began with an app process restart and collected `baseline`, `after_default_draw`, `after_zoom_draw`, `after_clear` and `final` snapshots. The manual sequence was approximately ten normal-zoom strokes, ten further zoomed strokes, Clear and two fresh strokes, followed by five zoomed drawing/Clear/redraw cycles. This is a functional reproducibility exercise, not a laboratory-controlled or physical stylus latency benchmark.

The automated capture tests passed according to the developer, as did all manual checklists. The snapshots show **one unchanged app PID per run** (10236, 10576, 10826), with no detected process loss or restart within runs. A previous CP3C session did encounter a native Android `RenderThread` SIGSEGV; the absence of crashes in these three short CP4A runs does not close that issue.

## Proportional set size (PSS), MiB

| Snapshot | Run 01 | Run 02 | Run 03 |
|---|---:|---:|---:|
| Baseline | 68.23 | 55.60 | 56.48 |
| After default drawing | 75.18 | 75.49 | 76.32 |
| After zoomed drawing | 80.07 | 79.50 | 80.41 |
| After Clear and redraw | 77.82 | 77.82 | 78.58 |
| Final stress | 82.72 | 82.52 | 82.54 |

Memory decreased directly after Clear in all runs (~1.7–2.3 MiB) and final PSS converged to ~82.5–82.7 MiB. Baseline PSS varies, so baseline-to-final deltas should not be construed as a consistent leak rate. These short runs do not demonstrate leak freedom or bound memory growth for long documents.

## Sampled UI frame timing, p95 in milliseconds

| Snapshot | Run 01 | Run 02 | Run 03 |
|---|---:|---:|---:|
| Default drawing | 52.5 | 34.4 | 35.5 |
| Zoomed drawing | 51.5 | 51.9 | 51.5 |
| After Clear | 36.1 | 58.6 | 51.1 |
| Final stress | 18.6 | 19.7 | 35.2 |

During the zoomed-drawing snapshot, sampled frame-count and >33.33 ms counts were 77/54, 76/48 and 69/40 for Runs 01/02/03. Median sampled frame time was ~34.4 ms in each run. These are recent app UI-frame samples from `gfxinfo framestats`, **not direct ink-input-to-display latency** and not necessarily all frames or wet-ink render updates. Snapshot counts differ; they should not be combined into a single pooled latency percentile.

The `gfxinfo.txt` cumulative application summaries report final janky frames of 125/592 (21.11%), 100/594 (16.84%) and 122/602 (20.27%). These Android jank classifications are a useful corroborating indicator, not a direct pen-latency threshold.

## GPU resource diagnostics

| Snapshot | Run 01 | Run 02 | Run 03 |
|---|---:|---:|---:|
| After default drawing | 54.13 MB | 57.15 MB | 54.36 MB |
| After zoomed drawing | 54.31 MB | 54.24 MB | 54.35 MB |
| After Clear | 41.77 MB | 41.67 MB | 38.86 MB |
| Final | 51.98 MB | 52.16 MB | 51.78 MB |

GPU cache values come from `Total GPU memory usage` in `gfxinfo`. The GPU cache declines after Clear, then grows again. GPU cache and PSS are different accounting domains and must not simply be added together as an exact total device footprint. Only three short runs are available.

## Assessment and deferred risks

**CP4A evidence collection: complete.** The tool and three manual runs worked; the application remained usable and the process remained stable during the observed sessions. This checkpoint is suitable to commit as a **measurement baseline**.

**Production engine acceptance: not established.** Zoomed-drawing UI-frame performance consistently warrants further profiling. The prior native `libhwui.so` RenderThread SIGSEGV remains unresolved and should be tracked as an explicit risk. Physical-stylus pressure/palm testing, wet-ink input-to-display latency, long-duration memory stability and cross-platform visual fidelity remain outstanding.

## Recommended follow-up

1. Commit CP4A capture script, tests, protocol and this summary without changing the tested Ink version.
2. Run a controlled **debug-only** comparative CP4B experiment against a newer Jetpack Ink preview (official release notes list `1.1.0-alpha10` as of 2026-11-02). Use separate, reversible dependency configuration; no portable schema changes and no production adoption in this experiment.
3. Capture the same workload at the same emulator/host settings; compare stable and preview for build/API compatibility, native crash behavior, frame timing, memory, and wet/dry rendering. Compare on a second emulator API level if practical. A single version comparison cannot prove a fix for an intermittent native crash.
4. If zoomed-drawing jank remains, collect a Perfetto or Android Studio system trace to isolate Compose, app drawing, Ink, and HWUI activity rather than attributing UI frame delays to Ink alone.
5. Before production adoption, repeat on a physical stylus device and agree on performance/reliability gates from measured data.
