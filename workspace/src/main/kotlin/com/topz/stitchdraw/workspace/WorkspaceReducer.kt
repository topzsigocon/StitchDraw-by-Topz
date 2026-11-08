package com.topz.stitchdraw.workspace

/**
 * Pure function: (layout, action) -> layout. Invalid or no-op actions return the same instance,
 * so callers can detect "nothing changed" with ===. Has no access to any design or engine state.
 */
object WorkspaceReducer {

    fun reduce(l: WorkspaceLayout, a: WorkspaceAction): WorkspaceLayout = when (a) {
        is WorkspaceAction.SetCollapsed -> setCollapsed(l, a.id, a.collapsed)
        is WorkspaceAction.ToggleCollapsed -> setCollapsed(l, a.id, !l.panel(a.id).collapsed)
        is WorkspaceAction.SetZoneCollapsed -> {
            var cur = l
            if (a.zone != DockZone.FLOATING) {
                for (p in l.inZone(a.zone)) cur = setCollapsed(cur, p.id, a.collapsed)
            }
            cur
        }

        is WorkspaceAction.Open -> update(l, a.id) { it.copy(open = true, collapsed = false, minimized = false) }
            .let { if (it.panel(a.id).zone == DockZone.FLOATING) raise(it, a.id) else it }
        is WorkspaceAction.Close -> update(l, a.id) { if (it.pinned) it else it.copy(open = false, maximized = false) }

        is WorkspaceAction.Dock -> dock(l, a.id, a.zone, a.index)
        is WorkspaceAction.Undock -> undock(l, a.id, a.rect)
        is WorkspaceAction.Reorder -> reorder(l, a.id, a.index)

        is WorkspaceAction.SetFloatRect -> update(l, a.id) {
            if (it.zone != DockZone.FLOATING || it.pinned || it.maximized) it else it.copy(floatRect = a.rect.clamped())
        }.let { if (it !== l) raise(it, a.id) else it }
        is WorkspaceAction.SetMinimized -> update(l, a.id) {
            if (it.zone != DockZone.FLOATING) it else it.copy(minimized = a.minimized, maximized = if (a.minimized) false else it.maximized)
        }
        is WorkspaceAction.SetMaximized -> update(l, a.id) {
            if (it.zone != DockZone.FLOATING) it else it.copy(maximized = a.maximized, minimized = if (a.maximized) false else it.minimized)
        }.let { if (a.maximized && it !== l) raise(it, a.id) else it }
        is WorkspaceAction.SetPinned -> update(l, a.id) { it.copy(pinned = a.pinned) }
        is WorkspaceAction.Raise -> raise(l, a.id)

        is WorkspaceAction.ResizeZone -> {
            if (a.zone == DockZone.FLOATING) l
            else same(l, l.copy(zoneSize = l.zoneSize + (a.zone to a.size.coerceIn(WorkspaceLayout.MIN_ZONE, WorkspaceLayout.MAX_ZONE))))
        }

        is WorkspaceAction.SetFocus -> same(l, l.copy(focusMode = a.on))
        WorkspaceAction.ToggleFocus -> l.copy(focusMode = !l.focusMode)
        is WorkspaceAction.MoveFocusButton -> same(l, l.copy(focusButtonPos = a.pos.clamped()))
        is WorkspaceAction.MoveQuickToolbar -> same(l, l.copy(quickToolbarPos = a.pos.clamped()))
        is WorkspaceAction.SetQuickToolbar -> same(l, l.copy(quickToolbar = a.commands.distinct()))

        is WorkspaceAction.SetIconScale -> same(
            l, l.copy(iconScale = a.scale.coerceIn(WorkspaceLayout.MIN_ICON_SCALE, WorkspaceLayout.MAX_ICON_SCALE))
        )
        is WorkspaceAction.SetMode -> same(l, l.copy(mode = a.mode))
        is WorkspaceAction.SetStylusOnly -> same(l, l.copy(stylusOnlyDrawing = a.on))
        is WorkspaceAction.Replace -> normalize(a.layout)
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun same(old: WorkspaceLayout, new: WorkspaceLayout) = if (old == new) old else new

    private fun update(l: WorkspaceLayout, id: PanelId, f: (PanelState) -> PanelState): WorkspaceLayout {
        val cur = l.panels[id] ?: return l
        val next = f(cur)
        return if (next == cur) l else l.copy(panels = l.panels + (id to next))
    }

    private fun setCollapsed(l: WorkspaceLayout, id: PanelId, collapsed: Boolean) =
        update(l, id) { if (it.zone == DockZone.FLOATING || it.pinned) it else it.copy(collapsed = collapsed) }

    private fun raise(l: WorkspaceLayout, id: PanelId): WorkspaceLayout {
        val p = l.panels[id] ?: return l
        if (p.zone != DockZone.FLOATING) return l
        val top = l.inZone(DockZone.FLOATING, includeClosed = true)
        if (top.lastOrNull()?.id == id) return l
        return renumber(l.copy(panels = l.panels + (id to p.copy(order = Int.MAX_VALUE))), DockZone.FLOATING)
    }

    /** Puts [moved] into [zone] at list position [index], renumbering that zone 0..n-1. */
    private fun place(l: WorkspaceLayout, moved: PanelState, zone: DockZone, index: Int?): WorkspaceLayout {
        val others = l.panels.values.filter { it.zone == zone && it.id != moved.id }.sortedBy { it.order }
        val at = (index ?: others.size).coerceIn(0, others.size)
        val ordered = others.toMutableList().apply { add(at, moved.copy(zone = zone)) }
        var panels = l.panels
        ordered.forEachIndexed { i, s -> panels = panels + (s.id to s.copy(order = i)) }
        return l.copy(panels = panels)
    }

    private fun dock(l: WorkspaceLayout, id: PanelId, zone: DockZone, index: Int?): WorkspaceLayout {
        if (zone == DockZone.FLOATING) return l
        val p = l.panels[id] ?: return l
        if (p.pinned) return l
        val moved = p.copy(open = true, collapsed = false, minimized = false, maximized = false)
        // index is relative to the zone's full list (closed panels included)
        val next = place(l, moved, zone, index)
        return if (p.zone != zone) renumber(next, p.zone) else next
    }

    private fun undock(l: WorkspaceLayout, id: PanelId, rect: NRect?): WorkspaceLayout {
        val p = l.panels[id] ?: return l
        if (p.pinned || p.zone == DockZone.FLOATING) return l
        val offset = (l.inZone(DockZone.FLOATING, includeClosed = true).size % 6) * 0.03f
        val r = (rect ?: NRect.DEFAULT_FLOAT.copy(x = NRect.DEFAULT_FLOAT.x + offset, y = NRect.DEFAULT_FLOAT.y + offset)).clamped()
        val moved = p.copy(zone = DockZone.FLOATING, open = true, collapsed = false, floatRect = r, order = Int.MAX_VALUE)
        var next = l.copy(panels = l.panels + (id to moved))
        next = renumber(next, DockZone.FLOATING)
        return renumber(next, p.zone)
    }

    private fun reorder(l: WorkspaceLayout, id: PanelId, index: Int): WorkspaceLayout {
        val p = l.panels[id] ?: return l
        if (p.pinned || p.zone == DockZone.FLOATING) return l
        return same(l, place(l, p, p.zone, index))
    }

    /** Rewrites `order` of every panel in [zone] as 0..n-1 keeping relative order. */
    private fun renumber(l: WorkspaceLayout, zone: DockZone): WorkspaceLayout {
        val sorted = l.panels.values.filter { it.zone == zone }.sortedBy { it.order }
        if (sorted.isEmpty()) return l
        var panels = l.panels
        sorted.forEachIndexed { i, s -> if (s.order != i) panels = panels + (s.id to s.copy(order = i)) }
        return if (panels === l.panels) l else l.copy(panels = panels)
    }

    /** Makes any layout complete and sane: every PanelId present, sizes clamped, orders contiguous. */
    fun normalize(input: WorkspaceLayout): WorkspaceLayout {
        var panels = input.panels
        for (id in PanelId.values()) {
            if (id !in panels) panels = panels + (id to PanelState(id, id.defaultZone, order = 1000 + id.ordinal, open = false))
        }
        panels = panels.mapValues { (_, s) -> s.copy(floatRect = s.floatRect.clamped()) }
        var l = input.copy(
            panels = panels,
            iconScale = input.iconScale.coerceIn(WorkspaceLayout.MIN_ICON_SCALE, WorkspaceLayout.MAX_ICON_SCALE),
            zoneSize = WorkspaceLayout.DEFAULT_ZONE_SIZE.mapValues { (z, d) ->
                if (z == DockZone.FLOATING) 0f
                else (input.zoneSize[z] ?: d).coerceIn(WorkspaceLayout.MIN_ZONE, WorkspaceLayout.MAX_ZONE)
            },
            focusButtonPos = input.focusButtonPos.clamped(),
            quickToolbarPos = input.quickToolbarPos.clamped(),
        )
        for (z in DockZone.values()) l = renumber(l, z)
        return l
    }
}
