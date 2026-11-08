package com.topz.stitchdraw.embroidery

import kotlin.math.hypot

/** What a stitch plan costs in machine time that is not stitching. */
data class SequenceStats(val jumps: Int, val trims: Int, val colorChanges: Int, val travelMm: Float)

class SequenceResult(val objects: List<DesignObject>, val before: SequenceStats, val after: SequenceStats, val changed: Boolean)

/**
 * Stitch sequencing: re-orders objects so the machine changes thread less and travels less, without changing how the
 * finished design looks.
 *
 *  - Objects of different colours whose bounding boxes overlap keep their original relative order (the later one is on top).
 *  - Otherwise the order is free: stay with the current colour while something of that colour is available, move to the
 *    nearest one; when the colour is used up, switch to the colour of the earliest remaining object.
 *  - Lines (running, triple, satin, zigzag) may be sewn in the opposite direction when that shortens the jump.
 *  - The result is only used when it never adds colour changes and its cost (travel + 200 mm per colour change) is lower.
 */
object Sequencer {
    private val REVERSIBLE = setOf(StitchType.RUNNING, StitchType.TRIPLE_RUNNING, StitchType.SATIN, StitchType.ZIGZAG)
    private const val MARGIN_MM = 0.3f
    /** One thread change costs about as much machine time as this much jump travel. */
    const val COLOR_CHANGE_COST_MM = 200.0
    private const val MAX_START_TRIES = 150

    fun measure(design: EmbroideryDesign, options: PlannerOptions = PlannerOptions()): SequenceStats = measure(StitchPlanner.plan(design, options))

    fun measure(plan: StitchPlan): SequenceStats {
        var travel = 0.0
        val s = plan.stitches
        for (i in 1 until s.size) if (s[i].cmd == StitchCommand.JUMP) travel += hypot((s[i].x - s[i - 1].x).toDouble(), (s[i].y - s[i - 1].y).toDouble()) / UNITS_PER_MM
        return SequenceStats(plan.stats.jumpCount, plan.stats.trimCount, plan.stats.colorChanges, travel.toFloat())
    }

    private class Item(val o: DesignObject, val index: Int, val lo: DoubleArray, val hi: DoubleArray,
                       val entry: DoubleArray, val exit: DoubleArray, val reversible: Boolean)

    fun optimize(design: EmbroideryDesign, options: PlannerOptions = PlannerOptions()): SequenceResult {
        val before = measure(design, options)
        val hidden = design.layers.filter { !it.visible }.map { it.id }.toSet()
        val slots = design.objects.indices.filter { design.objects[it].layerId !in hidden && design.objects[it].path.size >= 2 }
        val unchanged = SequenceResult(design.objects, before, before, false)
        if (slots.size < 3) return unchanged

        val items = ArrayList<Item>()
        for ((k, slot) in slots.withIndex()) {
            val o = design.objects[slot]
            val solo = StitchPlanner.plan(design.copy(objects = listOf(o)), options)
            val st = solo.stitches
            val first = st.firstOrNull { it.cmd == StitchCommand.STITCH || it.cmd == StitchCommand.JUMP } ?: continue
            val last = st.lastOrNull { it.cmd == StitchCommand.STITCH } ?: continue
            val all = o.path + o.holes.flatten()
            items.add(Item(o, k,
                doubleArrayOf(all.minOf { it.x } - MARGIN_MM.toDouble(), all.minOf { it.y } - MARGIN_MM.toDouble()),
                doubleArrayOf(all.maxOf { it.x } + MARGIN_MM.toDouble(), all.maxOf { it.y } + MARGIN_MM.toDouble()),
                doubleArrayOf(first.x / UNITS_PER_MM.toDouble(), first.y / UNITS_PER_MM.toDouble()),
                doubleArrayOf(last.x / UNITS_PER_MM.toDouble(), last.y / UNITS_PER_MM.toDouble()),
                o.type in REVERSIBLE))
        }
        if (items.size < 3) return unchanged

        // dependencies: j must stay after i when i was first, the colours differ and the boxes overlap
        val n = items.size
        val deps = Array(n) { ArrayList<Int>() }
        for (j in 0 until n) for (i in 0 until j) {
            val a = items[i]; val b = items[j]
            if (a.o.threadIndex == b.o.threadIndex) continue
            if (a.lo[0] <= b.hi[0] && b.lo[0] <= a.hi[0] && a.lo[1] <= b.hi[1] && b.lo[1] <= a.hi[1]) deps[j].add(i)
        }

        class Build(val order: List<Int>, val reversed: BooleanArray, val cost: Double)
        fun build(start: Int): Build {
            val placed = BooleanArray(n)
            val order = ArrayList<Int>(n); val reversed = BooleanArray(n)
            var cx = 0.0; var cy = 0.0; var curColor = -1; var cost = 0.0
            fun available(i: Int) = !placed[i] && deps[i].all { placed[it] }
            fun place(i: Int, rev: Boolean, travel: Double) {
                placed[i] = true; order.add(i); reversed[i] = rev
                val b = items[i]; val ex = if (rev) b.entry else b.exit
                if (curColor != -1 && b.o.threadIndex != curColor) cost += COLOR_CHANGE_COST_MM
                cost += travel; cx = ex[0]; cy = ex[1]; curColor = b.o.threadIndex
            }
            place(start, false, 0.0)                                   // the machine's first move costs nothing
            repeat(n - 1) {
                val avail = (0 until n).filter { available(it) }
                val sameColor = avail.filter { items[it].o.threadIndex == curColor }
                val pool = if (sameColor.isNotEmpty()) sameColor else {
                    val c = items[avail.first()].o.threadIndex           // earliest remaining object decides the next colour
                    avail.filter { items[it].o.threadIndex == c }
                }
                var best = -1; var bestD = Double.MAX_VALUE; var bestRev = false
                for (i in pool) {
                    val it = items[i]
                    val d = hypot(it.entry[0] - cx, it.entry[1] - cy)
                    if (d < bestD - 1e-9) { bestD = d; best = i; bestRev = false }
                    if (it.reversible) {
                        val dr = hypot(it.exit[0] - cx, it.exit[1] - cy)
                        if (dr < bestD - 1e-9) { bestD = dr; best = i; bestRev = true }
                    }
                }
                place(best, bestRev, bestD)
            }
            return Build(order, reversed, cost)
        }
        // try every object that may go first (small designs) or just the original first one (large ones)
        val starts = (0 until n).filter { deps[it].isEmpty() }.let { if (n <= MAX_START_TRIES) it else it.take(1) }
        var chosen = build(starts.first())
        for (st in starts.drop(1)) { val c = build(st); if (c.cost < chosen.cost - 1e-9) chosen = c }
        val order = chosen.order; val reversed = chosen.reversed

        val result = design.objects.toMutableList()
        for ((pos, idx) in order.withIndex()) {
            val it = items[idx]
            result[slots[pos]] = if (reversed[idx]) it.o.copy(path = it.o.path.reversed()) else it.o
        }
        val candidate = design.copy(objects = result)
        val after = measure(candidate, options)
        fun cost(x: SequenceStats) = x.travelMm + COLOR_CHANGE_COST_MM * x.colorChanges
        return if (after.colorChanges <= before.colorChanges && cost(after) < cost(before) - 0.05) SequenceResult(result, before, after, true) else unchanged
    }
}
