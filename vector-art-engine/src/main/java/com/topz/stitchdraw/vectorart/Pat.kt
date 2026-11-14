package com.topz.stitchdraw.vectorart

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.sqrt

/** Mask patterns for bitmap-pattern transparency (white = opaque, black = transparent, like CorelDRAW). */
object Pat {
    val NAMES = listOf("Checker", "Soft dots", "Stripes", "Diagonal", "Noise", "Radial")
    private val cache = HashMap<Int, Bitmap>()
    private const val N = 64

    fun base(idx: Int): Bitmap = cache.getOrPut(idx) {
        val px = IntArray(N * N); val rnd = java.util.Random(7)
        for (y in 0 until N) for (x in 0 until N) {
            val l = when (idx) {
                0 -> if ((x / 16 + y / 16) % 2 == 0) 1f else 0f
                1 -> { val dx = (x % 32) - 15.5f; val dy = (y % 32) - 15.5f; (1f - sqrt(dx * dx + dy * dy) / 15f).coerceIn(0f, 1f) }
                2 -> if ((y / 8) % 2 == 0) 1f else 0f
                3 -> if (((x + y) / 8) % 2 == 0) 1f else 0f
                4 -> rnd.nextFloat()
                else -> { val dx = x - 31.5f; val dy = y - 31.5f; (1f - sqrt(dx * dx + dy * dy) / 32f).coerceIn(0f, 1f) }
            }
            val g = (l * 255).toInt(); px[y * N + x] = Color.rgb(g, g, g)
        }
        Bitmap.createBitmap(px, N, N, Bitmap.Config.ARGB_8888)
    }

    /** Grayscale mask with white-area opacity tA1 and black-area opacity tA2 baked in; cached on the object. */
    fun baked(o: VObj): Bitmap {
        val key = "${o.tPat}|${o.tA1}|${o.tA2}|${System.identityHashCode(o.tBmp)}"
        val have = o.tBaked
        if (have != null && o.tKey == key) return have
        val custom = o.tBmp
        val src = if (o.tPat < 0 && custom != null) custom else base(max(o.tPat, 0))
        val w = src.width; val h = src.height; val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]; val lum = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            val g = ((o.tA2 + (o.tA1 - o.tA2) * lum).coerceIn(0f, 1f) * 255).toInt(); px[i] = Color.rgb(g, g, g)
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        o.tBaked = out; o.tKey = key; return out
    }
}
