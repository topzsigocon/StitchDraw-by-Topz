package com.topz.stitchdraw.workspace

import com.topz.stitchdraw.core.MiniJson
import com.topz.stitchdraw.core.asBool
import com.topz.stitchdraw.core.asDouble
import com.topz.stitchdraw.core.asInt
import com.topz.stitchdraw.core.asList
import com.topz.stitchdraw.core.asMap
import com.topz.stitchdraw.core.asString

/**
 * JSON encoding of layouts. Versioned; unknown panels/fields are ignored and missing ones get
 * defaults, so layout files written by older or newer builds still load.
 */
object WorkspaceSerializer {
    const val VERSION = 1

    fun encodeState(current: WorkspaceLayout, saved: Map<String, WorkspaceLayout>): String =
        MiniJson.write(
            mapOf(
                "version" to VERSION,
                "current" to toMap(current),
                "saved" to saved.values.map { toMap(it) }
            ),
            pretty = true
        )

    fun decodeState(text: String): Pair<WorkspaceLayout, Map<String, WorkspaceLayout>>? = try {
        val root = MiniJson.parse(text).asMap()
        val current = fromMap(root["current"].asMap()) ?: return null
        val saved = LinkedHashMap<String, WorkspaceLayout>()
        for (m in root["saved"].asList()) {
            val l = fromMap(m.asMap()) ?: continue
            if (!WorkspacePresets.isBuiltIn(l.name)) saved[l.name] = l
        }
        current to saved
    } catch (e: MiniJson.JsonException) {
        null
    }

    fun encodeLayout(l: WorkspaceLayout): String = MiniJson.write(toMap(l), pretty = true)
    fun decodeLayout(text: String): WorkspaceLayout? = try { fromMap(MiniJson.parse(text).asMap()) } catch (e: MiniJson.JsonException) { null }

    private fun toMap(l: WorkspaceLayout): Map<String, Any?> = linkedMapOf(
        "name" to l.name,
        "mode" to l.mode.name,
        "focus" to l.focusMode,
        "iconScale" to l.iconScale,
        "stylusOnly" to l.stylusOnlyDrawing,
        "zoneSize" to l.zoneSize.entries.associate { it.key.name to it.value },
        "quick" to l.quickToolbar.map { it.name },
        "style" to l.style.name,
        "exclusive" to l.exclusiveExpand,
        "menuBarPos" to listOf(l.menuBarPos.x, l.menuBarPos.y),
        "menuQuick" to l.menuQuick.map { it.name },
        "menu" to l.menu.map { c -> mapOf("id" to c.id, "panels" to c.panels.map { it.name }) },
        "quickPos" to listOf(l.quickToolbarPos.x, l.quickToolbarPos.y),
        "focusBtnPos" to listOf(l.focusButtonPos.x, l.focusButtonPos.y),
        "panels" to l.panels.values.map { p ->
            mapOf(
                "id" to p.id.name, "zone" to p.zone.name, "order" to p.order,
                "open" to p.open, "collapsed" to p.collapsed, "minimized" to p.minimized,
                "maximized" to p.maximized, "pinned" to p.pinned,
                "rect" to listOf(p.floatRect.x, p.floatRect.y, p.floatRect.w, p.floatRect.h)
            )
        }
    )

    private inline fun <reified E : Enum<E>> enumOf(name: String?): E? =
        enumValues<E>().firstOrNull { it.name == name }

    private fun point(v: Any?, default: NPoint): NPoint {
        val l = v.asList()
        return if (l.size == 2) NPoint(l[0].asDouble().toFloat(), l[1].asDouble().toFloat()) else default
    }

    private fun fromMap(m: Map<String, Any?>): WorkspaceLayout? {
        if (m.isEmpty()) return null
        val base = WorkspacePresets.default()
        val panels = LinkedHashMap<PanelId, PanelState>()
        for (pm in m["panels"].asList()) {
            val o = pm.asMap()
            val id = enumOf<PanelId>(o["id"].asString()) ?: continue      // unknown panel from a newer build
            val zone = enumOf<DockZone>(o["zone"].asString()) ?: id.defaultZone
            val r = o["rect"].asList()
            val rect = if (r.size == 4) NRect(
                r[0].asDouble().toFloat(), r[1].asDouble().toFloat(), r[2].asDouble().toFloat(), r[3].asDouble().toFloat()
            ) else NRect.DEFAULT_FLOAT
            panels[id] = PanelState(
                id, zone, o["order"].asInt(), o["open"].asBool(true), o["collapsed"].asBool(),
                o["minimized"].asBool(), o["maximized"].asBool(), o["pinned"].asBool(), rect
            )
        }
        val zones = HashMap<DockZone, Float>()
        for ((k, v) in m["zoneSize"].asMap()) enumOf<DockZone>(k)?.let { zones[it] = v.asDouble().toFloat() }
        val quick = m["quick"].asList().mapNotNull { enumOf<QuickCommand>(it.asString()) }
        val menu = m["menu"].asList().mapNotNull { cm ->
            val o = cm.asMap()
            val id = o["id"].asString()
            if (id.isEmpty()) return@mapNotNull null
            val info = MenuTree.DEFAULT.firstOrNull { it.id == id }
            MenuCategory(id, info?.title ?: id, info?.glyph ?: "•", o["panels"].asList().mapNotNull { enumOf<PanelId>(it.asString()) })
        }
        val menuQuick = m["menuQuick"].asList().mapNotNull { enumOf<QuickCommand>(it.asString()) }
        val layout = WorkspaceLayout(
            name = m["name"].asString("Custom"),
            mode = enumOf<UserMode>(m["mode"].asString()) ?: UserMode.PROFESSIONAL,
            panels = panels,
            zoneSize = WorkspaceLayout.DEFAULT_ZONE_SIZE + zones,
            focusMode = m["focus"].asBool(),
            iconScale = m["iconScale"].asDouble(1.0).toFloat(),
            quickToolbar = quick.ifEmpty { WorkspaceLayout.DEFAULT_QUICK },
            quickToolbarPos = point(m["quickPos"], base.quickToolbarPos),
            focusButtonPos = point(m["focusBtnPos"], base.focusButtonPos),
            stylusOnlyDrawing = m["stylusOnly"].asBool(),
            style = enumOf<LayoutStyle>(m["style"].asString()) ?: LayoutStyle.DOCKED,
            exclusiveExpand = m["exclusive"].asBool(),
            menu = menu.ifEmpty { MenuTree.DEFAULT },
            menuBarPos = point(m["menuBarPos"], base.menuBarPos),
            menuQuick = menuQuick.ifEmpty { WorkspaceLayout.DEFAULT_MENU_QUICK },
        )
        return WorkspaceReducer.normalize(layout)
    }
}
