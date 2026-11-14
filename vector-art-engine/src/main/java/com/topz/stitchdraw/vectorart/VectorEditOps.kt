package com.topz.stitchdraw.vectorart

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Editing operations shared by the vector workspace and canvas. Coordinates remain in mm. */
object VectorEditOps {
    enum class HandleMode { CUSP, SMOOTH, SYMMETRIC }
    enum class Align { LEFT, H_CENTER, RIGHT, TOP, V_CENTER, BOTTOM }

    fun setHandleMode(node: Node, mode: HandleMode, previous: Node? = null, next: Node? = null) {
        when (mode) {
            HandleMode.CUSP -> { node.smooth = false; node.symmetric = false }
            HandleMode.SMOOTH -> {
                node.smooth = true; node.symmetric = false
                if (node.inLine() && node.outLine()) seedHandles(node, previous, next)
                balance(node, equalLength = false)
            }
            HandleMode.SYMMETRIC -> {
                node.smooth = true; node.symmetric = true
                if (node.inLine() && node.outLine()) seedHandles(node, previous, next)
                balance(node, equalLength = true)
            }
        }
    }


    private fun seedHandles(n: Node, previous: Node?, next: Node?) {
        val dx=(next?.x ?: n.x+1f)-(previous?.x ?: n.x-1f)
        val dy=(next?.y ?: n.y)-(previous?.y ?: n.y)
        val len=hypot(dx,dy).coerceAtLeast(0.001f); val ux=dx/len; val uy=dy/len
        val before=previous?.let { hypot(n.x-it.x,n.y-it.y) } ?: 6f
        val after=next?.let { hypot(it.x-n.x,it.y-n.y) } ?: 6f
        val inLen=(before/3f).coerceAtLeast(1f); val outLen=(after/3f).coerceAtLeast(1f)
        n.ix=n.x-ux*inLen; n.iy=n.y-uy*inLen; n.ox=n.x+ux*outLen; n.oy=n.y+uy*outLen
    }

    /** Move one handle. Smooth nodes preserve tangent; symmetric nodes also preserve handle length. */
    fun moveHandle(node: Node, outgoing: Boolean, x: Float, y: Float) {
        if (outgoing) { node.ox = x; node.oy = y } else { node.ix = x; node.iy = y }
        if (!node.smooth) return
        val dx = x - node.x; val dy = y - node.y
        val len = hypot(dx, dy).coerceAtLeast(0.0001f)
        val otherLen = if (node.symmetric) len else if (outgoing) hypot(node.ix-node.x, node.iy-node.y) else hypot(node.ox-node.x, node.oy-node.y)
        val ux = -dx / len; val uy = -dy / len
        if (outgoing) { node.ix = node.x + ux * otherLen; node.iy = node.y + uy * otherLen }
        else { node.ox = node.x + ux * otherLen; node.oy = node.y + uy * otherLen }
    }

    private fun balance(n: Node, equalLength: Boolean) {
        val dx = n.ox - n.x; val dy = n.oy - n.y
        val len = hypot(dx, dy)
        if (len < 0.0001f) return
        val other = if (equalLength) len else hypot(n.ix - n.x, n.iy - n.y).coerceAtLeast(len * 0.35f)
        n.ix = n.x - dx / len * other; n.iy = n.y - dy / len * other
    }

    /** Insert a node at the closest point on a sampled cubic segment. */
    fun insertNode(obj: VObj, x: Float, y: Float): Int {
        if (obj.kind != Kind.PATH || obj.nodes.size < 2) return -1
        val count = if (obj.closed) obj.nodes.size else obj.nodes.size - 1
        var bestDist = Float.MAX_VALUE; var bestIndex = -1; var bestT = 0f
        for (i in 0 until count) {
            val a = obj.nodes[i]; val b = obj.nodes[(i + 1) % obj.nodes.size]
            for (step in 0..32) {
                val t = step / 32f; val q = cubic(a, b, t)
                val d = hypot(q.first - x, q.second - y)
                if (d < bestDist) { bestDist = d; bestIndex = i; bestT = t }
            }
        }
        if (bestIndex < 0 || bestDist > 6f) return -1
        val a = obj.nodes[bestIndex]; val b = obj.nodes[(bestIndex + 1) % obj.nodes.size]
        val t = bestT.coerceIn(0.02f, 0.98f)
        val p0 = floatArrayOf(a.x, a.y); val p1 = floatArrayOf(a.ox, a.oy)
        val p2 = floatArrayOf(b.ix, b.iy); val p3 = floatArrayOf(b.x, b.y)
        val q0 = mix(p0,p1,t); val q1 = mix(p1,p2,t); val q2 = mix(p2,p3,t)
        val r0 = mix(q0,q1,t); val r1 = mix(q1,q2,t); val mid = mix(r0,r1,t)
        a.ox = q0[0]; a.oy = q0[1]; b.ix = q2[0]; b.iy = q2[1]
        val n = Node(mid[0], mid[1]).apply { ix = r0[0]; iy = r0[1]; ox = r1[0]; oy = r1[1]; smooth = true }
        obj.nodes.add(bestIndex + 1, n)
        return bestIndex + 1
    }

