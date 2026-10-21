# Notebook App - V1 Product Requirements Document (PRD) and Delivery Plan

**Version:** 1.0 (planning baseline)  
**Date:** 21 October 2026  
**Status:** Consolidated V1 requirements. This document authorizes planning and backlog grooming, **not** a claim that the application or engine has been validated.  
**Owners:** Two-person product/engineering team; names and platform assignments TBD.  
**Reference artifacts:** `notebook_document_model_spec_v0_2.md`, `notebook_schema_v1_draft.json`, `notebook_library_storage_plan_v0_1.md`, `notebook_ui_ux_design_system_v0_3.md`, `notebook_ui_ux_wireframes_spec_v0_2.md`, `notebook_development_workflow_v0_1.md`.

## 1. Product statement

Build an offline-first, native digital notebook focused on **iPad + Apple Pencil**, and also shipped for **Android tablets and Android phones**. A user can create notebooks, handwrite and annotate fixed pages in a continuously scrolling zoomable editor, combine handwriting with editable shapes/text/images and PDFs, and protect their work through autosave plus manual editable-note and full-library backup/restore. No account or cloud service is required for V1.

### Product principles
1. **Inking quality:** stylus input and navigation should feel natural; establish thresholds from real devices rather than promising arbitrary timing.
2. **Trust:** a visible “Saved” state must correspond to a durable committed revision; never falsely imply cloud backup.
3. **Editability:** preserve structured objects, not flattened screenshots, for the native note format.
4. **Interoperability:** documents remain editable across both native implementations.
5. **Sustainable scope:** only two developers; avoid unnecessary backend services and premature custom ink engine commitments.

## 2. Personas and primary journeys

**Student / active note-taker (primary):** opens Mathematics notebook → creates note without a wizard → writes using pencil/highlighter → zooms/pans while pages scroll vertically → inserts geometric shapes, text and a PDF → navigates by page thumbnails → closes/reopens without data loss.

**Tablet and phone Android user:** accesses the same conceptual library and editable note format with responsive controls; explicit finger-writing mode on phones where needed.

**User preserving data:** exports an editable note or full-library backup → moves file manually → imports/restores on either platform without silent overwrite.

## 3. Platforms and technology decisions

| Topic | Decision / boundary |
|---|---|
| Apple | Swift + SwiftUI, with UIKit and native drawing integration as necessary; rented cloud Mac + Xcode |
| Android | Kotlin + Jetpack Compose; Android Studio on Windows |
| Stylus engine | **Not selected**. PencilKit and Jetpack Ink are preferred *candidates*, evaluated at editor prototype milestone; Wacom WILL remains an alternative |
| Native UI | Approved V0.2 wireframes; fixed top toolbar; adaptive layout |
| Storage | SQLite local repositories plus managed original assets; internal schema separate from portable format |
| Document contract | Versioned, editable `.notebook` archive; draft schema from V0.2 remains subject to compatibility validation |
| Collaboration | GitHub private monorepo; reviews; CI; one primary engineer per platform |
| Backend | None in V1; no user accounts or cloud synchronization |

**Minimum supported OS versions, cloud Mac provider, signing/account ownership, and numerical performance gates are open operational decisions**, to resolve at the foundation/prototype milestones. Do not infer approval of specific versions from this PRD.

## 4. V1 functional requirements and testable outcomes

Requirements are grouped into the following IDs for review; individual implementable tasks have `FND/LIB/STO/EDT/CNT/PDF/XFR/SRH/QAT` IDs in the backlog.

### R-LIB - Library and navigation
- R-LIB-01: Library hierarchy is **Library → optional nested Folder(s) → Notebook → Note → ordered Page(s) → Element(s)**. Folder may contain folder/notebook, not note directly; root notebooks allowed. Cycles prohibited.
- R-LIB-02: Create, rename, move, duplicate (notes), soft-delete, permanently delete and restore eligible library entities. Provide Quick Notes default notebook.
- R-LIB-03: Recent Notes and All Notes are virtual queries, not copies; notes sortable by modified date (default), created date and title; grid/list view as designed.
- R-LIB-04: Create note immediately with defaults; return from editor to previous library context when practical.

**Acceptance:** a folder/notebook tree can be created, moved without cycles, deleted to Trash and recovered; note move preserves content; create note has no mandatory modal.

### R-PAG - Pages and canvas
- R-PAG-01: Note is ordered fixed-size pages with *continuous vertical scrolling*, two-finger pinch zoom and free pan; stored geometry never changes due to viewport transform.
- R-PAG-02: Writing stays within page boundary. Page-local selection in V1; transfer between pages by cut/paste, not direct cross-page drag.
- R-PAG-03: A4/US Letter in portrait/landscape; blank/ruled/grid/dotted templates; mixed page sizes supported by model, including PDFs; per-page manual size editing not required in V1.
- R-PAG-04: Add, duplicate, reorder and delete pages; tablet thumbnails hidden initially, shown in collapsible left panel; phone uses bottom sheet.

