package com.topz.stitchdraw.workspace

/** How the workspace is presented. DOCKED = rails and panels around the canvas; MENU = canvas + one ☰ button. */
enum class LayoutStyle { DOCKED, MENU }

/** One entry of the ☰ menu. Opening it lists [panels] (in this order); opening a panel shows its content. */
data class MenuCategory(
    val id: String,
    val title: String,
    val glyph: String,
    val panels: List<PanelId>,
)

/**
 * The menu tree of the "Menu" layout: categories -> panels -> (the panel's own controls).
 * Pure functions on immutable lists so that every customization (drag to reorder, move a panel to
 * another category) can be tested without Android. Invariant kept by [normalize]: every [PanelId]
 * appears exactly once in the whole tree.
 */
object MenuTree {
    val DEFAULT: List<MenuCategory> = listOf(
        MenuCategory(
            "tools", "Tools", "✎",
            listOf(PanelId.DRAWING_TOOLS, PanelId.DIGITIZING_TOOLS, PanelId.SELECTION_TOOLS, PanelId.EDITING_TOOLS)
        ),
        MenuCategory(
            "objects", "Objects", "▣",
            listOf(PanelId.OBJECT_MANAGER, PanelId.LAYER_MANAGER, PanelId.OBJECT_PROPERTIES, PanelId.STITCH_PROPERTIES)
        ),
        MenuCategory("colors", "Colors", "◉", listOf(PanelId.THREAD_COLORS)),
        MenuCategory(
            "info", "Info", "ℹ",
            listOf(PanelId.STITCH_INFO, PanelId.DESIGN_MEASUREMENTS, PanelId.MACHINE_INFO, PanelId.STITCH_SEQUENCE)
        ),
    )

    private fun defaultCategoryOf(id: PanelId): String =
        DEFAULT.firstOrNull { id in it.panels }?.id ?: DEFAULT.last().id

    private fun defaultInfo(id: String): MenuCategory? = DEFAULT.firstOrNull { it.id == id }

    /**
     * Makes [input] valid: unique category ids, each panel exactly once (first occurrence wins),
     * missing panels appended to their default category (re-created if it was lost), and the four
     * default categories never disappear. Order chosen by the user is preserved.
     */
    fun normalize(input: List<MenuCategory>): List<MenuCategory> {
        val seenPanels = HashSet<PanelId>()
        val seenCats = HashSet<String>()
        val out = ArrayList<MenuCategory>()
        for (c in input) {
            if (c.id.isBlank() || !seenCats.add(c.id)) continue
            val ps = c.panels.filter { seenPanels.add(it) }
            out.add(c.copy(panels = ps))
        }
        for (d in DEFAULT) if (seenCats.add(d.id)) out.add(d.copy(panels = emptyList()))
        for (id in PanelId.values()) {
            if (id in seenPanels) continue
            val target = defaultCategoryOf(id)
            val i = out.indexOfFirst { it.id == target }.takeIf { it >= 0 } ?: out.lastIndex
            out[i] = out[i].copy(panels = out[i].panels + id)
        }
        return out
    }

    /** Moves [panel] into category [toCategory] at [index] (clamped). Unknown category = no change. */
    fun movePanel(menu: List<MenuCategory>, panel: PanelId, toCategory: String, index: Int): List<MenuCategory> {
        if (menu.none { it.id == toCategory }) return menu
        val stripped = menu.map { it.copy(panels = it.panels.filter { p -> p != panel }) }
        return stripped.map {
            if (it.id != toCategory) it
            else it.copy(panels = it.panels.toMutableList().apply { add(index.coerceIn(0, size), panel) })
        }
    }

    /** Moves a whole category to list position [index] (clamped). */
    fun moveCategory(menu: List<MenuCategory>, category: String, index: Int): List<MenuCategory> {
        val c = menu.firstOrNull { it.id == category } ?: return menu
        val rest = menu.filter { it.id != category }
        return rest.toMutableList().apply { add(index.coerceIn(0, size), c) }
    }

    /** Categories that have something to show (empty ones are hidden from the popup). */
    fun visible(menu: List<MenuCategory>): List<MenuCategory> = menu.filter { it.panels.isNotEmpty() }

    /** Rows for the drag-to-reorder editor: header rows and panel rows in display order. */
    sealed interface Row {
        data class Header(val category: MenuCategory) : Row
        data class Item(val categoryId: String, val panel: PanelId) : Row
    }

    fun rows(menu: List<MenuCategory>): List<Row> = menu.flatMap { c ->
        listOf<Row>(Row.Header(c)) + c.panels.map { Row.Item(c.id, it) }
    }

    /**
     * Rebuilds the tree from the editor's row list after the user dragged rows around. A panel row
     * belongs to the header above it; panels dragged above the first header go to the first category.
     */
    fun fromRows(rows: List<Row>, previous: List<MenuCategory>): List<MenuCategory> {
        val byId = previous.associateBy { it.id }
        val out = ArrayList<MenuCategory>()
        val pending = ArrayList<PanelId>()
        for (r in rows) {
            when (r) {
                is Row.Header -> out.add((byId[r.category.id] ?: r.category).copy(panels = emptyList()))
                is Row.Item -> if (out.isEmpty()) pending.add(r.panel)
                    else out[out.lastIndex] = out.last().copy(panels = out.last().panels + r.panel)
            }
        }
        if (out.isNotEmpty() && pending.isNotEmpty()) out[0] = out[0].copy(panels = pending + out[0].panels)
        return normalize(out)
    }

    fun titleOf(id: String): String = defaultInfo(id)?.title ?: id
}
