package com.topz.stitchdraw.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuTreeTest {
    private fun all(m: List<MenuCategory>) = m.flatMap { it.panels }
    private fun assertValid(m: List<MenuCategory>) {
        assertEquals("each panel exactly once", PanelId.values().toSet(), all(m).toSet())
        assertEquals(PanelId.values().size, all(m).size)
        assertEquals(m.map { it.id }.toSet().size, m.size)
    }

    @Test fun defaultTreeHoldsEveryPanelOnce() = assertValid(MenuTree.DEFAULT)

    @Test fun normalizeRepairsDuplicatesMissingAndUnknown() {
        val broken = listOf(
            MenuCategory("tools", "Tools", "✎", listOf(PanelId.DRAWING_TOOLS, PanelId.DRAWING_TOOLS)),
            MenuCategory("tools", "again", "x", listOf(PanelId.EDITING_TOOLS)),
            MenuCategory("", "blank", "x", listOf(PanelId.MACHINE_INFO)),
        )
        val n = MenuTree.normalize(broken)
        assertValid(n)
        assertEquals(listOf(PanelId.DRAWING_TOOLS), n.first { it.id == "tools" }.panels.filter { it == PanelId.DRAWING_TOOLS })
        assertTrue(MenuTree.DEFAULT.all { d -> n.any { it.id == d.id } })
    }

    @Test fun movePanelAcrossCategories() {
        val m = MenuTree.movePanel(MenuTree.DEFAULT, PanelId.THREAD_COLORS, "tools", 1)
        assertValid(m)
        assertEquals(listOf(PanelId.DRAWING_TOOLS, PanelId.THREAD_COLORS, PanelId.DIGITIZING_TOOLS, PanelId.SELECTION_TOOLS, PanelId.EDITING_TOOLS),
            m.first { it.id == "tools" }.panels)
        assertTrue(m.first { it.id == "colors" }.panels.isEmpty())
        assertFalse(MenuTree.visible(m).any { it.id == "colors" })
    }

    @Test fun movePanelWithinCategoryAndClamp() {
        val m = MenuTree.movePanel(MenuTree.DEFAULT, PanelId.EDITING_TOOLS, "tools", -5)
        assertEquals(PanelId.EDITING_TOOLS, m.first { it.id == "tools" }.panels.first())
        val e = MenuTree.movePanel(MenuTree.DEFAULT, PanelId.DRAWING_TOOLS, "tools", 99)
        assertEquals(PanelId.DRAWING_TOOLS, e.first { it.id == "tools" }.panels.last())
        assertEquals(MenuTree.DEFAULT, MenuTree.movePanel(MenuTree.DEFAULT, PanelId.DRAWING_TOOLS, "nope", 0))
    }

    @Test fun moveCategory() {
        val m = MenuTree.moveCategory(MenuTree.DEFAULT, "info", 0)
        assertEquals(listOf("info", "tools", "objects", "colors"), m.map { it.id })
        assertValid(m)
    }

    @Test fun rowsRoundTripAndDragRebuildsTree() {
        val rows = MenuTree.rows(MenuTree.DEFAULT)
        assertEquals(MenuTree.DEFAULT, MenuTree.fromRows(rows, MenuTree.DEFAULT))
        // the editor drags the "Thread Colors" row to the top of the list (above every header)
        val moved = rows.toMutableList()
        val i = moved.indexOfFirst { it is MenuTree.Row.Item && it.panel == PanelId.THREAD_COLORS }
        moved.add(0, moved.removeAt(i))
        val m = MenuTree.fromRows(moved, MenuTree.DEFAULT)
        assertValid(m)
        assertEquals(PanelId.THREAD_COLORS, m.first().panels.first())
        // dragging a header moves the whole category with its panels
        val h = rows.toMutableList()
        val hi = h.indexOfFirst { it is MenuTree.Row.Header && it.category.id == "info" }
        val block = h.subList(hi, h.size).toList()
        repeat(block.size) { h.removeAt(hi) }
        h.addAll(0, block)
        assertEquals("info", MenuTree.fromRows(h, MenuTree.DEFAULT).first().id)
    }

    @Test fun reducerActionsKeepTreeValidAndSurviveSerialization() {
        val c = WorkspaceController(WorkspacePresets.create(WorkspacePresets.MENU)!!)
        c.dispatch(WorkspaceAction.MoveMenuPanel(PanelId.STITCH_INFO, "tools", 0))
        c.dispatch(WorkspaceAction.MoveMenuCategory("colors", 0))
        c.dispatch(WorkspaceAction.SetMenuQuick(listOf(QuickCommand.UNDO, QuickCommand.UNDO, QuickCommand.TOGGLE_FOCUS)))
        assertValid(c.layout.menu)
        assertEquals(listOf(QuickCommand.UNDO, QuickCommand.TOGGLE_FOCUS), c.layout.menuQuick)
        val text = WorkspaceSerializer.encodeState(c.layout, emptyMap())
        val back = WorkspaceSerializer.decodeState(text)!!.first
        assertEquals(c.layout.menu.map { it.id to it.panels }, back.menu.map { it.id to it.panels })
        assertEquals(LayoutStyle.MENU, back.style)
        assertEquals(c.layout.menuQuick, back.menuQuick)
        c.dispatch(WorkspaceAction.ResetMenu)
        assertEquals(MenuTree.DEFAULT, c.layout.menu)
    }

    @Test fun oldLayoutFileWithoutMenuFieldsStillLoads() {
        val old = WorkspaceSerializer.encodeLayout(WorkspacePresets.default())
            .replace(Regex("\"menu\"[\\s\\S]*?\\]\\s*\\}\\s*\\]\\s*,"), "")
        val l = WorkspaceSerializer.decodeLayout(old)!!
        assertEquals(LayoutStyle.DOCKED, l.style)
        assertValid(l.menu)
    }

    @Test fun menuPresetOpensOneSheetAtATime() {
        val c = WorkspaceController(WorkspacePresets.create(WorkspacePresets.MENU)!!)
        assertTrue(c.layout.panels.values.none { it.open })
        c.dispatch(WorkspaceAction.OpenExclusive(PanelId.THREAD_COLORS))
        c.dispatch(WorkspaceAction.OpenExclusive(PanelId.STITCH_INFO))
        assertEquals(listOf(PanelId.STITCH_INFO), c.layout.panels.values.filter { it.open }.map { it.id })
        assertEquals(DockZone.FLOATING, c.layout.panel(PanelId.STITCH_INFO).zone)
    }

    @Test fun compactExpandsOnePanelPerZone() {
        val c = WorkspaceController(WorkspacePresets.create(WorkspacePresets.COMPACT)!!)
        assertTrue(c.layout.panels.values.all { it.open && it.collapsed })
        c.dispatch(WorkspaceAction.ToggleCollapsed(PanelId.DRAWING_TOOLS))
        c.dispatch(WorkspaceAction.ToggleCollapsed(PanelId.EDITING_TOOLS))
        val expanded = c.layout.inZone(DockZone.LEFT).filter { !it.collapsed }.map { it.id }
        assertEquals(listOf(PanelId.EDITING_TOOLS), expanded)
        c.dispatch(WorkspaceAction.ToggleCollapsed(PanelId.THREAD_COLORS))   // another zone is independent
        assertEquals(1, c.layout.inZone(DockZone.RIGHT).count { !it.collapsed })
        assertEquals(1, c.layout.inZone(DockZone.LEFT).count { !it.collapsed })
    }

    @Test fun applyingPresetKeepsUsersMenuOrder() {
        val c = WorkspaceController()
        c.dispatch(WorkspaceAction.MoveMenuCategory("info", 0))
        c.applyPreset(WorkspacePresets.MENU)
        assertEquals("info", c.layout.menu.first().id)
        assertEquals(LayoutStyle.MENU, c.layout.style)
    }
}
