# VecDraw integration — Phase 1

## Included

- Added Android library module `:vector-art-engine`.
- Imported the updated VecDraw document/model, geometry, renderer, SVG importer, bitmap tracer, font manager and pattern helpers.
- Added a non-destructive PATH-to-embroidery adapter.
- Added the library as an app dependency so the next UI integration can consume it.
- Kept StitchDraw's existing app ID, versioning, workspace, embroidery modules, `.STD` project flow and signing configuration unchanged.

## Signing identity preservation

The existing StitchDraw signing keystore at `app/stitchdraw.keystore` was retained byte-for-byte. Its SHA-256 certificate fingerprint was checked before packaging:

`C0:88:69:3D:D2:8C:87:6F:2C:54:75:26:A0:DF:54:9A:21:64:98:51:2B:48:EC:30:6E:16:AF:DB:1C:8E:70:01`

The updated VecDraw archive supplied for this integration does not contain a VecDraw `.jks`/`.keystore` or key properties. Therefore this package preserves StitchDraw's existing signing identity; it does not claim to restore or transfer a separate VecDraw signing identity. If the intent is to preserve the standalone VecDraw app's update identity too, its original keystore or configured GitHub secrets must be recovered before changing that app's release-signing setup.

## Build status

The source package was structurally prepared, but local Gradle compilation could not be completed because the environment could not resolve `services.gradle.org` to download the Gradle wrapper distribution. Run the repository's GitHub Actions build to validate compilation. Do not treat this package as a confirmed successful build until CI passes.

## Next phase

1. Add vector document state to the workspace/project lifecycle and `.STD` serialization.
2. Add a vector panel/tool mode to the existing workspace.
3. Adapt node editing, selection, transform and drawing gestures into `DesignCanvasView`.
4. Add explicit conversion preview and stitch parameter controls.
5. Add round-trip and device tests before enabling broader conversion/export actions.