**Acceptance:** reopening preserves order, dimensions and element coordinates after scrolling, zoom, and changes in orientation.

### R-INK - Handwriting and editing
- R-INK-01: Apple Pencil and Android stylus drawing; pen, pencil and highlighter with colors and width; pressure where available; eraser offers **partial (default)** and whole-stroke modes.
- R-INK-02: Native input must support palm-rejection/cancellation handling and distinguish stylus editing from finger navigation on stylus tablets; Android phones have explicit finger-drawing mode.
- R-INK-03: Semantic editable line, rectangle, ellipse/circle and arrow; colors, stroke width and editable geometry.
- R-INK-04: Unified **page-local lasso** selects all editable types: strokes, shapes, text and images; move, proportionally scale, delete, restyle compatible types; tap selects single element.
- R-INK-05: Session-only undo/redo includes multi-object edits as single reversible actions; cut/copy/paste supported across pages; copied objects get new IDs.

**Acceptance:** handwriting/shape content saved on one platform can be reopened and edited on the other; partial eraser splits editable stroke segments; commands undo in reasonable units. Cross-platform visual fidelity/brush subset must be validated in prototype, not assumed pixel-perfect.

### R-CNT - Rich content
- R-CNT-01: Styled, movable, resizable text boxes; formatting at box level, no mixed rich-text spans required.
- R-CNT-02: Insert images as editable asset-backed objects; select/move/resize them.
- R-CNT-03: Import PDF as fixed-size page backgrounds and retain original PDF asset; annotate on top using independent editable objects. Export combined pages to PDF with correct orientation and page geometry.

**Acceptance:** imported PDFs remain legible while annotations stay editable in native `.notebook` documents; PDF export composites the content correctly.

### R-DAT - Local durability and portability
- R-DAT-01: Offline operation with SQLite for index/elements and filesystem for image/PDF assets; completed edits persist via transactional autosave. UI distinguishes Saving/Saved/Failure.
- R-DAT-02: Safe staged asset imports, content hash/integrity checks, migration plan, and recovery of interrupted operations; avoid committed references to missing files.
- R-DAT-03: Import/export an individual fully editable `.notebook` note across platforms, including required assets and schema version.
- R-DAT-04: **Full-library backup AND restore in V1**: folders, notebooks, notes, pages, assets and relationships. Restore validates compatibility and file safety before mutating library; importing into an existing library adds content and remaps collisions rather than overwriting.
- R-DAT-05: Trash preservation and reference-aware asset cleanup; no known pathways to false “Saved” state on unrecoverable write failure.

**Acceptance:** full-library backup from either platform can restore on an empty install and a populated install; forced termination and low-storage tests do not destroy existing data.

### R-SEA - Search
- R-SEA-01: Offline search note titles, folder/notebook names, and **typed text** in notes; basic sorting as above. Handwriting OCR/search deferred.

### R-UX - Responsive UX and accessibility
- R-UX-01: Approved wireframe baseline on iPad portrait/landscape, Android tablets and phones; fixed top drawing toolbar; contextual tool settings; adaptive layout based on available window size.
- R-UX-02: Canvas has top priority for screen space; page content colors unaffected by app dark/light theme.
- R-UX-03: Basic VoiceOver/TalkBack support for library, primary tool controls, dialogs and page organizer; accessible hit targets; keyboard/focus behavior for non-canvas actions.
- R-UX-04: Backup and restore reachable at **Settings → Data & Backup** with preview and warning that backup is user-initiated, not automatic cloud backup.

## 5. Explicitly out of V1

- Cloud sync, account system, live collaboration, and notebook sharing.
- Audio recording; handwriting OCR/search/recognition; AI tools.
- Movable drawing toolbar; dedicated close-up writing/auto-advance window.
- Cross-page live lasso/drag; true unbounded infinite canvas.
- Advanced shape recognition/maths, mixed-style rich-text runs, and automatic remote backup.
- iPhone-specific release (Apple release target is iPad; Android phones are included).

Deferral should not remove forward-compatible hooks from the document format, but it must not add an implementation requirement to V1.

## 6. Nonfunctional requirements and release gates

