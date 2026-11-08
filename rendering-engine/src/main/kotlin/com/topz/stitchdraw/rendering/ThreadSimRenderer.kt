package com.topz.stitchdraw.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan
import com.topz.stitchdraw.embroidery.UNITS_PER_MM
import com.topz.stitchdraw.simulation.Fabric
import com.topz.stitchdraw.simulation.ThreadShade
import kotlin.math.max

/**
 * Embroidery simulator renderer.
 *
 * Each stitch is drawn in three passes so flat colours read as rounded thread:
 * a soft dark shadow under the stitch, the thread body, and a narrow lighter sheen on top shifted toward
 * the light. Dense satin and tatami areas therefore show their characteristic banded texture, because the
 * sheen lines of neighbouring stitches overlap at slightly different angles.
 *
 * Geometry is cached per plan; only the first `upTo` plan entries are drawn so the same renderer serves both
 * the finished preview and stitch-by-stitch playback. Memory: 20 bytes per stitch.
 */
class ThreadSimRenderer {

    private class Run(val rgb: Int, val lines: FloatArray, val planIdx: IntArray, val count: Int)

    private var cachedPlan: StitchPlan? = null
    private var runs: List<Run> = emptyList()
    private var points = FloatArray(0)         // x,y after every plan entry (mm), for the needle marker
    private var jumpLines = FloatArray(0); private var jumpIdx = IntArray(0); private var jumpCount = 0

    var threadWidthMm = 0.42f
    var realistic = true
    var showJumps = true
    var fabric: Fabric = Fabric.WHITE_COTTON

    private val fill = Paint()
    private val weavePaint = Paint().apply { style = Paint.Style.STROKE }
    private val thread = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val jumpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55FF5252 }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFD54F.toInt() }
    private val clip = Rect()

    /** Fabric inside the hoop rectangle (mm) with a visible weave when zoomed in enough. */
    fun drawFabric(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, pxPerMm: Float) {
        fill.color = fabric.base or (0xFF shl 24)
        canvas.drawRect(left, top, right, bottom, fill)
        if (pxPerMm < 3f) return
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        if (canvas.getClipBounds(clip)) {
            val vl = max(left, clip.left.toFloat()); val vr = minOf(right, clip.right.toFloat())
            val vt = max(top, clip.top.toFloat()); val vb = minOf(bottom, clip.bottom.toFloat())
            var step = fabric.weaveMm
            while ((vr - vl) / step > 300 || (vb - vt) / step > 300) step *= 2f
            weavePaint.color = fabric.weave or (0x90 shl 24)
            weavePaint.strokeWidth = max(0.08f, 0.8f / pxPerMm)
            var x = Math.ceil((vl / step).toDouble()).toFloat() * step
            while (x <= vr) { canvas.drawLine(x, vt, x, vb, weavePaint); x += step }
            var y = Math.ceil((vt / step).toDouble()).toFloat() * step
            while (y <= vb) { canvas.drawLine(vl, y, vr, y, weavePaint); y += step }
        }
        canvas.restore()
    }

    /** Draws plan entries [0, upTo). Canvas matrix must already map mm to pixels. */
    fun draw(canvas: Canvas, plan: StitchPlan, pxPerMm: Float, upTo: Int, showNeedle: Boolean) {
        if (plan !== cachedPlan) { rebuild(plan); cachedPlan = plan }
        val w = max(threadWidthMm, 1.4f / pxPerMm)
        for (r in runs) {
            val n = countBelow(r.planIdx, r.count, upTo)
            if (n == 0) continue
            if (!realistic || pxPerMm < 2.2f) {                       // too small to see detail: one flat pass
                thread.strokeWidth = w; thread.color = r.rgb or (0xFF shl 24)
                canvas.drawLines(r.lines, 0, n * 4, thread)
                continue
            }
            val o = w * 0.32f
            thread.strokeWidth = w * 1.5f; thread.color = ThreadShade.shadow(r.rgb) or (0x66 shl 24)
            canvas.save(); canvas.translate(o, o); canvas.drawLines(r.lines, 0, n * 4, thread); canvas.restore()
            thread.strokeWidth = w; thread.color = r.rgb or (0xFF shl 24)
            canvas.drawLines(r.lines, 0, n * 4, thread)
            thread.strokeWidth = w * 0.34f; thread.color = ThreadShade.highlight(r.rgb) or (0xCC shl 24)
            canvas.save(); canvas.translate(-o * 0.5f, -o * 0.5f); canvas.drawLines(r.lines, 0, n * 4, thread); canvas.restore()
        }
        if (showJumps && jumpCount > 0) {
            val n = countBelow(jumpIdx, jumpCount, upTo)
            if (n > 0) { jumpPaint.strokeWidth = 1f / pxPerMm; canvas.drawLines(jumpLines, 0, n * 4, jumpPaint) }
        }
        if (showNeedle && upTo > 0 && upTo <= plan.stitches.size) {
            val x = points[(upTo - 1) * 2]; val y = points[(upTo - 1) * 2 + 1]
            val r = 6f / pxPerMm
            needle.strokeWidth = 1.5f / pxPerMm
            canvas.drawCircle(x, y, r, needle)
            canvas.drawLine(x - r * 1.6f, y, x + r * 1.6f, y, needle)
            canvas.drawLine(x, y - r * 1.6f, x, y + r * 1.6f, needle)
        }
    }

    /** Number of leading segments whose plan index is below [limit] (indices are ascending). */
    private fun countBelow(idx: IntArray, count: Int, limit: Int): Int {
        var lo = 0; var hi = count
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (idx[mid] < limit) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun rebuild(plan: StitchPlan) {
        val result = ArrayList<Run>()
        var buf = FloatArray(256); var idx = IntArray(64); var n = 0
        var color = 0; var started = false
        var jb = FloatArray(64); var ji = IntArray(16); var jn = 0
        var px = 0f; var py = 0f
        points = FloatArray(plan.stitches.size * 2)

        fun flush() { if (n > 0) result.add(Run(color, buf.copyOf(n * 4), idx.copyOf(n), n)); n = 0 }

        for (i in plan.stitches.indices) {
            val s = plan.stitches[i]
            val x = s.x / UNITS_PER_MM; val y = s.y / UNITS_PER_MM
            when (s.cmd) {
                StitchCommand.STITCH -> {
                    val c = plan.threads.getOrNull(plan.threadIndexOf[i])?.rgb ?: 0xFFFFFF
                    if (!started || c != color) { flush(); color = c; started = true }
                    if (n * 4 + 4 > buf.size) buf = buf.copyOf(buf.size * 2)
                    if (n + 1 > idx.size) idx = idx.copyOf(idx.size * 2)
                    buf[n * 4] = px; buf[n * 4 + 1] = py; buf[n * 4 + 2] = x; buf[n * 4 + 3] = y; idx[n] = i; n++
                }
                StitchCommand.JUMP -> {
                    if (jn * 4 + 4 > jb.size) jb = jb.copyOf(jb.size * 2)
                    if (jn + 1 > ji.size) ji = ji.copyOf(ji.size * 2)
                    jb[jn * 4] = px; jb[jn * 4 + 1] = py; jb[jn * 4 + 2] = x; jb[jn * 4 + 3] = y; ji[jn] = i; jn++
                }
                else -> Unit
            }
            points[i * 2] = x; points[i * 2 + 1] = y
            px = x; py = y
        }
        flush()
        runs = result; jumpLines = jb; jumpIdx = ji; jumpCount = jn
    }
}
