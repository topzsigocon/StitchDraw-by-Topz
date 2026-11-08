package com.topz.stitchdraw.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTest {
    private fun ctrl() = WorkspaceController(WorkspacePresets.default())

    @Test fun collapseAndExpandDockedPanel() {
        val c = ctrl()
        assertTrue(c.dispatch(WorkspaceAction.SetCollapsed(PanelId.THREAD_COLORS, true)))
        assertTrue(c.layout.panel(PanelId.THREAD_COLORS).collapsed)
        c.dispatch(WorkspaceAction.ToggleCollapsed(PanelId.THREAD_COLORS))
        assertFalse(c.layout.panel(PanelId.THREAD_COLORS).collapsed)
    }

    @Test fun noOpDoesNotNotify() {
        val c = ctrl()
        var calls = 0
        c.addListener { _, _ -> calls++ }
        assertFalse(c.dispatch(WorkspaceAction.SetCollapsed(PanelId.THREAD_COLORS, false)))
        assertEquals(0, calls)
    }

    @Test fun zoneCollapseAffectsOnlyThatZone() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.SetZoneCollapsed(DockZone.RIGHT, true))
        c.layout.inZone(DockZone.RIGHT).forEach { assertTrue(it.collapsed) }
        c.layout.inZone(DockZone.LEFT).forEach { assertFalse(it.collapsed) }
    }

    @Test fun undockMakesFloatingAndDockReturns() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.Undock(PanelId.THREAD_COLORS, NRect(0.4f, 0.3f, 0.3f, 0.3f)))
        val p = c.layout.panel(PanelId.THREAD_COLORS)
        assertEquals(DockZone.FLOATING, p.zone)
        assertEquals(NRect(0.4f, 0.3f, 0.3f, 0.3f), p.floatRect)
        c.dispatch(WorkspaceAction.Dock(PanelId.THREAD_COLORS, DockZone.LEFT, 0))
        assertEquals(DockZone.LEFT, c.layout.panel(PanelId.THREAD_COLORS).zone)
        assertEquals(0, c.layout.panel(PanelId.THREAD_COLORS).order)
        assertContiguous(c.layout)
    }

    @Test fun floatingGeometryIsClampedOnScreen() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.Undock(PanelId.OBJECT_MANAGER))
        c.dispatch(WorkspaceAction.SetFloatRect(PanelId.OBJECT_MANAGER, NRect(0.9f, 0.95f, 0.5f, 0.5f)))
        val r = c.layout.panel(PanelId.OBJECT_MANAGER).floatRect
        assertTrue(r.x >= 0f && r.y >= 0f && r.x + r.w <= 1.0001f && r.y + r.h <= 1.0001f)
        c.dispatch(WorkspaceAction.SetFloatRect(PanelId.OBJECT_MANAGER, NRect(0.1f, 0.1f, 0.001f, 0.001f)))
        assertEquals(NRect.MIN_W, c.layout.panel(PanelId.OBJECT_MANAGER).floatRect.w, 0f)
    }

    @Test fun pinnedPanelCannotMoveResizeDockOrClose() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.Undock(PanelId.THREAD_COLORS))
        val before = c.layout.panel(PanelId.THREAD_COLORS).floatRect
        c.dispatch(WorkspaceAction.SetPinned(PanelId.THREAD_COLORS, true))
        assertFalse(c.dispatch(WorkspaceAction.SetFloatRect(PanelId.THREAD_COLORS, NRect(0.5f, 0.5f, 0.2f, 0.2f))))
        assertFalse(c.dispatch(WorkspaceAction.Dock(PanelId.THREAD_COLORS, DockZone.RIGHT)))
        assertFalse(c.dispatch(WorkspaceAction.Close(PanelId.THREAD_COLORS)))
        assertEquals(before, c.layout.panel(PanelId.THREAD_COLORS).floatRect)
        c.dispatch(WorkspaceAction.SetPinned(PanelId.THREAD_COLORS, false))
        assertTrue(c.dispatch(WorkspaceAction.Close(PanelId.THREAD_COLORS)))
    }

    @Test fun minimizeMaximizeAreMutuallyExclusiveAndFloatingOnly() {
        val c = ctrl()
        assertFalse(c.dispatch(WorkspaceAction.SetMinimized(PanelId.OBJECT_MANAGER, true))) // docked
        c.dispatch(WorkspaceAction.Undock(PanelId.OBJECT_MANAGER))
        c.dispatch(WorkspaceAction.SetMinimized(PanelId.OBJECT_MANAGER, true))
        c.dispatch(WorkspaceAction.SetMaximized(PanelId.OBJECT_MANAGER, true))
        val p = c.layout.panel(PanelId.OBJECT_MANAGER)
        assertTrue(p.maximized); assertFalse(p.minimized)
    }

    @Test fun closeAndReopen() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.Close(PanelId.LAYER_MANAGER))
        assertFalse(c.layout.panel(PanelId.LAYER_MANAGER).open)
        assertFalse(c.layout.inZone(DockZone.RIGHT).any { it.id == PanelId.LAYER_MANAGER })
        c.dispatch(WorkspaceAction.Open(PanelId.LAYER_MANAGER))
        assertTrue(c.layout.panel(PanelId.LAYER_MANAGER).open)
    }

    @Test fun raiseBringsFloatingToFront() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.Undock(PanelId.OBJECT_MANAGER))
        c.dispatch(WorkspaceAction.Undock(PanelId.LAYER_MANAGER))
        c.dispatch(WorkspaceAction.Raise(PanelId.OBJECT_MANAGER))
        assertEquals(PanelId.OBJECT_MANAGER, c.layout.inZone(DockZone.FLOATING).last().id)
        assertContiguous(c.layout)
    }

    @Test fun reorderWithinZone() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.Reorder(PanelId.OBJECT_PROPERTIES, 0))
        assertEquals(PanelId.OBJECT_PROPERTIES, c.layout.inZone(DockZone.RIGHT).first().id)
        assertContiguous(c.layout)
    }

    @Test fun zoneResizeIsClamped() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.ResizeZone(DockZone.LEFT, 0.9f))
        assertEquals(WorkspaceLayout.MAX_ZONE, c.layout.sizeOf(DockZone.LEFT), 0f)
        c.dispatch(WorkspaceAction.ResizeZone(DockZone.LEFT, 0.0f))
        assertEquals(WorkspaceLayout.MIN_ZONE, c.layout.sizeOf(DockZone.LEFT), 0f)
    }

    @Test fun focusModeHidesChromeAndRestoresArrangement() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.SetCollapsed(PanelId.THREAD_COLORS, true))
        val before = c.layout.panels
        c.dispatch(WorkspaceAction.ToggleFocus)
        assertFalse(c.layout.chromeVisible)
        c.dispatch(WorkspaceAction.ToggleFocus)
        assertTrue(c.layout.chromeVisible)
        assertEquals(before, c.layout.panels)
    }

    @Test fun presetsAreCompleteAndUsable() {
        for (n in WorkspacePresets.builtInNames) {
            val l = WorkspaceReducer.normalize(WorkspacePresets.create(n)!!)
            assertEquals(PanelId.values().size, l.panels.size)
            assertTrue("$n has at least one open panel", l.style == LayoutStyle.MENU || l.panels.values.any { it.open })
            assertContiguous(l)
        }
        assertTrue(WorkspacePresets.create("Full Screen")!!.focusMode)
        assertEquals(UserMode.BEGINNER, WorkspacePresets.create("Beginner")!!.mode)
    }

    @Test fun applyingPresetKeepsTouchOptions() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.SetStylusOnly(true))
        c.dispatch(WorkspaceAction.SetIconScale(1.5f))
        c.applyPreset(WorkspacePresets.STITCH_EDITING)
        assertTrue(c.layout.stylusOnlyDrawing)
        assertEquals(1.5f, c.layout.iconScale, 0f)
        assertEquals(WorkspacePresets.STITCH_EDITING, c.layout.name)
    }

    @Test fun saveAndRestoreCustomLayoutThroughStorage() {
        val c = ctrl()
        c.dispatch(WorkspaceAction.Undock(PanelId.THREAD_COLORS, NRect(0.5f, 0.2f, 0.3f, 0.4f)))
        c.dispatch(WorkspaceAction.SetPinned(PanelId.THREAD_COLORS, true))
        assertTrue(c.saveCurrentAs("My Layout"))
        assertFalse("built-in names reserved", c.saveCurrentAs("Beginner"))
        c.applyPreset(WorkspacePresets.BEGINNER)

        val store = object : WorkspaceStorage {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) { this.text = text }
        }
        c.save(store)

        val fresh = WorkspaceController(WorkspacePresets.default())
        assertTrue(fresh.restore(store))
        assertEquals(WorkspacePresets.BEGINNER, fresh.layout.name)
        assertEquals(listOf("My Layout"), fresh.savedNames())
        fresh.applyPreset("My Layout")
        val p = fresh.layout.panel(PanelId.THREAD_COLORS)
        assertEquals(DockZone.FLOATING, p.zone)
        assertTrue(p.pinned)
        assertEquals(NRect(0.5f, 0.2f, 0.3f, 0.4f), p.floatRect)
    }

    @Test fun corruptStorageIsIgnoredSafely() {
        val c = ctrl()
        val before = c.layout
        val store = object : WorkspaceStorage {
            override fun read() = "{ not json"
            override fun write(text: String) {}
        }
        assertFalse(c.restore(store))
        assertEquals(before, c.layout)
    }

    @Test fun olderOrNewerLayoutFilesStillLoad() {
        // Missing panels get defaults; unknown panel ids and fields are ignored.
        val json = """{"version":99,"current":{"name":"X","futureField":1,"panels":[
            {"id":"THREAD_COLORS","zone":"LEFT","order":0},{"id":"HOLOGRAM_PANEL","zone":"LEFT","order":1}]},"saved":[]}"""
        val (l, _) = WorkspaceSerializer.decodeState(json)!!
        assertEquals(PanelId.values().size, l.panels.size)
        assertEquals(DockZone.LEFT, l.panel(PanelId.THREAD_COLORS).zone)
        assertFalse(l.panel(PanelId.LAYER_MANAGER).open)
    }

    @Test fun contextMenusMatchSpec() {
        assertEquals(
            listOf("Edit Object", "Move / Scale / Rotate…", "Change Stitch Type", "Stitch Properties", "Duplicate", "Delete", "Bring Forward", "Send Backward"),
            ContextMenus.forObject().map { it.label }
        )
        assertEquals(
            listOf("Satin Settings", "Stitch Density", "Underlay", "Pull Compensation", "Stitch Direction"),
            ContextMenus.forTool(ToolMenuKind.SATIN).map { it.label }
        )
        val canvas = ContextMenus.forCanvas(CanvasViewState(true, false, true))
        assertEquals(listOf("Zoom In", "Zoom Out", "Fit Design", "Show Grid", "Show Rulers", "Show Hoop"), canvas.map { it.label })
        assertEquals(listOf(true, false, true), canvas.drop(3).map { it.checked })
    }

    @Test fun randomActionStormKeepsLayoutValid() {
        val c = ctrl()
        val rnd = java.util.Random(42)
        val ids = PanelId.values()
        val zones = DockZone.values()
        repeat(5000) {
            val id = ids[rnd.nextInt(ids.size)]
            val a: WorkspaceAction = when (rnd.nextInt(14)) {
                0 -> WorkspaceAction.ToggleCollapsed(id)
                1 -> WorkspaceAction.Open(id)
                2 -> WorkspaceAction.Close(id)
                3 -> WorkspaceAction.Dock(id, zones[rnd.nextInt(3)], rnd.nextInt(6) - 1)
                4 -> WorkspaceAction.Undock(id)
                5 -> WorkspaceAction.SetFloatRect(id, NRect(rnd.nextFloat() * 2 - .5f, rnd.nextFloat() * 2 - .5f, rnd.nextFloat(), rnd.nextFloat()))
                6 -> WorkspaceAction.SetMinimized(id, rnd.nextBoolean())
                7 -> WorkspaceAction.SetMaximized(id, rnd.nextBoolean())
                8 -> WorkspaceAction.SetPinned(id, rnd.nextBoolean())
                9 -> WorkspaceAction.Reorder(id, rnd.nextInt(8) - 2)
                10 -> WorkspaceAction.ResizeZone(zones[rnd.nextInt(4)], rnd.nextFloat())
                11 -> WorkspaceAction.ToggleFocus
                12 -> WorkspaceAction.Raise(id)
                else -> WorkspaceAction.SetIconScale(rnd.nextFloat() * 4)
            }
            c.dispatch(a)
            val l = c.layout
            assertEquals(ids.size, l.panels.size)
            assertContiguous(l)
            l.panels.values.forEach {
                assertFalse("min&max", it.minimized && it.maximized)
                if (it.zone != DockZone.FLOATING) assertFalse(it.minimized || it.maximized)
                val r = it.floatRect
                assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.w <= 1.001f && r.y + r.h <= 1.001f)
            }
        }
        // And the result must survive a serialization round trip.
        assertEquals(c.layout, WorkspaceSerializer.decodeLayout(WorkspaceSerializer.encodeLayout(c.layout)))
    }

    private fun assertContiguous(l: WorkspaceLayout) {
        for (z in DockZone.values()) {
            val orders = l.panels.values.filter { it.zone == z }.map { it.order }.sorted()
            assertEquals("zone $z", orders.indices.toList(), orders)
        }
    }
}
