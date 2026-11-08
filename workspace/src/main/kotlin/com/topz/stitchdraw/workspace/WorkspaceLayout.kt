package com.topz.stitchdraw.workspace

enum class UserMode { BEGINNER, PROFESSIONAL }

/** Commands the floating quick toolbar may expose. Executed by the host app, never by the workspace. */
enum class QuickCommand(val label: String, val glyph: String) {
    UNDO("Undo", "↶"), REDO("Redo", "↷"), FINISH_OBJECT("Finish object", "✔"),
    CANCEL_OBJECT("Cancel", "✖"), ZOOM_FIT("Fit design", "⤢"), TOGGLE_GRID("Grid", "#"),
    TOOL_SELECT("Select / Pan", "☝"), TOOL_RUNNING("Running stitch", "┈"), TOOL_TRIPLE("Triple run", "≣"),
    EXIT_FOCUS("Exit focus", "⛶"), PANELS_MENU("Panels", "☷")
}

/**
 * Complete, immutable description of the arrangement of the workspace.
 * It contains NOTHING about the design being digitized.
 *
 * Zone sizes are fractions of the screen: LEFT/RIGHT = width fraction, BOTTOM = height fraction.
 */
data class WorkspaceLayout(
    val name: String,
    val mode: UserMode,
    val panels: Map<PanelId, PanelState>,
    val zoneSize: Map<DockZone, Float>,
    val focusMode: Boolean = false,
    val iconScale: Float = 1.0f,
    val quickToolbar: List<QuickCommand> = DEFAULT_QUICK,
    val quickToolbarPos: NPoint = NPoint(0.5f, 0.88f),
    val focusButtonPos: NPoint = NPoint(0.94f, 0.08f),
    val stylusOnlyDrawing: Boolean = false,
    val showTopBar: Boolean = true,
) {
    fun panel(id: PanelId): PanelState = panels.getValue(id)

    /** True when top bar, docks and bottom bar should be drawn (everything except the canvas). */
    val chromeVisible: Boolean get() = !focusMode

    /** Panels in [zone] ordered for display (closed panels excluded unless [includeClosed]). */
    fun inZone(zone: DockZone, includeClosed: Boolean = false): List<PanelState> =
        panels.values.filter { it.zone == zone && (includeClosed || it.open) }.sortedBy { it.order }

    fun sizeOf(zone: DockZone): Float = zoneSize[zone] ?: DEFAULT_ZONE_SIZE.getValue(zone)

    companion object {
        val DEFAULT_QUICK = listOf(
            QuickCommand.TOOL_SELECT, QuickCommand.TOOL_RUNNING, QuickCommand.TOOL_TRIPLE,
            QuickCommand.UNDO, QuickCommand.REDO, QuickCommand.FINISH_OBJECT,
            QuickCommand.CANCEL_OBJECT, QuickCommand.ZOOM_FIT
        )
        val DEFAULT_ZONE_SIZE = mapOf(
            DockZone.LEFT to 0.22f, DockZone.RIGHT to 0.28f,
            DockZone.BOTTOM to 0.24f, DockZone.FLOATING to 0f
        )
        const val MIN_ZONE = 0.12f
        const val MAX_ZONE = 0.55f
        const val MIN_ICON_SCALE = 0.75f
        const val MAX_ICON_SCALE = 1.75f
    }
}

/** Built-in workspace presets (Beginner / Digitizing / Professional / Stitch Editing / Color Editing / Full Screen). */
object WorkspacePresets {
    const val BEGINNER = "Beginner"
    const val DIGITIZING = "Digitizing"
    const val PROFESSIONAL = "Professional"
    const val STITCH_EDITING = "Stitch Editing"
    const val COLOR_EDITING = "Color Editing"
    const val FULL_SCREEN = "Full Screen"

    val builtInNames = listOf(BEGINNER, DIGITIZING, PROFESSIONAL, STITCH_EDITING, COLOR_EDITING, FULL_SCREEN)

    fun isBuiltIn(name: String) = name in builtInNames

    /** Builds a layout where only [visible] panels are open; others stay available but closed. */
    private fun build(
        name: String,
        mode: UserMode,
        visible: Set<PanelId>,
        collapsed: Set<PanelId> = emptySet(),
        zoneSize: Map<DockZone, Float> = WorkspaceLayout.DEFAULT_ZONE_SIZE,
        focus: Boolean = false,
        iconScale: Float = 1f,
        floating: Map<PanelId, NRect> = emptyMap(),
    ): WorkspaceLayout {
        val panels = LinkedHashMap<PanelId, PanelState>()
        val counters = HashMap<DockZone, Int>()
        for (id in PanelId.values()) {
            val floatRect = floating[id]
            val zone = if (floatRect != null) DockZone.FLOATING else id.defaultZone
            val order = counters.merge(zone, 1, Int::plus)!! - 1
            panels[id] = PanelState(
                id = id, zone = zone, order = order,
                open = id in visible, collapsed = id in collapsed,
                floatRect = floatRect ?: NRect.DEFAULT_FLOAT
            )
        }
        return WorkspaceLayout(name, mode, panels, zoneSize, focusMode = focus, iconScale = iconScale)
    }

    fun create(name: String): WorkspaceLayout? = when (name) {
        BEGINNER -> build(
            name, UserMode.BEGINNER,
            visible = setOf(PanelId.DRAWING_TOOLS, PanelId.DIGITIZING_TOOLS, PanelId.THREAD_COLORS, PanelId.STITCH_INFO),
            zoneSize = WorkspaceLayout.DEFAULT_ZONE_SIZE + (DockZone.BOTTOM to 0.16f),
            iconScale = 1.25f
        )
        DIGITIZING -> build(
            name, UserMode.PROFESSIONAL,
            visible = setOf(PanelId.DIGITIZING_TOOLS, PanelId.THREAD_COLORS, PanelId.STITCH_INFO),
            collapsed = setOf(PanelId.DIGITIZING_TOOLS, PanelId.THREAD_COLORS, PanelId.STITCH_INFO)
        )
        PROFESSIONAL -> build(name, UserMode.PROFESSIONAL, visible = PanelId.values().toSet())
        STITCH_EDITING -> build(
            name, UserMode.PROFESSIONAL,
            visible = setOf(
                PanelId.SELECTION_TOOLS, PanelId.EDITING_TOOLS, PanelId.STITCH_PROPERTIES,
                PanelId.OBJECT_PROPERTIES, PanelId.STITCH_SEQUENCE, PanelId.STITCH_INFO
            ),
            zoneSize = WorkspaceLayout.DEFAULT_ZONE_SIZE + (DockZone.RIGHT to 0.34f)
        )
        COLOR_EDITING -> build(
            name, UserMode.PROFESSIONAL,
            visible = setOf(PanelId.THREAD_COLORS, PanelId.OBJECT_MANAGER, PanelId.LAYER_MANAGER, PanelId.SELECTION_TOOLS),
            zoneSize = WorkspaceLayout.DEFAULT_ZONE_SIZE + (DockZone.RIGHT to 0.34f)
        )
        // Focus mode hides all chrome; the panels stay open+collapsed so leaving focus restores a usable workspace.
        FULL_SCREEN -> {
            val tools = setOf(PanelId.DIGITIZING_TOOLS, PanelId.THREAD_COLORS, PanelId.STITCH_INFO)
            build(name, UserMode.PROFESSIONAL, visible = tools, collapsed = tools, focus = true)
        }
        else -> null
    }

    fun default(): WorkspaceLayout = create(PROFESSIONAL)!!
}
