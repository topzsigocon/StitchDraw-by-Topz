package com.topz.stitchdraw.vectorart

import android.graphics.*
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Bezier node: anchor (x,y), in-handle (ix,iy), out-handle (ox,oy). Handle == anchor means "no handle" (line). */
class Node(var x: Float, var y: Float) {
    var ix = x; var iy = y; var ox = x; var oy = y; var smooth = false; var symmetric = false
    fun copy() = Node(x, y).also { it.ix = ix; it.iy = iy; it.ox = ox; it.oy = oy; it.smooth = smooth; it.symmetric = symmetric }
    fun move(dx: Float, dy: Float) { x += dx; y += dy; ix += dx; iy += dy; ox += dx; oy += dy }
    fun outLine() = ox == x && oy == y
    fun inLine() = ix == x && iy == y
    fun map(m: Matrix) {
        val p = floatArrayOf(x, y, ix, iy, ox, oy); m.mapPoints(p)
        x = p[0]; y = p[1]; ix = p[2]; iy = p[3]; ox = p[4]; oy = p[5]
    }
}

/** An extra contour of a compound path (a hole or a separate part); the whole object is filled even-odd. */
class SubPath(var nodes: MutableList<Node>, var closed: Boolean = true) {
    fun copy() = SubPath(nodes.map { it.copy() }.toMutableList(), closed)
}

private fun nodeToJson(n: Node) = JSONArray(listOf(n.x, n.y, n.ix, n.iy, n.ox, n.oy, if (n.smooth) 1f else 0f, if (n.symmetric) 1f else 0f).map { it.toDouble() })
private fun nodeFromJson(a: JSONArray): Node = Node(a.getDouble(0).toFloat(), a.getDouble(1).toFloat()).also {
    it.ix = a.getDouble(2).toFloat(); it.iy = a.getDouble(3).toFloat(); it.ox = a.getDouble(4).toFloat(); it.oy = a.getDouble(5).toFloat(); it.smooth = a.getDouble(6) > 0.5; it.symmetric = a.length() > 7 && a.getDouble(7) > 0.5
}

enum class Kind { PATH, TEXT, IMAGE }

class Layer(var name: String, var visible: Boolean = true, var locked: Boolean = false, var opacity: Float = 1f)

/** All coordinates are in millimetres (document space). */
class VObj(var kind: Kind = Kind.PATH) {
    /** Optional user-facing name; absent in legacy VDT files. */
    var name: String = ""
    var nodes = mutableListOf<Node>()
    var closed = false
    var holes = mutableListOf<SubPath>()   // extra contours (holes / compound parts), filled even-odd
    var group = 0                          // objects sharing a non-zero id select and move together
    var uid = 0; var clipUid = 0           // clipUid != 0: drawn clipped to the closed shape whose uid matches (live "put inside")
    var fill: Int? = 0xFF3A86FF.toInt()
    var fill2: Int? = null          // gradient end colour (null = uniform fill)
    var gradAngle = 0f
    var stroke: Int? = Color.BLACK
    var strokeW = 0.3f
    var cap = 0; var join = 0; var dash = 0
    var arrow = false
    var mesh: IntArray? = null      // mesh fill vertex colours (row-major), spread evenly over the bounds
    var meshR = 0; var meshC = 0
    var meshPos: FloatArray? = null   // optional per-vertex (u,v) relative to bounds; null = regular grid
    var meshH: FloatArray? = null     // per-vertex edge-handle offsets: 8 floats (left,right,up,down x u,v); null = straight edges
    var fRot = 0f                     // rotation (deg) accumulated by transforms: mesh, gradient and patterns turn with the shape
    // transparency mask: 0 = none (uniform alpha only), 1 = fountain, 2 = bitmap pattern
    var tMode = 0; var tA1 = 1f; var tA2 = 0f; var tAngle = 0f
    var tPat = 0; var tTile = 10f           // built-in pattern index (-1 = custom image) and tile width in mm
    var tBmp: Bitmap? = null
    var tBaked: Bitmap? = null; var tKey = ""   // cache, never saved
    var alpha = 1f
    var shadow = false
    var layer = 0
    var visible = true
    var locked = false
    // text
    var text = ""; var size = 8f; var bold = false; var italic = false
    var underline = false; var strikeThrough = false
    var align = 0; var spacing = 0f; var leading = 1.2f
    var font = ""; var tRot = 0f        // font family (built-in or imported file) / rotation of text and images (deg)
    var gradType = 0; var fillMid: Int? = null   // 0 linear, 1 radial gradient; optional middle colour stop
    var tx = 0f; var ty = 0f; var frameW = 0f
    var onPath = false              // text follows `nodes`
    // image
    var bmp: Bitmap? = null
    var rect = RectF()

