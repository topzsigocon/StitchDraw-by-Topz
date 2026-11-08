package com.topz.stitchdraw.embroidery

import com.topz.stitchdraw.vector.Vec2

/** Embroidery machine units: 1 unit = 0.1 mm (the native resolution of DST/EXP/JEF-class formats). */
const val UNITS_PER_MM = 10f

/**
 * Stitch types. Serialized by NAME in .STD files, so existing names must never change; new types are only appended.
 *  - RUNNING / TRIPLE_RUNNING: the path is the stitch line.
 *  - SATIN / ZIGZAG: the path is the centre line of a column of width [StitchParams.widthMm].
 *  - TATAMI: the path is the closed outline of a filled region.
 *  - APPLIQUE: the path is the closed outline of the fabric shape: placement line, STOP (place fabric),
 *    tack-down, STOP (trim fabric), then a satin cover border of width [StitchParams.widthMm].
 */
enum class StitchType(val label: String) {
    RUNNING("Running Stitch"),
    TRIPLE_RUNNING("Triple Run"),
    SATIN("Satin"),
    ZIGZAG("Zigzag"),
    TATAMI("Tatami Fill"),
    APPLIQUE("Appliqué");

    /** Types whose geometry is a closed region rather than a line. */
    val isFill: Boolean get() = this == TATAMI || this == APPLIQUE
    /** Minimum number of digitized points for a valid object. */
    val minPoints: Int get() = if (isFill) 3 else 2
}

enum class StitchCommand { STITCH, JUMP, TRIM, COLOR_CHANGE, STOP, END }

/** One machine instruction at an absolute position in 0.1 mm units. */
data class StitchPoint(val x: Int, val y: Int, val cmd: StitchCommand = StitchCommand.STITCH)

data class ThreadColor(val rgb: Int, val name: String = "", val brand: String = "") {
    val hex: String get() = "#%06X".format(rgb and 0xFFFFFF)
    val red: Int get() = (rgb shr 16) and 0xFF
    val green: Int get() = (rgb shr 8) and 0xFF
    val blue: Int get() = rgb and 0xFF

    companion object {
        /** Accepts "#RRGGBB", "RRGGBB" or "#RGB". Returns null if invalid. */
        fun parseHex(text: String, name: String = ""): ThreadColor? {
            var t = text.trim().removePrefix("#")
            if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
            if (t.length != 6) return null
            val v = t.toIntOrNull(16) ?: return null
            return ThreadColor(v, name.ifBlank { "#${t.uppercase()}" })
        }
        fun rgb(r: Int, g: Int, b: Int, name: String = "") =
            ThreadColor(((r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)), name)
    }
}

data class StitchParams(
    val stitchLengthMm: Float = 2.5f,
    val tieIn: Boolean = true,
    val tieOut: Boolean = true,
    /** Distance between satin stitches / tatami rows (mm). Smaller = denser. */
    val densityMm: Float = 0.4f,
    /** Satin / zigzag column width (mm). */
    val widthMm: Float = 3.0f,
    /** Tatami row angle in degrees. */
    val angleDeg: Float = 45f,
    val underlay: Boolean = true,
    /** Extra width (satin) or row extension (tatami) that compensates for fabric pull (mm). */
    val pullCompMm: Float = 0.2f,
) {
    init {
        require(stitchLengthMm in MIN_LENGTH..MAX_LENGTH) { "stitch length out of range" }
        require(densityMm in MIN_DENSITY..MAX_DENSITY) { "density out of range" }
        require(widthMm in MIN_WIDTH..MAX_WIDTH) { "width out of range" }
        require(pullCompMm in 0f..MAX_PULL) { "pull compensation out of range" }
    }
    companion object {
        const val MIN_LENGTH = 0.3f
        const val MAX_LENGTH = 12.1f   // DST per-stitch limit (+/-121 units)
        const val MIN_DENSITY = 0.2f
        const val MAX_DENSITY = 4.0f
        const val MIN_WIDTH = 0.5f
        const val MAX_WIDTH = 12.0f    // a satin column must fit one machine stitch
        const val MAX_PULL = 1.5f
    }
}

data class Layer(val id: Int, val name: String, val visible: Boolean = true, val locked: Boolean = false)

data class DesignObject(
    val id: Int,
    val name: String,
    val type: StitchType,
    val path: List<Vec2>,
    val threadIndex: Int,
    val layerId: Int,
    val params: StitchParams = StitchParams(),
    /** Cut-outs inside a fill (only used by TATAMI): areas that must stay unstitched, e.g. the counter of an "O". */
    val holes: List<List<Vec2>> = emptyList(),
)

data class Hoop(val widthMm: Float, val heightMm: Float, val name: String) {
    companion object {
        val HOOP_100 = Hoop(100f, 100f, "100 x 100 mm")
        val HOOP_130 = Hoop(130f, 180f, "130 x 180 mm")
        val HOOP_200 = Hoop(200f, 300f, "200 x 300 mm")
        val ALL = listOf(HOOP_100, HOOP_130, HOOP_200)
    }
}

/**
 * Immutable design document. Every edit returns a new instance, which makes undo/redo trivial
 * and means a UI/workspace change can never half-modify the design.
 */
data class EmbroideryDesign(
    val hoop: Hoop = Hoop.HOOP_100,
    val threads: List<ThreadColor> = DEFAULT_PALETTE,
    val layers: List<Layer> = listOf(Layer(1, "Layer 1")),
    val objects: List<DesignObject> = emptyList(),
    val nextObjectId: Int = 1,
    val nextLayerId: Int = 2,
) {
    fun obj(id: Int) = objects.firstOrNull { it.id == id }

    fun addObject(o: DesignObject) = copy(objects = objects + o, nextObjectId = maxOf(nextObjectId, o.id + 1))
    fun replaceObject(o: DesignObject) = copy(objects = objects.map { if (it.id == o.id) o else it })
    fun removeObject(id: Int) = copy(objects = objects.filter { it.id != id })
    fun addThread(t: ThreadColor) = copy(threads = threads + t)
    fun replaceThread(i: Int, t: ThreadColor) =
        if (i in threads.indices) copy(threads = threads.toMutableList().also { it[i] = t }) else this
    fun addLayer(name: String): EmbroideryDesign = copy(layers = layers + Layer(nextLayerId, name), nextLayerId = nextLayerId + 1)
    fun replaceLayer(l: Layer) = copy(layers = layers.map { if (it.id == l.id) l else it })

    /** Moves object one step later (drawn/stitched after) or earlier in sequence. */
    fun moveObject(id: Int, delta: Int): EmbroideryDesign {
        val i = objects.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in objects.indices) return this
        val list = objects.toMutableList()
        list.add(j, list.removeAt(i))
        return copy(objects = list)
    }

    companion object {
        val DEFAULT_PALETTE = listOf(
            ThreadColor(0xE53935, "Red"), ThreadColor(0x1E88E5, "Blue"), ThreadColor(0x43A047, "Green"),
            ThreadColor(0xFDD835, "Yellow"), ThreadColor(0x212121, "Black"), ThreadColor(0xFAFAFA, "White"),
            ThreadColor(0x8E24AA, "Purple"), ThreadColor(0xFB8C00, "Orange"),
        )
    }
}
