package com.topz.stitchdraw.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.topz.stitchdraw.workspace.DockZone
import com.topz.stitchdraw.workspace.LayoutStyle
import com.topz.stitchdraw.workspace.QuickCommand
import com.topz.stitchdraw.workspace.WorkspaceAction
import com.topz.stitchdraw.workspace.WorkspaceController

/**
 * Customize the arrangement by dragging names into the order wanted.
 *  - Menu layout: order of the menu sections, order of the panels inside each section (and move a panel to
 *    another section), quick buttons, reset.
 *  - Docked layouts: order of the panels on each side, move a panel to another side, show/hide, quick buttons.
 * Every change goes through [WorkspaceController.dispatch]; nothing here touches the embroidery design.
 */
class LayoutEditor(private val ctx: Context, private val workspace: WorkspaceController, private val onChanged: () -> Unit) {

    fun show() {
        val nav = NavDialog(ctx)
        if (workspace.layout.style == LayoutStyle.MENU) nav.push("Customize menu") { menuRoot(nav) }
        else nav.push("Customize panels") { dockRoot(nav) }
        nav.show()
    }

    private fun act(a: WorkspaceAction) { workspace.dispatch(a); onChanged() }

    private fun smallButton(text: String, tint: Int, onClick: (View) -> Unit): TextView =
        ctx.label(text, 14f, tint, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
            roundBg(Theme.PANEL, 8f, Theme.BORDER, 1f)
            isClickable = true
            setOnClickListener { onClick(this) }
        }

    // ---- Menu layout -----------------------------------------------------------------------------------------

    private fun menuRoot(nav: NavDialog): View = NavDialog.scrollColumn(
        ctx, listOf(
            NavDialog.menuRow(ctx, "Order of menu sections", "Drag ≡ to reorder; tap a section to reorder its panels", true) {
                nav.push("Menu sections") { sections(nav) }
            },
            NavDialog.menuRow(ctx, "Quick buttons", "Buttons that stay over the canvas", true) {
                nav.push("Quick buttons") {
                    quickList(nav, { workspace.layout.menuQuick }) { act(WorkspaceAction.SetMenuQuick(it)) }
                }
            },
            NavDialog.menuRow(ctx, "Reset to default order", null, false) { act(WorkspaceAction.ResetMenu); nav.refresh() },
        )
    )

    private fun sections(nav: NavDialog): View {
        val cats = workspace.layout.menu
        val list = DragReorderList(ctx) { from, to -> act(WorkspaceAction.MoveMenuCategory(cats[from].id, to)); nav.refresh() }
        list.setRows(cats.map { c ->
            DragRow("${c.glyph}  ${c.title}", "${c.panels.size} panels", null) { nav.push(c.title) { sectionPanels(nav, c.id) } }
        })
        return list
    }

    private fun sectionPanels(nav: NavDialog, categoryId: String): View {
        val cats = workspace.layout.menu
        val cat = cats.firstOrNull { it.id == categoryId } ?: return View(ctx)
        val list = DragReorderList(ctx) { from, to -> act(WorkspaceAction.MoveMenuPanel(cat.panels[from], cat.id, to)); nav.refresh() }
        list.setRows(cat.panels.map { id ->
            val move = smallButton("Move ▾", Theme.ACCENT) { anchor ->
                val m = PopupMenu(ctx, anchor)
                val others = cats.filter { it.id != cat.id }
                others.forEachIndexed { i, o -> m.menu.add(0, i, i, "To: ${o.title}") }
                m.setOnMenuItemClickListener { item ->
                    act(WorkspaceAction.MoveMenuPanel(id, others[item.itemId].id, Int.MAX_VALUE)); nav.refresh(); true
                }
                m.show()
            }
            DragRow("${id.iconGlyph}  ${id.title}", null, move)
        })
        return list
    }

    // ---- docked layouts --------------------------------------------------------------------------------------

    private fun dockRoot(nav: NavDialog): View {
        val rows = ArrayList<View>()
        for (z in listOf(DockZone.LEFT, DockZone.RIGHT, DockZone.BOTTOM)) {
            val n = workspace.layout.panels.values.count { it.zone == z }
            val name = when (z) { DockZone.LEFT -> "Left side"; DockZone.RIGHT -> "Right side"; else -> "Bottom" }
            rows.add(NavDialog.menuRow(ctx, name, "$n panels - drag to reorder", true) { nav.push(name) { zone(nav, z) } })
        }
        rows.add(NavDialog.menuRow(ctx, "Quick buttons (full-screen mode)", null, true) {
            nav.push("Quick buttons") { quickList(nav, { workspace.layout.quickToolbar }) { act(WorkspaceAction.SetQuickToolbar(it)) } }
        })
        return NavDialog.scrollColumn(ctx, rows)
    }

    private fun zone(nav: NavDialog, z: DockZone): View {
        val panels = workspace.layout.panels.values.filter { it.zone == z }.sortedBy { it.order }
        val list = DragReorderList(ctx) { from, to -> act(WorkspaceAction.Reorder(panels[from].id, to)); nav.refresh() }
        list.setRows(panels.map { p ->
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val shown = smallButton(if (p.open) "Shown" else "Hidden", if (p.open) Theme.OK else Theme.TEXT_DIM) {
                act(if (p.open) WorkspaceAction.Close(p.id) else WorkspaceAction.Open(p.id)); nav.refresh()
            }
            val move = smallButton("Move ▾", Theme.ACCENT) { anchor ->
                val m = PopupMenu(ctx, anchor)
                val targets = listOf(DockZone.LEFT, DockZone.RIGHT, DockZone.BOTTOM).filter { it != z }
                targets.forEachIndexed { i, t -> m.menu.add(0, i, i, "To: " + t.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                m.setOnMenuItemClickListener { item -> act(WorkspaceAction.Dock(p.id, targets[item.itemId])); nav.refresh(); true }
                m.show()
            }
            box.addView(shown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = ctx.dp(6) })
            box.addView(move)
            DragRow("${p.id.iconGlyph}  ${p.id.title}", null, box)
        })
        return list
    }

    // ---- quick buttons (shared) ------------------------------------------------------------------------------

    private fun quickList(nav: NavDialog, get: () -> List<QuickCommand>, set: (List<QuickCommand>) -> Unit): View {
        val on = get()
        val all = on + QuickCommand.values().filter { it !in on && it != QuickCommand.EXIT_FOCUS }
        val list = DragReorderList(ctx) { from, to ->
            // only the enabled buttons have an order; moving within them reorders, anything else is ignored
            if (from < on.size && to < on.size) {
                val m = on.toMutableList(); m.add(to, m.removeAt(from)); set(m)
            }
            nav.refresh()
        }
        list.setRows(all.mapIndexed { i, c ->
            val enabled = i < on.size
            val toggle = smallButton(if (enabled) "On" else "Off", if (enabled) Theme.OK else Theme.TEXT_DIM) {
                set(if (enabled) on - c else on + c); nav.refresh()
            }
            DragRow("${c.glyph}  ${c.label}", null, toggle)
        })
        return list
    }
}
