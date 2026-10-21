# Notebook App - UI/UX Design System & Interaction Specification V0.3

**Date:** 2026-10-21  
**Status:** Planning draft; only entries marked **Confirmed** are approved product requirements  
**Supersedes:** Toolbar placement choice in `notebook_ui_ux_wireframes_spec_v0_2.md`  
**Related:** `notebook_ui_ux_wireframes_spec_v0_2.md`, `notebook_ui_ux_plan_v0_1.md`, `notebook_document_model_spec_v0_2.md`, `notebook_library_storage_plan_v0_1.md`

## 1. Confirmed UI baseline

- The V0.2 wireframes are accepted as the baseline for information architecture and basic screen composition, **not final visual assets or production implementation**.
- **Fixed drawing toolbar at the top in V1**; no draggable or dockable toolbar behavior. Fixed means anchored to the editor header/canvas layout rather than overlaid onto notes.
- iPad-first native SwiftUI + UIKit editor view composition; native Kotlin + Jetpack Compose on Android tablets and phones.
- Library → nested folder(s) → notebook → note → pages, with Quick Notes, Recent, All Notes, Trash.
- Note editor with fixed-size, bounded pages in continuous vertical scrolling and free zoom/pan.
- Editing modes include pen/pencil, highlighter, both erasers, unified page-local lasso, geometric shapes, text and images.
- PDF as immutable source page background, with editable annotations on top.
- Local-first, transaction-backed autosave, explicit save failure visibility; editable note export and full-library backup/restore in V1.
- No handwriting engine commitment yet: PencilKit / Jetpack Ink preferred candidates; evaluation deferred.

## 2. Information architecture and navigation rules

**Wide window (iPad / Android tablet):** persistent-or-collapsible library sidebar and a notebook contents pane. Selecting Recent or All Notes changes only the query/view, not the documents. Entering the editor prioritizes maximum canvas area; library sidebar is not permanently overlaid in the editor.

**Compact window (phone / narrow iPad):** stacked library → notebook → editor navigation with clear back behavior. Do not force a multi-column layout because the physical device is a tablet. Derive mode from available window size; consider vertical constraints as well.

**New Note action:** when launched from a notebook, use that notebook; when launched globally, create in Quick Notes. Create immediately using configured defaults, with no mandatory title/template dialog (**Proposed; pending confirmation**).

**Returning to library:** preserve the prior notebook/filter and scroll position where feasible. Opening a note uses previous editor location when practical, otherwise fit-to-width and first page.

## 3. Editor visual structure

Three contiguous areas, from top to bottom:

1. **Editor header:** Back, editable note title, unobtrusive saving status, undo, redo, page organizer, overflow/export. Always readable and outside page drawing coordinates.
2. **Fixed tool strip:** pen, pencil, highlighter, eraser, lasso, shapes, text, image. Accessible even while zooming or scrolling. On narrow widths, use horizontally scrollable/overflowed tools without shrinking interactive targets.
3. **Canvas viewport:** fixed-size pages, continuous scroll, optional thumbnail panel, no persistent floating tool palette on top of content.

Select a tool with a single tap. Tapping the active tool again opens its settings (e.g. pen presets, eraser mode or shape type) (**Proposed**). A selected tool remains visibly selected. Presets (last used color and width per tool) should survive navigation (**Proposed**).

### Tool settings

| Tool | Contextual settings | Interaction note |
|---|---|---|
| Pen/pencil | Color, thickness | Brush rendering subject to later technical prototype |
| Highlighter | Color, width, opacity | Visible feedback before and after stroke completion |
| Eraser | Partial / whole stroke, size | Partial mode is the V1 default |
| Lasso | Cut, copy, paste, delete, style/arrange | Select all editable kinds, one page at a time |
| Shapes | Line, rectangle, ellipse, arrow; color, outline, fill | Saved as semantic shapes |
| Text | Font family/system fallback, size, color, alignment, whole-box style | Rich spans deferred |
| Image | Choose asset, placement, resize | Crop is an optional refinement requiring scope check |

## 4. Page and document navigation

**Proposed V1 default:** page thumbnails are **collapsed on editor entry** and open as a **collapsible left-side panel** on wide layouts; use a bottom sheet on compact layouts. The page strip must not cover or erase content. Close panel restores full canvas width while preserving the visible page focus where practical.

Provide add page, duplicate, reorder, delete, navigate to page. Show page number and page size in the organizer. PDF pages can differ in dimensions; layout must adapt. Creation of a fresh note uses the last-selected or configured paper template; subsequent changes to template must not distort content (**Proposed**).

**Special close-up / auto-advance zoom writing box:** defer to post-V1 (**Proposed**). Standard pinch zoom, free pan and continuous scroll remain essential V1 functionality.

## 5. Selection, transforms and direct manipulation

- Lasso selects handwriting, shapes, text boxes and image elements on the originating page; selected content remains separate elements.
- Tap for single selection, lasso for multiple; allow drag movement, proportional mixed selection resize, cut/copy/paste and deletion.
- Selection overlay is editor interaction state and should not serialize into a saved note.
- Page-local selection V1; allow cross-page transfer using clipboard semantics. Internal model retains IDs and page ownership so cross-page dragging can be added later.
- Gesture ownership must be explicit: stylus editing; finger viewport navigation on stylus tablets by default; phone supports explicit finger-writing mode. Coordinate conversion remains independent of rendered pixel density.

## 6. Visual design tokens (initial, not branding approval)

Use **semantic tokens**, with separate implementations on Apple and Android. Avoid hard-coded colors on drawing elements; document colors are user content, not UI theme colors.

