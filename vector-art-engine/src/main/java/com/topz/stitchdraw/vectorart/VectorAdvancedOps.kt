package com.topz.stitchdraw.vectorart

import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.hypot

/** Advanced, non-destructive vector operations. Document coordinates are millimetres. */
object VectorAdvancedOps {
    enum class BooleanMode { UNION, SUBTRACT, INTERSECT, EXCLUDE }

    /**
     * Creates a new editable compound path from exactly two closed path objects.
     * The source objects are deliberately retained. Subtract is ordered: first - second.
     * Boolean output is flattened to editable line nodes at a bounded sampling interval.
     */
    fun booleanOperation(doc: Doc, orderedIds: List<Int>, mode: BooleanMode, sampleStepMm: Float = 0.65f): VObj? {
        if (orderedIds.size != 2 || orderedIds[0] == orderedIds[1]) return null
        val objects = orderedIds.map { id -> doc.objs.firstOrNull { it.uid == id } ?: return null }
        val (first, second) = objects
        if (!isEditable(first, doc) || !isEditable(second, doc)) return null
        if (first.kind != Kind.PATH || second.kind != Kind.PATH || !first.closed || !second.closed) return null
        if (first.layer != second.layer) return null

        val op = when (mode) {
            BooleanMode.UNION -> Path.Op.UNION
            BooleanMode.SUBTRACT -> Path.Op.DIFFERENCE
            BooleanMode.INTERSECT -> Path.Op.INTERSECT
            BooleanMode.EXCLUDE -> Path.Op.XOR
        }
        val resultPath = Path()
        val success = runCatching { resultPath.op(first.path(), second.path(), op) }.getOrDefault(false)
        if (!success) return null
        resultPath.fillType = Path.FillType.EVEN_ODD
        val contours = flattenContours(resultPath, sampleStepMm.coerceIn(0.15f, 2f))
        if (contours.isEmpty()) return null

        val uid = (doc.objs.maxOfOrNull { it.uid } ?: 0) + 1
        val result = first.copy().apply {
            name = "${mode.name.lowercase().replaceFirstChar { it.uppercase() }} ${first.name.ifBlank { "Shape" }}"
            this.uid = uid
            group = 0
            clipUid = 0
            nodes = contours.first().nodes
            closed = true
            holes = contours.drop(1).map { SubPath(it.nodes, true) }.toMutableList()
        }
        doc.objs.add(result)
        return result
    }

    /** Groups editable objects on one layer. Group membership is stored in the existing VDT model. */
    fun group(doc: Doc, ids: Set<Int>): Boolean {
        val objects = editableObjects(doc, ids)
        if (objects.size < 2 || objects.map { it.layer }.distinct().size != 1) return false
        val groupId = (doc.objs.maxOfOrNull { it.group } ?: 0) + 1
        objects.forEach { it.group = groupId }
        return true
    }

    /** Ungroups every member of any selected group, preventing orphaned partial groups. */
    fun ungroup(doc: Doc, ids: Set<Int>): Int {
        if (ids.isEmpty()) return 0
        val selected = doc.objs.filter { it.uid in ids && isEditable(it, doc) }
        val groupIds = selected.map { it.group }.filter { it != 0 }.toSet()
        if (groupIds.isEmpty()) return 0
        var changed = 0
        doc.objs.filter { it.group in groupIds && isEditable(it, doc) }.forEach { it.group = 0; changed++ }
        return changed
    }

    /**
     * Removes duplicate and near-collinear anchors. Curved contours keep their control nodes;
     * only duplicate, handle-free anchors are removed from them. Returns number removed.
     */
    fun cleanup(doc: Doc, ids: Set<Int>, toleranceMm: Float = 0.12f): Int {
        val objects = editableObjects(doc, ids).filter { it.kind == Kind.PATH }
        var removed = 0
        objects.forEach { obj ->
            val main = cleanContour(obj.nodes, obj.closed, toleranceMm)
            obj.nodes = main.first
            removed += main.second
            obj.holes.forEach { hole ->
                val cleaned = cleanContour(hole.nodes, hole.closed, toleranceMm)
                hole.nodes = cleaned.first
                removed += cleaned.second
            }
        }
        return removed
    }

    private data class Contour(val nodes: MutableList<Node>)

    private fun flattenContours(path: Path, step: Float): List<Contour> {
        val result = mutableListOf<Contour>()
        val measure = PathMeasure(path, false)
        do {
            val length = measure.length
            if (length > 0.01f) {
                val count = (length / step).toInt().coerceIn(3, 4096)
                val nodes = ArrayList<Node>(count)
                val pos = FloatArray(2)
                for (i in 0 until count) {
                    val distance = length * i / count
                    if (measure.getPosTan(distance, pos, null)) nodes.add(Node(pos[0], pos[1]))
                }
                if (nodes.size >= 3) result.add(Contour(nodes))
            }
        } while (measure.nextContour())
        return result
    }

    private fun cleanContour(source: MutableList<Node>, closed: Boolean, tolerance: Float): Pair<MutableList<Node>, Int> {
        if (source.size <= if (closed) 3 else 2) return source to 0
        val nodes = source.map { it.copy() }.toMutableList()
        var removed = 0
        val minCount = if (closed) 3 else 2
        val eps = tolerance.coerceAtLeast(0.001f)

        // Remove consecutive duplicate anchors only when the discarded node has no active handles.
        var i = 0
        while (i < nodes.size && nodes.size > minCount) {
            val next = if (i == nodes.lastIndex) 0 else i + 1
            val a = nodes[i]; val b = nodes[next]
            if (hypot(a.x - b.x, a.y - b.y) <= eps && a.inLine() && a.outLine() && b.inLine() && b.outLine()) {
                nodes.removeAt(next)
                removed++
                if (next == 0) i = 0
            } else i++
        }

        // RDP-like local collinear cleanup is safe only for entirely straight contours.
        val allStraight = nodes.all { it.inLine() && it.outLine() }
        if (allStraight) {
            var changed = true
            while (changed && nodes.size > minCount) {
                changed = false
                val start = if (closed) 0 else 1
                val end = if (closed) nodes.size else nodes.lastIndex
                for (idx in start until end) {
                    val prev = nodes[(idx - 1 + nodes.size) % nodes.size]
                    val cur = nodes[idx]
                    val next = nodes[(idx + 1) % nodes.size]
                    if (distanceToSegment(cur.x, cur.y, prev.x, prev.y, next.x, next.y) <= eps) {
                        nodes.removeAt(idx); removed++; changed = true; break
                    }
                }
            }
        }
        return nodes to removed
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-8f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun editableObjects(doc: Doc, ids: Set<Int>) = doc.objs.filter { it.uid in ids && isEditable(it, doc) }
    private fun isEditable(obj: VObj, doc: Doc): Boolean = !obj.locked && doc.layers.getOrNull(obj.layer)?.let { it.visible && !it.locked } == true
}