    private fun addContour(p: Path, ns: List<Node>, closed: Boolean) {
        val n = ns.size; if (n == 0) return
        p.moveTo(ns[0].x, ns[0].y)
        for (i in 0 until (if (closed) n else n - 1)) {
            val a = ns[i]; val b = ns[(i + 1) % n]
            if (a.outLine() && b.inLine()) p.lineTo(b.x, b.y) else p.cubicTo(a.ox, a.oy, b.ix, b.iy, b.x, b.y)
        }
        if (closed) p.close()
    }

    fun path(): Path {
        val p = Path(); addContour(p, nodes, closed)
        if (holes.isNotEmpty()) { for (h in holes) addContour(p, h.nodes, h.closed); p.fillType = Path.FillType.EVEN_ODD }
        return p
    }

    fun textPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Fonts.typeface(font, bold, italic)
        isUnderlineText = underline; isStrikeThruText = strikeThrough
        letterSpacing = spacing
        textAlign = when (align) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
    }

    fun lines(): List<String> {
        val raw = text.split("\n"); if (frameW <= 0f) return raw
        val pt = textPaint(); val out = ArrayList<String>()
        for (para in raw) {
            var cur = ""
            for (w in para.split(" ")) {
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (cur.isEmpty() || pt.measureText(t) <= frameW) cur = t else { out.add(cur); cur = w }
            }
            out.add(cur)
        }
        return out
    }

    fun textW(): Float {
        if (frameW > 0f) return frameW
        val pt = textPaint(); return lines().maxOfOrNull { pt.measureText(it) } ?: 0f
    }
    fun textX(w: Float) = when (align) { 1 -> tx + w / 2; 2 -> tx + w; else -> tx }

    private fun pathBounds(): RectF { val r = RectF(); path().computeBounds(r, true); return r }

    private fun rotRect(r: RectF, px: Float, py: Float): RectF {
        if (tRot == 0f) return r
        val pts = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
        Matrix().apply { setRotate(tRot, px, py) }.mapPoints(pts)
        return RectF(minOf(pts[0], pts[2], pts[4], pts[6]), minOf(pts[1], pts[3], pts[5], pts[7]), maxOf(pts[0], pts[2], pts[4], pts[6]), maxOf(pts[1], pts[3], pts[5], pts[7]))
    }

    fun bounds(): RectF = when {
        kind == Kind.IMAGE -> rotRect(RectF(rect), rect.centerX(), rect.centerY())
        kind == Kind.TEXT && !onPath -> {
            val l = lines()
            rotRect(RectF(tx, ty - size * 0.9f, tx + textW(), ty + (l.size - 1) * size * leading + size * 0.25f), tx, ty)
        }
        else -> pathBounds()
    }

    fun transform(m: Matrix) {
        nodes.forEach { it.map(m) }
        holes.forEach { h -> h.nodes.forEach { it.map(m) } }
        if (kind == Kind.TEXT) {
            val p = floatArrayOf(tx, ty); m.mapPoints(p); tx = p[0]; ty = p[1]
            val s = m.mapRadius(1f); size *= s; frameW *= s
        }
        val v = FloatArray(9); m.getValues(v)
        if (kind == Kind.IMAGE) {
            val sx = Math.hypot(v[Matrix.MSCALE_X].toDouble(), v[Matrix.MSKEW_Y].toDouble()).toFloat()
            val sy = Math.hypot(v[Matrix.MSKEW_X].toDouble(), v[Matrix.MSCALE_Y].toDouble()).toFloat()
            val c = floatArrayOf(rect.centerX(), rect.centerY()); m.mapPoints(c)
            val hw = rect.width() * sx / 2; val hh = rect.height() * sy / 2
            rect = RectF(c[0] - hw, c[1] - hh, c[0] + hw, c[1] + hh)
        }
        val det = v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y] - v[Matrix.MSKEW_X] * v[Matrix.MSKEW_Y]
        if (det > 0f) {   // ignore reflections
            val ang = Math.toDegrees(Math.atan2(v[Matrix.MSKEW_Y].toDouble(), v[Matrix.MSCALE_X].toDouble())).toFloat()
            if (Math.abs(ang) > 1e-4f) { if (kind == Kind.PATH) fRot = (fRot + ang) % 360f else if (!onPath) tRot = (tRot + ang) % 360f }
        }
    }

    fun copy(): VObj = VObj(kind).also { o ->
        o.name = name; o.nodes = nodes.map { it.copy() }.toMutableList(); o.closed = closed
        o.holes = holes.map { it.copy() }.toMutableList(); o.group = group; o.uid = uid; o.clipUid = clipUid
        o.fill = fill; o.fill2 = fill2; o.gradAngle = gradAngle; o.stroke = stroke; o.strokeW = strokeW
        o.cap = cap; o.join = join; o.dash = dash; o.arrow = arrow; o.alpha = alpha; o.shadow = shadow
        o.layer = layer; o.visible = visible; o.locked = locked
        o.text = text; o.size = size; o.bold = bold; o.italic = italic; o.underline = underline; o.strikeThrough = strikeThrough; o.align = align; o.spacing = spacing
        o.leading = leading; o.tx = tx; o.ty = ty; o.frameW = frameW; o.onPath = onPath
        o.bmp = bmp; o.rect = RectF(rect)
        o.font = font; o.tRot = tRot; o.gradType = gradType; o.fillMid = fillMid
        o.mesh = mesh?.copyOf(); o.meshR = meshR; o.meshC = meshC; o.meshPos = meshPos?.copyOf(); o.meshH = meshH?.copyOf(); o.fRot = fRot
        o.tMode = tMode; o.tA1 = tA1; o.tA2 = tA2; o.tAngle = tAngle; o.tPat = tPat; o.tTile = tTile; o.tBmp = tBmp; o.tBaked = tBaked; o.tKey = tKey
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", kind.name); put("c", closed); if (name.isNotBlank()) put("nm", name)
        fill?.let { put("f", it) }; fill2?.let { put("f2", it) }; stroke?.let { put("s", it) }
        put("ga", gradAngle.toDouble()); put("sw", strokeW.toDouble()); put("cap", cap); put("jn", join); put("ds", dash)
        put("ar", arrow); put("a", alpha.toDouble()); put("sh", shadow); put("ly", layer); put("vis", visible); put("lk", locked)
        put("n", JSONArray().also { arr ->
            nodes.forEach { n -> arr.put(JSONArray(listOf(n.x, n.y, n.ix, n.iy, n.ox, n.oy, if (n.smooth) 1f else 0f, if (n.symmetric) 1f else 0f).map { it.toDouble() })) }
        })
        if (group != 0) put("gr", group)
        if (uid != 0) put("uid", uid)
        if (clipUid != 0) put("cu", clipUid)
        if (holes.isNotEmpty()) put("holes", JSONArray().also { ha -> holes.forEach { h -> ha.put(JSONObject().put("c", h.closed).put("n", JSONArray().also { arr -> h.nodes.forEach { arr.put(nodeToJson(it)) } })) } })
        if (fRot != 0f) put("fr", fRot.toDouble())
        if (font.isNotEmpty()) put("fn", font)
        if (tRot != 0f) put("trot", tRot.toDouble())
        if (gradType != 0) put("gt", gradType)
        fillMid?.let { put("fm", it) }
        if (tMode != 0) {
            put("tm", tMode); put("ta1", tA1.toDouble()); put("ta2", tA2.toDouble()); put("tang", tAngle.toDouble()); put("tpat", tPat); put("ttile", tTile.toDouble())
            tBmp?.let { put("tbmp", Base64.encodeToString(Png.bytes(it), Base64.NO_WRAP)) }
        }
        mesh?.let { m -> put("mr", meshR); put("mc", meshC); put("mesh", JSONArray(m.toList())) }
        meshPos?.let { p -> put("mpos", JSONArray(p.map { it.toDouble() })) }
        meshH?.let { p -> put("mh", JSONArray(p.map { it.toDouble() })) }
        if (kind == Kind.TEXT) {
            put("t", text); put("sz", size.toDouble()); put("b", bold); put("i", italic); put("un", underline); put("st", strikeThrough); put("al", align)
            put("sp", spacing.toDouble()); put("ld", leading.toDouble()); put("tx", tx.toDouble()); put("ty", ty.toDouble())
            put("fw", frameW.toDouble()); put("op", onPath)
        }
        if (kind == Kind.IMAGE) {
            put("r", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom).map { it.toDouble() }))
            bmp?.let { b -> put("img", Base64.encodeToString(Png.bytes(b), Base64.NO_WRAP)) }
        }
    }

    companion object {
        fun fromJson(j: JSONObject): VObj {
            val o = VObj(Kind.valueOf(j.getString("k")))
            o.name = j.optString("nm", "")
            o.closed = j.optBoolean("c"); o.fill = if (j.has("f")) j.getInt("f") else null
            o.fill2 = if (j.has("f2")) j.getInt("f2") else null; o.stroke = if (j.has("s")) j.getInt("s") else null
            o.gradAngle = j.optDouble("ga", 0.0).toFloat(); o.strokeW = j.optDouble("sw", 0.3).toFloat()
            o.cap = j.optInt("cap"); o.join = j.optInt("jn"); o.dash = j.optInt("ds"); o.arrow = j.optBoolean("ar")
            o.alpha = j.optDouble("a", 1.0).toFloat(); o.shadow = j.optBoolean("sh"); o.layer = j.optInt("ly")
            o.visible = j.optBoolean("vis", true); o.locked = j.optBoolean("lk")
            val arr = j.getJSONArray("n")
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                val n = Node(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
                n.ix = a.getDouble(2).toFloat(); n.iy = a.getDouble(3).toFloat(); n.ox = a.getDouble(4).toFloat(); n.oy = a.getDouble(5).toFloat()
                n.smooth = a.getDouble(6) > 0.5; n.symmetric = a.length() > 7 && a.getDouble(7) > 0.5; o.nodes.add(n)
            }
            o.group = j.optInt("gr"); o.uid = j.optInt("uid"); o.clipUid = j.optInt("cu")
            j.optJSONArray("holes")?.let { ha ->
                for (hi in 0 until ha.length()) {
                    val ho = ha.getJSONObject(hi); val arr = ho.getJSONArray("n"); val ns = mutableListOf<Node>()
                    for (i in 0 until arr.length()) ns.add(nodeFromJson(arr.getJSONArray(i)))
                    o.holes.add(SubPath(ns, ho.optBoolean("c", true)))
                }
            }
            o.fRot = j.optDouble("fr", 0.0).toFloat()
            o.font = j.optString("fn"); o.tRot = j.optDouble("trot", 0.0).toFloat(); o.gradType = j.optInt("gt"); o.fillMid = if (j.has("fm")) j.getInt("fm") else null
            o.tMode = j.optInt("tm")
            if (o.tMode != 0) {
                o.tA1 = j.optDouble("ta1", 1.0).toFloat(); o.tA2 = j.optDouble("ta2", 0.0).toFloat(); o.tAngle = j.optDouble("tang", 0.0).toFloat()
                o.tPat = j.optInt("tpat"); o.tTile = j.optDouble("ttile", 10.0).toFloat()
                if (j.has("tbmp")) { val bytes = Base64.decode(j.getString("tbmp"), Base64.NO_WRAP); o.tBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }
            if (j.has("mesh")) { val a = j.getJSONArray("mesh"); o.mesh = IntArray(a.length()) { a.getInt(it) }; o.meshR = j.optInt("mr"); o.meshC = j.optInt("mc"); j.optJSONArray("mpos")?.let { pa -> o.meshPos = FloatArray(pa.length()) { pa.getDouble(it).toFloat() } }; j.optJSONArray("mh")?.let { ha -> o.meshH = FloatArray(ha.length()) { ha.getDouble(it).toFloat() } } }
            if (o.kind == Kind.TEXT) {
                o.text = j.optString("t"); o.size = j.optDouble("sz", 8.0).toFloat(); o.bold = j.optBoolean("b"); o.italic = j.optBoolean("i")
                o.underline = j.optBoolean("un", false); o.strikeThrough = j.optBoolean("st", false)
                o.align = j.optInt("al"); o.spacing = j.optDouble("sp", 0.0).toFloat(); o.leading = j.optDouble("ld", 1.2).toFloat()
                o.tx = j.optDouble("tx").toFloat(); o.ty = j.optDouble("ty").toFloat(); o.frameW = j.optDouble("fw", 0.0).toFloat()
                o.onPath = j.optBoolean("op")
            }
            if (o.kind == Kind.IMAGE) {
                val r = j.getJSONArray("r"); o.rect = RectF(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
                if (j.has("img")) { val bytes = Base64.decode(j.getString("img"), Base64.NO_WRAP); o.bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }
            return o
        }
    }
}

object Png { fun bytes(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }

class Doc {
    var w = 210f; var h = 297f; var unit = "mm"; var grid = 10f
    val layers = mutableListOf(Layer("Layer 1"))
    var objs = mutableListOf<VObj>()
    val hg = mutableListOf<Float>()   // horizontal guides (y, mm)
    val vg = mutableListOf<Float>()   // vertical guides (x, mm)

    /** millimetres per current unit */
    fun uf() = when (unit) { "cm" -> 10f; "in" -> 25.4f; "px" -> 25.4f / 96f; else -> 1f }

    /** Deep document snapshot used by undo/redo. Keeps the on-disk VDT schema unchanged. */
    fun deepCopy(): Doc = Doc().also { copy ->
        copy.w = w; copy.h = h; copy.unit = unit; copy.grid = grid
        copy.layers.clear()
        layers.forEach { copy.layers.add(Layer(it.name, it.visible, it.locked, it.opacity)) }
        copy.objs = objs.map { it.copy() }.toMutableList()
        copy.hg.addAll(hg); copy.vg.addAll(vg)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("app", "VecDraw by Topz"); put("v", 1); put("w", w.toDouble()); put("h", h.toDouble()); put("unit", unit); put("grid", grid.toDouble())
        put("layers", JSONArray().also { a -> layers.forEach { a.put(JSONObject().put("n", it.name).put("v", it.visible).put("l", it.locked).put("o", it.opacity.toDouble())) } })
        put("hg", JSONArray(hg.map { it.toDouble() })); put("vg", JSONArray(vg.map { it.toDouble() }))
        put("objs", JSONArray().also { a -> objs.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(j: JSONObject): Doc = Doc().also { d ->
            d.w = j.optDouble("w", 210.0).toFloat(); d.h = j.optDouble("h", 297.0).toFloat()
            d.unit = j.optString("unit", "mm"); d.grid = j.optDouble("grid", 10.0).toFloat()
            d.layers.clear()
            val ls = j.getJSONArray("layers")
            for (i in 0 until ls.length()) { val l = ls.getJSONObject(i); d.layers.add(Layer(l.getString("n"), l.optBoolean("v", true), l.optBoolean("l"), l.optDouble("o", 1.0).toFloat())) }
            if (d.layers.isEmpty()) d.layers.add(Layer("Layer 1"))
            j.optJSONArray("hg")?.let { for (i in 0 until it.length()) d.hg.add(it.getDouble(i).toFloat()) }
            j.optJSONArray("vg")?.let { for (i in 0 until it.length()) d.vg.add(it.getDouble(i).toFloat()) }
            val os = j.getJSONArray("objs")
            for (i in 0 until os.length()) d.objs.add(VObj.fromJson(os.getJSONObject(i)))
        }
    }
}

/** A document made of several pages; each page is a Doc (own size, layers, guides, objects). Old single-page files still load. */
object Project {
    fun toJson(pages: List<Doc>, cur: Int): JSONObject = JSONObject().put("app", "VecDraw by Topz").put("v", 2).put("cur", cur)
        .put("pages", JSONArray().also { a -> pages.forEach { a.put(it.toJson()) } })

    fun fromJson(j: JSONObject): Pair<List<Doc>, Int> {
        if (j.has("pages")) {
            val arr = j.getJSONArray("pages"); val l = List(arr.length()) { Doc.fromJson(arr.getJSONObject(it)) }
            return (if (l.isEmpty()) listOf(Doc()) else l) to j.optInt("cur", 0).coerceIn(0, maxOf(0, l.size - 1))
        }
        return listOf(Doc.fromJson(j)) to 0
    }
}
