# VecDraw Integration — Phase 4

## Scope

Phase 4 adds path construction and editing operations to the existing StitchDraw vector workspace. It continues to use the existing `Doc` / `VObj` model and the current workspace canvas; it does not replace the embroidery digitizing canvas or the StitchDraw project container.

## New tools and operations

- Pen / Bézier tool: tap to place cusp anchors; drag while placing an anchor to create a paired tangent handle; tap near the first anchor to close a path; use **Stop Vector Tool / Finish Pen** to commit an open path.
- Node editing: drag anchors or incoming/outgoing handles independently. Moving an anchor carries both handles.
- Node modes: Cusp, Smooth, and Symmetric. Smooth preserves a common tangent; Symmetric also preserves equal handle lengths. Converting a cusp with no handles seeds handles from its adjacent anchors.
- Add node: tap a path segment while Node Edit is active. The closest sampled cubic segment is split with de Casteljau interpolation; taps farther than 6 mm from the path are ignored.
- Delete node: removes the selected anchor while preserving minimum point counts for open and closed paths.
- Split path: split an open path at its selected interior node.
- Join paths: joins exactly two selected open paths on the same layer, orienting endpoints to minimize the joining distance.
- Multi-select: select multiple vector objects, drag the selection as a group, align by edges/centres, and distribute horizontally or vertically.

## Project compatibility

Node JSON records now optionally include an eighth `symmetric` flag. Older seven-value node records remain readable; the new flag defaults to `false`. No project version bump is required. Existing `.STD` files and vector document JSON remain backward compatible.

## Safety and preservation

- Locked or hidden vector layers cannot be edited through the new node and path operations.
- Vector edits continue to invoke the existing autosave callback.
- Vector-to-stitch conversion remains non-destructive; source vector objects are retained.
- This patch does not include, replace, or regenerate `app/stitchdraw.keystore` or signing configuration.

## CI checks

Run the repository's existing Android CI workflow. Verify at minimum:

1. Run the repository workflow task `./gradlew test --stacktrace --continue`.
2. Run `./gradlew :app:assembleDebug --stacktrace` (the current Android CI workflow task).
3. Open an older `.STD` project and confirm it still loads.
4. Draw an open and closed Pen path; save, close, reopen, and edit nodes.
5. Convert a Bézier path to Running and Tatami stitches; confirm original vector remains.
6. Try node edits and group alignment on locked and unlocked layers.

## Known limitations

- Join paths inserts a connecting segment between the closest endpoints; it does not perform automatic tangent welding or gap snapping.
- Split path is currently limited to simple open PATH objects (not compound paths with holes).
- Automatic curve-to-stitch sampling density remains controlled by the existing vector-to-stitch adapter.
- UI interaction and Android rendering still require device testing after CI passes.
