package com.topz.stitchdraw.digitizing.auto

import com.topz.stitchdraw.embroidery.DesignObject
import com.topz.stitchdraw.embroidery.StitchParams
import com.topz.stitchdraw.embroidery.StitchType
import com.topz.stitchdraw.vector.Vec2

/** Supplies flattened glyph outlines for one line of text. Android implements it with Paint.getTextPath. */
fun interface GlyphSource {
    /** Closed contours in mm, +Y down, baseline at y = 0, starting at x = 0. [heightMm] is the font size (em height). */
    fun contours(line: String, heightMm: Float): List<List<Vec2>>
}

enum class TextAlign { LEFT, CENTER, RIGHT }

/** AUTO: satin where the strokes are thin enough (<= [Lettering.SATIN_MAX_MM]), tatami fill for heavy letters. */
enum class LetterFill { AUTO, SATIN, TATAMI }

class TextLayout(val groups: List<Pair<List<Vec2>, List<List<Vec2>>>>, val widthMm: Float, val heightMm: Float)

object Lettering {
    const val MIN_HEIGHT_MM = 4f
    const val SATIN_MAX_MM = 6f

    /** Turns a layout into design objects: satin columns for thin letters, tatami (with counters) for the rest. */
    fun toObjects(layout: TextLayout, fill: LetterFill, params: StitchParams, thread: Int, layerId: Int, firstId: Int, label: String): List<DesignObject> {
        val out = ArrayList<DesignObject>(); var id = firstId
        for ((outer, holes) in layout.groups) {
            val satinWidth = when (fill) {
                LetterFill.TATAMI -> 0f
                LetterFill.SATIN -> SatinSkeleton.thinWidthMm(outer, holes, StitchParams.MAX_WIDTH)
                LetterFill.AUTO -> SatinSkeleton.thinWidthMm(outer, holes, SATIN_MAX_MM)
            }
            if (satinWidth > 0f) {
                val cols = SatinSkeleton.columnsFor(outer, holes)
                if (cols.isNotEmpty()) {
                    for (c in cols) out.add(DesignObject(id++, "Text \"$label\" satin", StitchType.SATIN, c.path, thread, layerId,
                        params.copy(widthMm = c.widthMm.coerceIn(StitchParams.MIN_WIDTH, StitchParams.MAX_WIDTH))))
                    continue
                }
            }
            out.add(DesignObject(id++, "Text \"$label\"", StitchType.TATAMI, outer, thread, layerId, params, holes))
        }
        return out
    }

    /**
     * Lays out (possibly multi-line) text centred on (0,0) and nests the contours into outline+holes groups
     * (so "O", "A", "B" keep their counters). Throws IllegalArgumentException for empty text or tiny sizes.
     */
    fun layout(text: String, heightMm: Float, source: GlyphSource, align: TextAlign = TextAlign.CENTER, lineSpacing: Float = 1.25f): TextLayout {
        require(heightMm >= MIN_HEIGHT_MM) { "Text height must be at least ${MIN_HEIGHT_MM.toInt()} mm - smaller letters cannot be stitched cleanly" }
        val lines = text.split('\n').map { it.trimEnd() }
        require(lines.any { it.isNotBlank() }) { "Text is empty" }
        data class L(val c: List<List<Vec2>>, val minX: Float, val maxX: Float)
        val laid = lines.map { line ->
            val c = if (line.isBlank()) emptyList() else source.contours(line, heightMm).filter { it.size >= 3 }
            L(c, c.minOfOrNull { p -> p.minOf { it.x } } ?: 0f, c.maxOfOrNull { p -> p.maxOf { it.x } } ?: 0f)
        }
        val maxW = laid.maxOf { it.maxX - it.minX }
        val all = ArrayList<List<Vec2>>()
        laid.forEachIndexed { i, l ->
            if (l.c.isEmpty()) return@forEachIndexed
            val w = l.maxX - l.minX
            val dx = when (align) { TextAlign.LEFT -> 0f; TextAlign.CENTER -> (maxW - w) / 2; TextAlign.RIGHT -> maxW - w } - l.minX
            val dy = i * heightMm * lineSpacing
            for (p in l.c) all.add(p.map { Vec2(it.x + dx, it.y + dy) })
        }
        require(all.isNotEmpty()) { "This font has no outlines for that text" }
        val minX = all.minOf { p -> p.minOf { it.x } }; val maxX = all.maxOf { p -> p.maxOf { it.x } }
        val minY = all.minOf { p -> p.minOf { it.y } }; val maxY = all.maxOf { p -> p.maxOf { it.y } }
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        val centred = all.map { p -> p.map { Vec2(it.x - cx, it.y - cy) } }
        return TextLayout(SvgImporter.nest(centred), maxX - minX, maxY - minY)
    }
}
