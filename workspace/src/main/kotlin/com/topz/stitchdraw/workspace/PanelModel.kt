package com.topz.stitchdraw.workspace

/** Where a panel lives. FLOATING panels are positioned freely over the canvas. */
enum class DockZone { LEFT, RIGHT, BOTTOM, FLOATING }

/** Every panel in the studio. Order inside a zone is defined by [PanelState.order]. */
enum class PanelId(val title: String, val defaultZone: DockZone, val iconGlyph: String) {
    // Left
    DRAWING_TOOLS("Drawing Tools", DockZone.LEFT, "✎"),
    DIGITIZING_TOOLS("Digitizing Tools", DockZone.LEFT, "🧵"),
    SELECTION_TOOLS("Selection Tools", DockZone.LEFT, "⬚"),
    EDITING_TOOLS("Editing Tools", DockZone.LEFT, "✂"),

    // Right
    OBJECT_MANAGER("Object Manager", DockZone.RIGHT, "▣"),
    LAYER_MANAGER("Layer Manager", DockZone.RIGHT, "☰"),
    THREAD_COLORS("Thread Colors", DockZone.RIGHT, "◉"),
    STITCH_PROPERTIES("Stitch Properties", DockZone.RIGHT, "≋"),
    OBJECT_PROPERTIES("Object Properties", DockZone.RIGHT, "⚙"),

    // Bottom
    STITCH_INFO("Stitch Information", DockZone.BOTTOM, "ℹ"),
    DESIGN_MEASUREMENTS("Design Measurements", DockZone.BOTTOM, "↔"),
    MACHINE_INFO("Machine Information", DockZone.BOTTOM, "⚒"),
    STITCH_SEQUENCE("Stitch Sequence", DockZone.BOTTOM, "≡");
}

/** A rectangle in normalized screen space (0..1 of the workspace area). */
data class NRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    /** Keeps the rect on screen and at least [MIN_W]x[MIN_H]. */
    fun clamped(): NRect {
        val nw = w.coerceIn(MIN_W, 1f)
        val nh = h.coerceIn(MIN_H, 1f)
        return NRect(x.coerceIn(0f, 1f - nw), y.coerceIn(0f, 1f - nh), nw, nh)
    }

    companion object {
        const val MIN_W = 0.14f
        const val MIN_H = 0.10f
        val DEFAULT_FLOAT = NRect(0.30f, 0.20f, 0.30f, 0.45f)
    }
}

/** Normalized point. */
data class NPoint(val x: Float, val y: Float) {
    fun clamped() = NPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/**
 * State of one panel. All flags are independent of the embroidery engine.
 *
 * - [open]: false = closed (reopen from the Panels menu).
 * - [collapsed]: docked panel reduced to its small icon tab on the zone rail.
 * - [minimized]: floating panel reduced to its title bar.
 * - [maximized]: floating panel fills the workspace.
 * - [pinned]: position and size are locked (no drag/resize/auto-rearrange).
 */
data class PanelState(
    val id: PanelId,
    val zone: DockZone,
    val order: Int,
    val open: Boolean = true,
    val collapsed: Boolean = false,
    val minimized: Boolean = false,
    val maximized: Boolean = false,
    val pinned: Boolean = false,
    val floatRect: NRect = NRect.DEFAULT_FLOAT,
)
