package com.topz.stitchdraw.app

import android.content.Context
import android.view.View
import com.topz.stitchdraw.workspace.MenuTree
import com.topz.stitchdraw.workspace.WorkspaceAction
import com.topz.stitchdraw.workspace.WorkspaceController

/**
 * The ☰ menu of the Menu layout: sections -> panels. Choosing a panel opens it as a sheet over the canvas
 * (one at a time). Order of sections and panels is whatever the user arranged in [LayoutEditor].
 */
class MenuSheet(private val ctx: Context, private val workspace: WorkspaceController, private val onCustomize: () -> Unit) {

    fun show() {
        val nav = NavDialog(ctx)
        nav.push("Menu") { root(nav) }
        nav.show()
    }

    private fun root(nav: NavDialog): View {
        val l = workspace.layout
        val rows = ArrayList<View>()
        for (c in MenuTree.visible(l.menu)) {
            rows.add(NavDialog.menuRow(ctx, "${c.glyph}  ${c.title}", "${c.panels.size} panels", true) {
                nav.push(c.title) { panels(nav, c.id) }
            })
        }
        if (l.panels.values.any { it.open }) {
            rows.add(NavDialog.menuRow(ctx, "✕  Close open panel", null, false) {
                for (p in workspace.layout.panels.values) if (p.open) workspace.dispatch(WorkspaceAction.Close(p.id))
                nav.dismiss()
            })
        }
        rows.add(NavDialog.menuRow(ctx, "⇅  Customize menu…", "Drag to change the order", false) { nav.dismiss(); onCustomize() })
        return NavDialog.scrollColumn(ctx, rows)
    }

    private fun panels(nav: NavDialog, categoryId: String): View {
        val cat = workspace.layout.menu.firstOrNull { it.id == categoryId }
        val rows = (cat?.panels ?: emptyList()).map { id ->
            NavDialog.menuRow(ctx, "${id.iconGlyph}  ${id.title}", null, false) {
                workspace.dispatch(WorkspaceAction.OpenExclusive(id))
                nav.dismiss()
            }
        }
        return NavDialog.scrollColumn(ctx, rows)
    }
}