    fun deleteNode(obj: VObj, index: Int): Boolean {
        if (obj.kind != Kind.PATH || index !in obj.nodes.indices || obj.nodes.size <= if (obj.closed) 3 else 2) return false
        obj.nodes.removeAt(index); return true
    }

    /** Split an open path at an interior anchor. The original becomes the first half. */
    fun splitPath(doc: Doc, obj: VObj, index: Int): VObj? {
        if (obj.closed || obj.holes.isNotEmpty() || index <= 0 || index >= obj.nodes.lastIndex || obj.kind != Kind.PATH) return null
        val second = obj.copy()
        second.uid = (doc.objs.maxOfOrNull { it.uid } ?: 0) + 1
        second.name = if (obj.name.isBlank()) "Path ${second.uid}" else "${obj.name} (split)"
        second.nodes = obj.nodes.drop(index).map { it.copy() }.toMutableList()
        obj.nodes = obj.nodes.take(index + 1).map { it.copy() }.toMutableList()
        doc.objs.add(second)
        return second
    }

    /** Join two open paths, choosing the nearest endpoint orientation. */
    fun joinPaths(a: VObj, b: VObj): Boolean {
        if (a.kind != Kind.PATH || b.kind != Kind.PATH || a.closed || b.closed || a.nodes.size < 2 || b.nodes.size < 2 || a.layer != b.layer) return false
        val variants = listOf(
            Triple(a.nodes.first(), b.nodes.first(), 0), Triple(a.nodes.first(), b.nodes.last(), 1),
            Triple(a.nodes.last(), b.nodes.first(), 2), Triple(a.nodes.last(), b.nodes.last(), 3)
        )
        val best = variants.minBy { hypot(it.first.x-it.second.x, it.first.y-it.second.y) }
        val bn = b.nodes.map { it.copy() }.toMutableList()
        when (best.third) {
            0 -> { a.nodes.reverse(); a.nodes.addAll(bn.drop(1)) }
            1 -> { a.nodes.reverse(); bn.reverse(); a.nodes.addAll(bn.drop(1)) }
            2 -> a.nodes.addAll(bn.drop(1))
            3 -> { bn.reverse(); a.nodes.addAll(bn.drop(1)) }
        }
        return true
    }

    fun align(doc: Doc, ids: Set<Int>, mode: Align): Boolean {
        val objs = editable(doc, ids); if (objs.size < 2) return false
        val bounds = objs.associateWith { it.bounds() }
        val target = when (mode) {
            Align.LEFT -> bounds.values.minOf { it.left }; Align.H_CENTER -> bounds.values.map { it.centerX() }.average().toFloat()
            Align.RIGHT -> bounds.values.maxOf { it.right }; Align.TOP -> bounds.values.minOf { it.top }
            Align.V_CENTER -> bounds.values.map { it.centerY() }.average().toFloat(); Align.BOTTOM -> bounds.values.maxOf { it.bottom }
        }
        objs.forEach { o -> val r = bounds.getValue(o); val dx = when(mode) { Align.LEFT -> target-r.left; Align.H_CENTER -> target-r.centerX(); Align.RIGHT -> target-r.right; else -> 0f }; val dy = when(mode) { Align.TOP -> target-r.top; Align.V_CENTER -> target-r.centerY(); Align.BOTTOM -> target-r.bottom; else -> 0f }; translate(o, dx, dy) }
        return true
    }

    fun distribute(doc: Doc, ids: Set<Int>, horizontal: Boolean): Boolean {
        val objs = editable(doc, ids); if (objs.size < 3) return false
        val sorted = objs.sortedBy { if (horizontal) it.bounds().centerX() else it.bounds().centerY() }
        val first = sorted.first().bounds(); val last = sorted.last().bounds()
        val start = if (horizontal) first.centerX() else first.centerY(); val end = if (horizontal) last.centerX() else last.centerY()
        val step = (end-start)/(sorted.size-1)
        sorted.forEachIndexed { i, o -> val r=o.bounds(); val cur=if(horizontal) r.centerX() else r.centerY(); val delta=start+step*i-cur; translate(o, if(horizontal) delta else 0f, if(horizontal) 0f else delta) }
        return true
    }

    private fun editable(doc: Doc, ids: Set<Int>) = doc.objs.filter { it.uid in ids && !it.locked && doc.layers.getOrNull(it.layer)?.let { l -> l.visible && !l.locked } == true }
    private fun translate(o: VObj, dx: Float, dy: Float) { o.nodes.forEach { it.move(dx,dy) }; o.holes.forEach { h -> h.nodes.forEach { it.move(dx,dy) } }; if(o.kind==Kind.TEXT){o.tx+=dx;o.ty+=dy}; if(o.kind==Kind.IMAGE){o.rect.offset(dx,dy)} }
    private fun cubic(a: Node,b: Node,t:Float):Pair<Float,Float>{ val u=1f-t; return Pair(u*u*u*a.x+3*u*u*t*a.ox+3*u*t*t*b.ix+t*t*t*b.x,u*u*u*a.y+3*u*u*t*a.oy+3*u*t*t*b.iy+t*t*t*b.y) }
    private fun mix(a:FloatArray,b:FloatArray,t:Float)=floatArrayOf(a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t)
}