| Token | Initial guidance |
|---|---|
| `surface.canvasSurround` | Neutral low-contrast grey, distinct from the page |
| `surface.page` | Warm white/white in both light and dark system themes, unless page template explicitly says otherwise |
| `surface.panel` | System-adaptive surface |
| `text.primary` / `text.secondary` | Accessible semantic text styles |
| `accent.action` | One restrained accent for active/primary controls; hue TBD |
| `stroke.separator` | Low-contrast divider |
| `state.error` | Accessible error color plus icon/text |
| `focus.visible` | Visible keyboard/accessibility focus |
| `spacing` | Base 4-point/dp increments; common 8, 12, 16, 24 gaps |
| `radius` | Small for tool buttons, medium for cards/popovers; avoid gratuitous rounding |
| `type` | Platform system fonts and text styles; Dynamic Type/font scaling where appropriate |
| `icon` | SF Symbols on Apple; Material Symbols or matching platform icons on Android |

Use platform-specific semantic tokens rather than forcing identical typography/controls pixel-for-pixel. The notebook's paper color, line pattern, image colors and ink colors belong to note data and **must not change just because the app switches to dark mode**.

### Interaction size and accessibility

- Minimum actionable target **44 × 44 pt on Apple**, **48 × 48 dp on Android**, including compact toolbar icons (visual glyph may be smaller).
- Provide accessibility labels, tool state announcements, keyboard focus and visible selection/focus states.
- Support VoiceOver and TalkBack for library, toolbars, dialogs, page navigation and text boxes. Handwriting accessibility requires additional exploration and should be documented honestly.
- Design for left/right-handed use and screen-edge palm rest; test side panel placement and toolbar interaction on both.
- Save errors must persist as actionable status, not only an ephemeral toast or color indicator.

References: Apple HIG Buttons, Android Compose Accessibility API Defaults, Android Adaptive Apps.

## 7. Adaptive layout contract

| Form factor / window | Library | Editor tools | Page list / dialogs |
|---|---|---|---|
| iPad wide | Sidebar + detail | Fixed top strip | Left collapsible panel; popovers |
| iPad narrow / multitasking | Adaptive one-/two-pane | Fixed top strip with overflow | Panel or sheet |
| Android tablet wide | List/detail | Fixed top strip | Left collapsible panel; popovers |
| Android phone | Navigation stack | Fixed top compact/scrollable strip | Bottom sheet |

Do not freeze device labels into breakpoints. Determine UI from available width and height and test window resizing/rotation, including preservation of zoom, active tool, selection and pending text edits.

## 8. Reliability & trust UX

- Completed document edits have states equivalent to `unsaved`, `saving`, `saved`, `error`. Avoid displaying a misleading 'Saved' state before commit.
- If leaving the editor with pending writes, arrange a safe flush/recovery path; if failed, show an actionable error.
- **Back Up Library** and **Restore Library** appear under Settings → Data & Backup. Show selected file and metadata, note counts, storage estimate and compatibility before restore. Never silently overwrite existing notes; on a populated library, restore as distinct hierarchy with remapped IDs.
- Distinguish manual backup from automatic cloud sync (not in V1).
- Confirm destructive delete, support Trash restoration, avoid losing assets still referenced by a note or Trash.

## 9. Component inventory for implementation

`LibraryShell`, `FolderTree`, `NotebookHeader`, `NoteListItem`, `NoteThumbnail`, `RecentNotes`, `SearchField`, `EditorShell`, `EditorHeader`, `FixedToolStrip`, `ToolOptionsPopover`, `CanvasViewportHost`, `PageThumbnailPanel`, `SelectionOverlay`, `SaveStatus`, `BackupRestoreFlow`, `EmptyState`, `ErrorBanner`, `ConfirmationDialog`.

Define UI contracts as behaviors plus UI state; the Swift and Kotlin view implementations are independent. Keep drawing engine-specific views behind platform adapters.

## 10. Acceptance criteria before UI freeze

1. All primary screens match the V0.2 layout and V0.3 interaction states in wide and compact windows.
2. Fixed toolbar remains fully operable in iPad portrait and Android phone landscape without reducing hit targets.
3. New note can be created and entered immediately; editable title can be changed later.
4. Page organizer can open/close without losing the active page; different PDF page sizes render without unexpected clipping.
5. Selected drawing tool and current preset remain obvious; context settings don't intercept active stylus strokes.
6. Autosave failures stay visible and offer retry/recovery path.
7. VoiceOver and TalkBack can identify active tools and operate basic library/page navigation.
8. Switching dark/light app theme does not unexpectedly change saved ink or page colors.
9. Backup preview clearly identifies that restored data will be added instead of silently replacing existing notes.

## 11. Decisions to confirm with product owners

| Topic | Recommended V1 choice | Status |
|---|---|---|
| Drawing toolbar | Fixed at top | **Confirmed** |
| Page thumbnails | Collapsed by default; left panel on tablet / bottom sheet on phone | **Proposed** |
| New note creation | Immediate using default paper/template; no setup modal | **Proposed** |
| Active tool settings | Tap active tool to open contextual controls; remember per-tool presets | **Proposed** |
| Close-up zoom box / auto-advance | Post-V1; standard pinch zoom in V1 | **Proposed** |
| Theme branding | System-adaptive neutral base; accent color to be selected later | **Proposed** |

## 12. Next work after sign-off

1. Finalize the proposed behaviors above and the accessible visual token palette.
2. Expand the interactive prototype to cover those states, if visual review is needed.
3. Create platform-oriented component contracts and corresponding GitHub backlog issues.
4. Plan CI, cloud Mac/Xcode workflow, native project creation and release checks; no handwriting engine evaluation until the agreed later stage.
