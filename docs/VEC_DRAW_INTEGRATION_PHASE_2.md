# VecDraw integration — Phase 2: workspace and project lifecycle

## Implemented

- Added a Vector menu to StitchDraw's existing top bar; no second Activity or workspace shell was introduced.
- `DesignCanvasView` renders the VecDraw `Doc` in the same millimetre coordinate system as the embroidery hoop and stitch plan.
- Added starter rectangle and cubic-Bézier ellipse creation.
- Added vector artwork visibility toggle and a guarded clear action that does not delete embroidery objects.
- Added non-destructive conversion of the latest vector path into Running or Tatami embroidery objects.
- Added `DigitizingSession.addExternalObject` so converted objects enter the normal undo/redo and stitch-planning lifecycle.
- Extended `.STD` with an optional `vectorArt` JSON payload while retaining the version-1 container and backward compatibility for projects without that field.
- Included vector data in Save, Save As, autosave, crash recovery and backup restore.
- Added a file-engine round-trip test for the opaque vector JSON field.

## Coordinate mapping

VecDraw document coordinates use a top-left origin. StitchDraw's hoop uses a centred origin. Rendering and conversion apply the translation `(-doc.w / 2, -doc.h / 2)` so artwork is centred over the hoop without changing the saved VecDraw geometry.

## Known limits

- The Vector menu is a starter workspace bridge, not the complete VecDraw editing UI.
- Rectangle and ellipse are the current built-in vector shape creation actions. Full pen, selection, node editing, drag transforms and context-sensitive panels remain for the next phase.
- Direct text-to-stitch conversion and image-to-stitch conversion are not enabled; text must be outlined and images traced first.
- Machine output compatibility still requires CI and physical-machine/emulator validation.

## Signing identity

`app/stitchdraw.keystore` is retained byte-for-byte from the Phase 1 package. No new key is generated and the signing configuration is unchanged. Keep this repository private and preserve a secure backup of the keystore and its credentials.

## Validation

The source changes and package structure were inspected. Local Gradle tests/build could not run because the environment could not resolve `services.gradle.org` to download Gradle 8.14.3. The GitHub Actions workflow must confirm compilation and tests before treating the update as build-verified.
