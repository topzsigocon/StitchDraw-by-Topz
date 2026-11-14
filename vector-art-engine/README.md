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
