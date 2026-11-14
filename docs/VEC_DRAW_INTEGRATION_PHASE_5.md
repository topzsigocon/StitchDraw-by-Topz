# VecDraw Integration – Phase 5

## Advanced vector operations

This update adds advanced vector operations to the existing StitchDraw vector workspace.

### Boolean shape operations

Available from **Vector → Boolean Shape Operations**:

- **Union** – combines two closed paths.
- **Subtract** – subtracts the second selected path from the first selected path.
- **Intersect** – keeps only the overlapping area.
- **Exclude** – removes the overlapping area from both paths.

Requirements: select exactly two editable, closed PATH objects on the same visible, unlocked layer. Text, images, open paths, locked objects and cross-layer operations are rejected. Boolean output is a new editable compound path. Source objects are retained. Result contours are flattened into line nodes with a bounded 0.65 mm sampling interval; this is a deliberate trade-off for predictable editing and conversion to stitches. Inspect tight corners before machine export.

### Grouping

- Group selected editable objects on the same layer.
- Ungroup selection; all members of selected groups are ungrouped together.
- Selecting a grouped object selects its group for movement.

### Path cleanup

- Removes redundant duplicate anchors.
- Removes near-collinear anchors from entirely straight contours.
- Preserves Bézier control handles; it does not simplify curved contours.
- Applies to selected editable path objects and their compound contours.

### Safety and compatibility

- The original vector shapes remain unchanged after Boolean operations.
- Existing `.STD` serialization already stores `group`, compound contours and node coordinates; no file-format migration is introduced.
- Existing embroidery objects and the signing keystore/configuration are not changed by this patch.

## Suggested device QA

1. Draw two overlapping rectangles on the same vector layer; select both using Multi-select.
2. Run Union, Subtract, Intersect and Exclude separately. Confirm each creates a new compound path and leaves both source rectangles in place.
3. Save, close and reopen the `.STD` project; confirm the Boolean result remains editable and visible.
4. Convert a Boolean result to Running Stitch and Tatami Fill; inspect stitch order and boundary quality before exporting to a machine format.
5. Group three shapes, select one group member and drag; confirm all group members move together. Ungroup and confirm they move independently.
6. Clean up a straight freehand path and confirm redundant nodes are reduced. Clean up a Bézier path and confirm its handles remain unchanged.
7. Repeat with a locked layer and locked object; confirm editing operations are rejected.

## Build verification

This is a source update package. Run the repository's Android CI workflow to verify Kotlin compilation, unit tests and APK assembly on the current project baseline.