| Category | Requirement / release evidence |
|---|---|
| Data durability | No known critical loss/corruption bugs. Acknowledged saved revision survives forced relaunch. Failed writes clearly surfaced and recoverable. |
| Interoperability | Golden `.notebook` fixtures accepted/rejected identically by Swift and Kotlin; editable iPad→Android→iPad and reverse round-trips pass. |
| Long documents | Virtualize nonvisible pages and bound caches; test handwritten and PDF-heavy documents on actual target devices. |
| Input quality | Test pressure, cancellation/palm touches, in-progress responsiveness and zoom effects on real stylus hardware; set numeric acceptance targets from recorded prototype baseline before beta. |
| Security | No embedded secrets, validated archive paths/size/asset integrity, least-privilege file access, safe restore, non-destructive error handling. |
| Accessibility | Primary navigation and controls operable with supported assistive features; known handwriting-specific gaps documented. |
| Release | Successful internal/closed betas, device matrix, privacy disclosures, support contact and store listing assets. Confirm current store requirements at submission time. |

## 7. Delivery milestones (dependency-guided, not time estimates)

| ID | Exit evidence |
|---|---|
| **M0 - Foundation** | Repo + reviews/CI + cloud Mac + Android setup; both navigation shells build. |
| **M1 - Library and data skeleton** | Domain schema, CRUD, first saved empty note, schema validator and fixtures. |
| **M2 - Editable ink vertical slice** | Continuous pages, engine candidates evaluated **at this milestone**, save and reload strokes on both platforms. |
| **M3 - Core editor operations** | Brush tools, shapes, selection, erase, session undo/redo, page operations. |
| **M4 - Rich content & library UI** | Text, images, PDF annotation/export, page organizer, responsive library and search. |
| **M5 - Portability and recovery** | Note import/export, full-library backup/restore, semantic round-trips, crash/migration tests. |
| **M6 - Hardening and release** | Accessibility, physical-device performance, failure tests, beta builds and joint release decision. |

Milestones may overlap. Prioritize the thin end-to-end slice: create note → insert editable content → commit → exit → relaunch → reopen. Do not build all UI first or make engine selection now.

## 8. Two-developer ownership model

- **Apple lane:** one developer owns SwiftUI/UIKit and the rented Mac environment, while the other can review.
- **Android lane:** the other developer owns Kotlin/Compose and local Windows/Android test workflow, while the Apple developer reviews.
- **Joint lane:** document schema, cross-platform semantics, migrations, compatibility, data durability, security, and release gates must be agreed by both.
- **Shared lane:** platform-neutral validators, schema and golden fixtures; assign a rotating primary maintainer.
- Each task has a lane rather than an actual name. Reassign once the team chooses who owns which platform.

## 9. Prioritization and backlog conventions

The companion CSV/XLSX/JSON backlog contains tasks with IDs, epic, owner lane, milestone, dependencies, acceptance criteria and state. **P0** is a release blocker / architectural prerequisite; **P1** is still in scope for V1 but follows core risk reducers. Neither class is a promise about calendar duration. Only move a task to Done after its acceptance criterion is demonstrated. If an architectural experiment disproves the current design, record an ADR and revise downstream tickets before implementation.

Status flow: `Not started → In progress → In review → Done` (or `Blocked`). Dependencies are task IDs, comma-separated. Work can run in parallel once predecessors are met. Treat compatibility/backup tests as nonnegotiable, not optional polish.

## 10. Release definition of done

A release candidate passes only if all P0 and all agreed P1 V1 tasks are complete; cross-platform scenarios in §6 and §4 pass; on-device performance targets agreed during M2 are met or explicitly accepted with rationale; backup restore has been tested onto both empty and populated libraries; and both store submission packages have valid signing, privacy notices, screenshots and review evidence. Any unresolved data-loss risk is a release blocker.

## 11. Outstanding decisions and risk register

| Topic | Current stance | Resolve by |
|---|---|---|
| Exact min iPadOS/Android versions | Unspecified; choose based on actual device matrix and SDK APIs | M0 |
| Rental Mac service, Xcode build/signing workflow | Cloud Mac selected; provider not selected | M0 |
| Developer program / Play account owners | Team must determine who holds signing and store accounts | M0 or before external beta |
| PencilKit vs custom/third-party engine | Evaluation deliberately postponed | M2 |
| Brush cross-platform visual fidelity | Shared canonical stroke contract proposed, still unproven | M2/M5 |
| PDF crop/rotation, annotation alignment | Requires fixture suite | M4 |
| Backup format version and forward compatibility | Design during portable format work; safe failure on unsupported features | M1/M5 |
| Actual latency, memory and stress-test thresholds | Instrumented device measurements required | M2/M6 |
| Recovering local device loss without manual backup | Out of scope; communicate risk clearly because cloud sync is deferred | UI and release notices |

## 12. Recommended immediate next action

Open a separate **Project Setup / Foundation Sprint** implementation chat. Bring this PRD, backlog, document-format artifacts, approved wireframes and workflow specification. Begin with FND-01 through FND-08 and FMT-01/FMT-02 as applicable. Do not start production handwriting work until setup and test fixtures are established.
