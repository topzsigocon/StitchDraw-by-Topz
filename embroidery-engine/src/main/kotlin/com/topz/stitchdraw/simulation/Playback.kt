package com.topz.stitchdraw.simulation

import com.topz.stitchdraw.embroidery.StitchCommand
import com.topz.stitchdraw.embroidery.StitchPlan
import kotlin.math.max
import kotlin.math.min

/**
 * Stitch-by-stitch playback state. Pure logic (no Android types) so it is unit tested and the renderer only
 * asks "how many plan entries are visible". Independent of the digitizing session: it only reads a [StitchPlan].
 */
class Playback(total: Int = 0) {
    companion object {
        /** Stitches per second. The first value is roughly a real machine at 900 stitches/minute. */
        val SPEEDS = intArrayOf(15, 60, 240, 1000, 4000)
        val SPEED_LABELS = arrayOf("1×", "4×", "16×", "65×", "260×")
    }

    var total: Int = total; private set
    /** Fractional number of plan entries already sewn (0..total). */
    var position: Float = 0f; private set
    var playing: Boolean = false; private set
    var speedIndex: Int = 2; private set

    val shown: Int get() = position.toInt().coerceIn(0, total)
    val finished: Boolean get() = total > 0 && position >= total
    val fraction: Float get() = if (total == 0) 0f else position / total
    val stitchesPerSecond: Int get() = SPEEDS[speedIndex]

    /** Plan changed (edited design): keep the same relative place, clamp to the new length. */
    fun setTotal(n: Int) { total = max(0, n); position = min(position, total.toFloat()); if (total == 0) playing = false }

    fun play() { if (total == 0) return; if (finished) position = 0f; playing = true }
    fun pause() { playing = false }
    fun toggle() { if (playing) pause() else play() }
    fun restart() { position = 0f }
    fun showAll() { position = total.toFloat(); playing = false }

    fun setSpeed(i: Int) { speedIndex = i.coerceIn(0, SPEEDS.lastIndex) }
    fun cycleSpeed() { speedIndex = (speedIndex + 1) % SPEEDS.size }

    /** Advances by wall-clock time. Returns true when the picture changed. Stops at the end. */
    fun advance(dtMs: Long): Boolean {
        if (!playing || total == 0) return false
        val before = shown
        position = min(total.toFloat(), position + stitchesPerSecond * (dtMs.coerceIn(0, 100) / 1000f))
        if (position >= total) playing = false
        return shown != before || !playing
    }

    /** Manual single step (pauses). */
    fun step(delta: Int) { playing = false; position = (shown + delta).coerceIn(0, total).toFloat() }

    fun seekFraction(f: Float) { position = (f.coerceIn(0f, 1f) * total) }
    fun seekTo(index: Int) { position = index.coerceIn(0, total).toFloat() }
}

/** Facts derived from a plan for the playback HUD and colour-block navigation. */
class PlaybackIndex(val plan: StitchPlan) {
    /** Plan indices at which a colour change / machine stop happens, ascending. */
    val boundaries: IntArray = plan.stitches.indices
        .filter { plan.stitches[it].cmd == StitchCommand.COLOR_CHANGE || plan.stitches[it].cmd == StitchCommand.STOP }
        .toIntArray()

    /** stitchesBefore[i] = number of STITCH entries among plan indices < i. */
    private val stitchesBefore = IntArray(plan.stitches.size + 1).also { a ->
        for (i in plan.stitches.indices) a[i + 1] = a[i] + if (plan.stitches[i].cmd == StitchCommand.STITCH) 1 else 0
    }

    val totalStitches: Int get() = stitchesBefore.last()
    val colorBlocks: Int get() = boundaries.size + 1

    fun stitchesSewn(shown: Int): Int = stitchesBefore[shown.coerceIn(0, plan.stitches.size)]

    /** 1-based colour block the needle is currently in. */
    fun blockAt(shown: Int): Int = boundaries.count { it < shown } + 1

    fun threadAt(shown: Int): Int? {
        var i = shown.coerceIn(0, plan.stitches.size) - 1
        while (i >= 0 && plan.stitches[i].cmd == StitchCommand.END) i--
        return if (i >= 0) plan.threadIndexOf[i] else plan.threadIndexOf.firstOrNull()
    }

    fun objectAt(shown: Int): Int? {
        var i = shown.coerceIn(0, plan.stitches.size) - 1
        while (i >= 0 && plan.objectOf[i] < 0) i--
        return if (i >= 0) plan.objectOf[i] else null
    }

    /** Plan index where the next colour block begins (first entry after a colour change), or the end of the plan. */
    fun nextBlockStart(shown: Int): Int = boundaries.map { it + 1 }.firstOrNull { it > shown } ?: plan.stitches.size

    fun prevBlockStart(shown: Int): Int {
        // start of the current block, or of the previous one if already at a block start
        val starts = listOf(0) + boundaries.map { it + 1 }
        return starts.lastOrNull { it < shown } ?: 0
    }

    /** Estimated sewing time in seconds at [stitchesPerMinute]. */
    fun estimatedSeconds(shown: Int, stitchesPerMinute: Int = 800): Float =
        stitchesSewn(shown) * 60f / stitchesPerMinute
}

/** Fabric backgrounds for the preview. */
enum class Fabric(val label: String, val base: Int, val weave: Int, val weaveMm: Float) {
    WHITE_COTTON("White cotton", 0xF2F0EA, 0xD9D6CC, 0.55f),
    BLACK_COTTON("Black cotton", 0x1C1C1E, 0x2E2E31, 0.55f),
    DENIM("Denim", 0x3B5A82, 0x2F4A6E, 0.7f),
    FELT_GREY("Grey felt", 0x8D8F93, 0x7D7F83, 0.9f),
    LINEN("Linen", 0xD8C7A5, 0xC4B28E, 0.8f),
    RED_TWILL("Red twill", 0x9E2A2F, 0x86222A, 0.65f);
}

/** Thread shading used to fake a rounded, glossy thread from flat colours. */
object ThreadShade {
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    fun luminance(rgb: Int): Float =
        (0.2126f * ((rgb shr 16) and 0xFF) + 0.7152f * ((rgb shr 8) and 0xFF) + 0.0722f * (rgb and 0xFF)) / 255f

    fun shadow(rgb: Int) = mix(rgb, 0x000000, 0.55f)
    /** Lighter streak along the stitch; dark threads get a stronger lift so the sheen stays visible. */
    fun highlight(rgb: Int) = mix(rgb, 0xFFFFFF, if (luminance(rgb) < 0.25f) 0.40f else 0.30f)
}
