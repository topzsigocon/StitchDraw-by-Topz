package com.topz.stitchdraw.rendering

import android.graphics.Canvas
import android.graphics.Paint
import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan
import com.topz.stitchdraw.embroidery.UNITS_PER_MM
import kotlin.math.max

/**
 * Draws a [StitchPlan] onto a Canvas whose matrix already maps millimetres to pixels.
 * Geometry is cached per plan instance, so panning/zooming only re-issues drawLines calls.
 * Memory use: 16 bytes per stitch (one float line segment).
 */
class DesignRenderer {

    private class Run(val color: Int, val lines: FloatArray, val count: Int)

    private var cachedPlan: StitchPlan? = null
    private var runs: List<Run> = emptyList()
    private var jumpLines: FloatArray = FloatArray(0)
    private var jumpCount = 0

    private val threadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val jumpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FFFFFF
    }

    /** Thread thickness in mm (real embroidery thread is ~0.3-0.4 mm). */
    var threadWidthMm = 0.38f
    var showJumps = true

    fun draw(canvas: Canvas, plan: StitchPlan, pxPerMm: Float) {
        if (plan !== cachedPlan) { rebuild(plan); cachedPlan = plan }
        threadPaint.strokeWidth = max(threadWidthMm, 1.4f / pxPerMm)
        for (r in runs) {
            threadPaint.color = r.color or (0xFF shl 24)
            canvas.drawLines(r.lines, 0, r.count * 4, threadPaint)
        }
        if (showJumps && jumpCount > 0) {
            jumpPaint.strokeWidth = 1f / pxPerMm
            canvas.drawLines(jumpLines, 0, jumpCount * 4, jumpPaint)
        }
    }

    private fun rebuild(plan: StitchPlan) {
        val result = ArrayList<Run>()
        var buf = FloatArray(256)
        var n = 0
        var color = 0
        var started = false
        var jbuf = FloatArray(64)
        var jn = 0
        var px = 0f
        var py = 0f

        fun flush() {
            if (n > 0) result.add(Run(color, buf.copyOf(n * 4), n))
            n = 0
        }

        for (i in plan.stitches.indices) {
            val s = plan.stitches[i]
            val x = s.x / UNITS_PER_MM
            val y = s.y / UNITS_PER_MM
            when (s.cmd) {
                StitchCommand.STITCH -> {
                    val c = plan.threads.getOrNull(plan.threadIndexOf[i])?.rgb ?: 0xFFFFFF
                    if (!started || c != color) { flush(); color = c; started = true }
                    if (n * 4 + 4 > buf.size) buf = buf.copyOf(buf.size * 2)
                    buf[n * 4] = px; buf[n * 4 + 1] = py; buf[n * 4 + 2] = x; buf[n * 4 + 3] = y
                    n++
                }
                StitchCommand.JUMP -> {
                    if (jn * 4 + 4 > jbuf.size) jbuf = jbuf.copyOf(jbuf.size * 2)
                    jbuf[jn * 4] = px; jbuf[jn * 4 + 1] = py; jbuf[jn * 4 + 2] = x; jbuf[jn * 4 + 3] = y
                    jn++
                }
                else -> Unit
            }
            px = x; py = y
        }
        flush()
        runs = result
        jumpLines = jbuf
        jumpCount = jn
    }
}
