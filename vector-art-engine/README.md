# Vector Art Engine (VecDraw integration)

This Android library is the first extraction stage of the updated VecDraw source into StitchDraw.

## Imported source components

- `Model.kt` — document, layer, vector object, Bézier node and compound contour model.
- `Geo.kt` — geometry construction helpers.
- `Render.kt` — Android Canvas rendering and vector export helpers.
- `SvgImport.kt` — SVG import.
- `Trace.kt` — bitmap tracing.
- `Fonts.kt` — font registration/import helpers.
- `Pat.kt` — pattern-fill helpers.

The original VecDraw app entry point and `VecCanvas` are intentionally not copied into StitchDraw. StitchDraw already owns its activity, canvas view, workspace host and panel system. The next stage is to adapt selected VecDraw canvas interactions into `DesignCanvasView` and workspace panels rather than introducing a second app shell.

## Vector-to-embroidery bridge

`VectorStitchAdapter` currently converts PATH objects into StitchDraw `DesignObject` values by sampling cubic Bézier segments in millimetres. It preserves source vector artwork and returns a separate embroidery object; it does not mutate the source document.

Current limits:
- PATH objects only. Text must first be converted to outlines; bitmap images must be traced.
- Gradient, mesh, transparency and opacity effects need manual thread/region decisions.
- Hole contours are passed through for TATAMI only, matching the current embroidery model.
- Stitch type, thread, density, width, underlay and sequencing remain explicit digitizing choices.

## Architecture rule

Keep `:vector-engine` as the pure Kotlin/JVM geometry foundation. This module contains Android-dependent VecDraw document/rendering code. Do not move Android graphics imports into `:vector-engine`.

## Phase 2 workspace integration

The app now owns a `Doc` alongside the embroidery session and renders its vector artwork on the same millimetre canvas. The top-bar **Vector** menu can add a rectangle or ellipse, show/hide vector artwork, clear vector objects without touching embroidery stitches, and convert the most recently added vector path into Running or Tatami stitches. Conversion is non-destructive: the source vector remains in the document.

The `.STD` payload now carries an optional opaque `vectorArt` JSON string using VecDraw's document schema. The file-engine deliberately treats it as a string so the existing pure Kotlin/JVM file module does not acquire Android graphics or `org.json` dependencies. Save, Save As, autosave, crash recovery and backup restore carry this field. Existing `.STD` projects without `vectorArt` remain compatible.

Current Phase 2 limits: the Vector menu currently exposes starter shape creation and conversion, not the full VecDraw node-editing toolset. SVG import, bitmap tracing, node handles, selection transforms and richer vector panels remain planned integration work.
