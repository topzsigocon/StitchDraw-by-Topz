package com.topz.stitchdraw.vectorart

import com.topz.stitchdraw.embroidery.DesignObject
import com.topz.stitchdraw.embroidery.StitchParams
import com.topz.stitchdraw.embroidery.StitchType
import com.topz.stitchdraw.vector.Vec2
import kotlin.math.max

/**
 * Explicit, non-destructive bridge from VecDraw vector paths to StitchDraw embroidery objects.
 * Original VObj data remains in the vector document; this adapter only creates a conversion preview.
 */
object VectorStitchAdapter {
    data class Conversion(
        val sourceUid: Int,
        val embroideryObject: DesignObject?,
        val warnings: List<String> = emptyList(),
    )

    /**
     * Convert a PATH object. Bézier handles are sampled into millimetre-space points.
     * Text and bitmap objects must first be converted to vector outlines / traced paths.
     */
    fun convertPath(
        source: VObj,
        objectId: Int,
        layerId: Int,
        threadIndex: Int,
        stitchType: StitchType,
        params: StitchParams = StitchParams(),
        curveStepsPerSegment: Int = 12,
    ): Conversion {
        if (source.kind != Kind.PATH) {
            return Conversion(source.uid, null, listOf("Only vector PATH objects can be digitized directly. Convert text to outlines or trace the image first."))
        }
        val warnings = mutableListOf<String>()
        val path = sampleContour(source.nodes, source.closed, curveStepsPerSegment)
        if (path.size < stitchType.minPoints) {
            return Conversion(source.uid, null, listOf("The source path has too few usable points for ${stitchType.label}."))
        }
        if (stitchType.isFill && !source.closed) {
            return Conversion(source.uid, null, listOf("A closed vector contour is required for ${stitchType.label}."))
        }
        if (source.fill2 != null || source.mesh != null || source.tMode != 0 || source.alpha < 0.999f) {
            warnings += "Vector gradients, mesh fills, transparency, and opacity are not embroidery properties; assign thread colors/regions manually."
        }
        val holes = source.holes.mapNotNull { contour ->
            val points = sampleContour(contour.nodes, contour.closed, curveStepsPerSegment)
            points.takeIf { it.size >= 3 }
        }
        if (holes.isNotEmpty() && stitchType != StitchType.TATAMI) {
            warnings += "Interior contours are preserved only for TATAMI in the current embroidery model."
        }
        val obj = DesignObject(
            id = objectId,
            name = source.name.ifBlank { "Vector ${source.uid}" },
            type = stitchType,
            path = path,
            threadIndex = threadIndex.coerceAtLeast(0),
            layerId = layerId,
            params = params,
            holes = if (stitchType == StitchType.TATAMI) holes else emptyList(),
        )
        return Conversion(source.uid, obj, warnings)
    }

    private fun sampleContour(nodes: List<Node>, closed: Boolean, steps: Int): List<Vec2> {
        if (nodes.isEmpty()) return emptyList()
        if (nodes.size == 1) return listOf(Vec2(nodes[0].x, nodes[0].y))
        val segments = if (closed) nodes.size else nodes.size - 1
        val subdivisions = max(2, steps)
        val out = ArrayList<Vec2>(segments * subdivisions + 1)
        for (i in 0 until segments) {
            val a = nodes[i]
            val b = nodes[(i + 1) % nodes.size]
            val curved = !a.outLine() || !b.inLine()
            val count = if (curved) subdivisions else 1
            for (j in 0 until count) {
                val t = j.toFloat() / count
                val u = 1f - t
                val x = u*u*u*a.x + 3f*u*u*t*a.ox + 3f*u*t*t*b.ix + t*t*t*b.x
                val y = u*u*u*a.y + 3f*u*u*t*a.oy + 3f*u*t*t*b.iy + t*t*t*b.y
                out += Vec2(x, y)
            }
        }
        if (!closed) out += Vec2(nodes.last().x, nodes.last().y)
        return out.distinct()
    }
}
