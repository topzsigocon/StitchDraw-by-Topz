package com.topz.stitchdraw.workspace

/** Identifiers of actions a context menu item triggers. The host app maps them onto engine calls. */
enum class MenuCommand {
    // Embroidery object
    EDIT_OBJECT, TRANSFORM_OBJECT, CHANGE_STITCH_TYPE, STITCH_PROPERTIES, DUPLICATE, DELETE, BRING_FORWARD, SEND_BACKWARD,
    // Tool settings
    TOOL_SETTINGS, STITCH_DENSITY, UNDERLAY, PULL_COMPENSATION, STITCH_DIRECTION, STITCH_LENGTH,
    // Canvas
    ZOOM_IN, ZOOM_OUT, FIT_DESIGN, TOGGLE_GRID, TOGGLE_RULERS, TOGGLE_HOOP
}

data class ContextMenuItem(
    val command: MenuCommand,
    val label: String,
    val enabled: Boolean = true,
    /** Non-null for toggles (grid/rulers/hoop). */
    val checked: Boolean? = null,
)

/** Tools that have a settings menu. Kept as workspace-local ids to avoid depending on any engine. */
enum class ToolMenuKind { RUNNING, TRIPLE_RUNNING, SATIN, TATAMI, ZIGZAG, APPLIQUE, GENERIC }

data class CanvasViewState(val gridVisible: Boolean, val rulersVisible: Boolean, val hoopVisible: Boolean)

/** Builds the contextual menus described in the specification. Pure and engine-independent. */
object ContextMenus {

    /** Long press on an embroidery object. */
    fun forObject(canBringForward: Boolean = true, canSendBackward: Boolean = true) = listOf(
        ContextMenuItem(MenuCommand.EDIT_OBJECT, "Edit Object"),
        ContextMenuItem(MenuCommand.TRANSFORM_OBJECT, "Move / Scale / Rotate…"),
        ContextMenuItem(MenuCommand.CHANGE_STITCH_TYPE, "Change Stitch Type"),
        ContextMenuItem(MenuCommand.STITCH_PROPERTIES, "Stitch Properties"),
        ContextMenuItem(MenuCommand.DUPLICATE, "Duplicate"),
        ContextMenuItem(MenuCommand.DELETE, "Delete"),
        ContextMenuItem(MenuCommand.BRING_FORWARD, "Bring Forward", enabled = canBringForward),
        ContextMenuItem(MenuCommand.SEND_BACKWARD, "Send Backward", enabled = canSendBackward),
    )

    /** Tap on a tool in the digitizing toolbar. Satin has the full list from the spec. */
    fun forTool(kind: ToolMenuKind): List<ContextMenuItem> = when (kind) {
        ToolMenuKind.SATIN, ToolMenuKind.TATAMI, ToolMenuKind.APPLIQUE -> listOf(
            ContextMenuItem(MenuCommand.TOOL_SETTINGS, "${name(kind)} Settings"),
            ContextMenuItem(MenuCommand.STITCH_DENSITY, "Stitch Density"),
            ContextMenuItem(MenuCommand.UNDERLAY, "Underlay"),
            ContextMenuItem(MenuCommand.PULL_COMPENSATION, "Pull Compensation"),
            ContextMenuItem(MenuCommand.STITCH_DIRECTION, "Stitch Direction"),
        )
        ToolMenuKind.RUNNING, ToolMenuKind.TRIPLE_RUNNING, ToolMenuKind.ZIGZAG -> listOf(
            ContextMenuItem(MenuCommand.TOOL_SETTINGS, "${name(kind)} Settings"),
            ContextMenuItem(MenuCommand.STITCH_LENGTH, "Stitch Length"),
        )
        ToolMenuKind.GENERIC -> listOf(ContextMenuItem(MenuCommand.TOOL_SETTINGS, "Tool Settings"))
    }

    /** Tap/long-press on empty canvas. */
    fun forCanvas(s: CanvasViewState) = listOf(
        ContextMenuItem(MenuCommand.ZOOM_IN, "Zoom In"),
        ContextMenuItem(MenuCommand.ZOOM_OUT, "Zoom Out"),
        ContextMenuItem(MenuCommand.FIT_DESIGN, "Fit Design"),
        ContextMenuItem(MenuCommand.TOGGLE_GRID, "Show Grid", checked = s.gridVisible),
        ContextMenuItem(MenuCommand.TOGGLE_RULERS, "Show Rulers", checked = s.rulersVisible),
        ContextMenuItem(MenuCommand.TOGGLE_HOOP, "Show Hoop", checked = s.hoopVisible),
    )

    private fun name(k: ToolMenuKind) = when (k) {
        ToolMenuKind.RUNNING -> "Running Stitch"
        ToolMenuKind.TRIPLE_RUNNING -> "Triple Run"
        ToolMenuKind.SATIN -> "Satin"
        ToolMenuKind.TATAMI -> "Tatami"
        ToolMenuKind.ZIGZAG -> "Zigzag"
        ToolMenuKind.APPLIQUE -> "Applique"
        ToolMenuKind.GENERIC -> "Tool"
    }
}
