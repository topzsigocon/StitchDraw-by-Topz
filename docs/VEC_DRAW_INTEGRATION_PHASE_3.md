# VecDraw Integration — Phase 3: Interactive Vector Workspace

## Included

- Canvas-level vector tool modes: Select/Move, Node Edit, Rectangle, Ellipse, Hexagon, and Freehand Pencil.
- Drag previews for geometric shapes and freehand path preview.
- Vector objects are created in the existing millimetre-based `Doc` coordinate space.
- Select/Move updates node anchors and Bézier handles together, preserving curve shape.
- Node Edit moves a selected anchor and, for smooth nodes, its in/out handles.
- Hidden or locked vector layers and individually locked objects are protected from editing.
- Delete selected vector object.
- Convert the selected vector object to Running or Tatami embroidery while retaining the original vector artwork.
- Vector changes trigger project autosave and panel refresh.

## How to use

1. Open the **Vector** menu.
2. Choose **Draw Rectangle**, **Draw Ellipse**, **Draw Hexagon**, or **Freehand Pencil**.
3. Drag on the canvas to create the object. Freehand Pencil follows the drag path.
4. Choose **Select / Move** and drag inside an object to move it.
5. Choose **Node Edit**, select a node by tapping near its anchor, then drag to reshape it.
6. Use **Delete selected vector** to remove the selected vector object.
7. Select an object and choose a conversion command to create embroidery stitches without deleting the vector source.
8. Choose **Stop Vector Tool** to return to the normal embroidery canvas interaction.

## Scope and limitations

- This phase adds direct touch editing for the listed tools; it does not yet implement a full pen click-sequence, Boolean operations, multi-selection, transform handles, or vector-specific undo history.
- Freehand paths are simplified before Catmull-Rom smoothing. Very small gestures are ignored.
- Node Edit currently moves anchors and smooth-node handles as a unit; independent handle editing and cusp/symmetry controls are planned for a later phase.
- Object hit-testing uses path bounds and node proximity, so overlapping objects may require selecting from the object manager in a later phase.
- Vector selection is an editor state and is not serialized as document content.

## Signing

No signing configuration or keystore file was modified by this phase. The existing `app/stitchdraw.keystore` and signing settings from the previous repository remain the source of signing identity. Keep the repository private and do not publish the keystore or its credentials.

## Verification

Run the repository's GitHub Actions Android build and unit-test workflow after unpacking this update. A successful local Gradle build is not claimed by this package preparation.
