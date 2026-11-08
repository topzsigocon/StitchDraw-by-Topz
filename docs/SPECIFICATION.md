# StitchDraw by Topz — Official Project Specification

Advanced professional offline embroidery digitizing studio for Android.

## 1. Platform
- Android 10+ (minSdk 29), native, Kotlin, Gradle Kotlin DSL.
- Fully offline. No cloud dependency.

## 2. Design direction
- Dark charcoal/black workspace, high-contrast colorful vector tool icons.
- Professional toolbar, central design canvas, rulers, grids, measurement guides.
- Object and layer management.

## 3. User modes
- **Beginner**: simplified UI, guided digitizing, automatic digitizing, preset stitch configs.
- **Professional**: full manual tools, advanced stitch controls, per-stitch editing, machine configuration, production optimization.

## 4. Core features
1. **Vector editor** — PNG/JPG/SVG/PDF import, shapes, pen/Bézier, text/lettering, transforms, layers.
2. **Manual digitizing** — running, triple running, satin, tatami, zigzag, applique; custom angles; entry/exit points.
3. **Automatic digitizing** — segmentation, color separation, region detection, object recognition, stitch type + direction assignment, underlay, sequencing.
4. **Embroidery engine** — stitch generation, density/length control, underlay, pull compensation, tie-in/tie-off, jump management, trims.
5. **Simulator** — 2D rendering, realistic thread, satin/tatami textures, stitch-by-stitch playback, thread color simulation, fabric background.
6. **Thread management** — custom palettes, RGB/HEX, sequence, color changes.
7. **Machine export** — genuine encoders per real specification: DST, PES, JEF, EXP, VP3, XXX, HUS. Never rename extensions.
8. **Native format `.STD`** — preserves vector objects, stitch data, layers, thread colors, digitizing properties; editable reopen; backward compatible.
9. **Offline storage** — internal storage, Storage Access Framework, SD card, backup/recovery.

## 5. Architecture
Modules: UI (`app`), vector engine, embroidery engine, digitizing engine, rendering engine, machine export engine, file management engine, **workspace** (see §6).

## 6. Workspace Management System (CORE FEATURE)

A core feature, not optional decoration. Adapts to the full screen of phones and tablets, especially during digitizing.

### 6.1 Independence rule (key design decision)
The Workspace Management System is **independent of the embroidery engine**. Changing panel arrangement must never affect the current digitizing operation (active tool, in-progress object, undo history, selection, design data). Enforced by:
- `workspace` module depends only on `core`; it has no dependency on vector, embroidery or digitizing modules.
- `WorkspaceLayout` holds arrangement only — no design data.
- Workspace emits commands (`QuickCommand`, `MenuCommand`); the host app maps them to engine calls.
- Workspace state is serialized separately from `.STD` projects.

### 6.2 Collapsible / expandable panels
| Zone | Panels |
|---|---|
| Left | Drawing Tools, Digitizing Tools, Selection Tools, Editing Tools |
| Right | Object Manager, Layer Manager, Thread Colors, Stitch Properties, Object Properties |
| Bottom | Stitch Information, Design Measurements, Machine Information, Stitch Sequence |

Collapsed panels shrink to a small icon tab on the zone edge.

### 6.3 Floating panels
Drag and drop, resize, minimize, maximize, pin/unpin, dock/undock, close and reopen. Example: move Thread Colors next to the canvas while editing colors.

### 6.4 Contextual pop-up menus
- **Long-press object**: Edit Object, Move / Scale / Rotate, Change Stitch Type, Stitch Properties, Duplicate, Delete, Bring Forward, Send Backward.
- **Tap tool (e.g. Satin)**: Satin Settings, Stitch Density, Underlay, Pull Compensation, Stitch Direction.
- **Tap canvas**: Zoom In, Zoom Out, Fit Design, Show Grid, Show Rulers, Show Hoop.

### 6.5 Full-screen / Focus mode
Dedicated FULL SCREEN / FOCUS MODE button. When active: top toolbar, left toolbar, right panels and bottom info bar are hidden; only the canvas and design remain. A small floating control button stays for main tools; a single tap returns to the normal workspace.

### 6.6 Workspace presets and customization
Presets: **Beginner** (simplified tools), **Digitizing** (maximum canvas), **Professional** (all panels), **Stitch Editing** (stitch properties focus), **Color Editing** (thread management focus), **Full Screen** (canvas only). Users can save, restore and delete their own named layouts.

### 6.7 Touchscreen optimization
Pinch-to-zoom, two-finger pan, long-press context menu, swipe panel controls, floating quick toolbar, adjustable icon sizes, touch-friendly handles, optional stylus support.

## 7. Development rules
- Real working functionality; no placeholder buttons; no fake exports.
- Modular, maintainable Kotlin; optimized for low-RAM devices; crash-safe.
- Preserve working features and `.STD` backward compatibility across updates.
- Validate exported files with format validation and available machine-compatible readers.

## 8. GitHub workflow
`update.zip`-based source updates; `unpack.yml`; `android.yml`; Gradle wrapper; automatic APK build; build artifact publishing.

## 9. Development priority
Stable Android foundation + professional interface → vector editor → manual digitizing → stitch generation → automatic digitizing → simulation → machine export.
