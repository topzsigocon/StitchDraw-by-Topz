package com.topz.stitchdraw.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.topz.stitchdraw.digitizing.DigitizingSession
import com.topz.stitchdraw.digitizing.auto.GlyphSource
import com.topz.stitchdraw.digitizing.auto.LetterFill
import com.topz.stitchdraw.digitizing.auto.Lettering
import com.topz.stitchdraw.digitizing.auto.TextAlign
import com.topz.stitchdraw.vector.Vec2

/** Text/lettering: Android's font engine supplies the outlines, the digitizing engine turns them into filled letters. */
class TextTool(private val activity: Activity, private val session: DigitizingSession, private val toast: (String) -> Unit) {

    private class Font(val label: String, val face: Typeface)
    private val fonts = listOf(
        Font("Sans bold", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)),
        Font("Sans", Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)),
        Font("Serif bold", Typeface.create(Typeface.SERIF, Typeface.BOLD)),
        Font("Serif", Typeface.create(Typeface.SERIF, Typeface.NORMAL)),
        Font("Monospace bold", Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)),
        Font("Cursive-style", Typeface.create("cursive", Typeface.BOLD)),
    )

    private fun source(face: Typeface) = GlyphSource { line, heightMm ->
        val size = 100f                                   // work in 1/100 em then scale: keeps curve flattening precise
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size }
        val path = Path()
        paint.getTextPath(line, 0, line.length, 0f, 0f, path)
        val k = heightMm / size
        val out = ArrayList<List<Vec2>>()
        val pm = PathMeasure(path, false)
        val pos = FloatArray(2)
        do {
            val len = pm.length
            if (len <= 0f) continue
            val steps = (len / 0.6f).toInt().coerceIn(8, 400)     // ~0.6 units of 1/100 em
            val pts = ArrayList<Vec2>(steps)
            for (i in 0 until steps) { pm.getPosTan(len * i / steps, pos, null); pts.add(Vec2(pos[0] * k, pos[1] * k)) }
            out.add(pts)
        } while (pm.nextContour())
        out
    }

    fun show() {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        val input = EditText(activity).apply {
            hint = "Type your text (Enter = new line)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 1; maxLines = 4
        }
        box.addView(input)
        val spin = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, fonts.map { it.label }) }
        box.addView(spin)
        val hoopH = session.design.hoop.heightMm
        val maxH = (hoopH * 0.8f).toInt().coerceAtLeast(10)
        val label = TextView(activity); box.addView(label)
        val sb = SeekBar(activity).apply { max = maxH - Lettering.MIN_HEIGHT_MM.toInt(); progress = (15 - Lettering.MIN_HEIGHT_MM.toInt()).coerceIn(0, max) }
        fun h() = sb.progress + Lettering.MIN_HEIGHT_MM.toInt()
        fun upd() { label.text = "Letter height: ${h()} mm" }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = upd()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        upd(); box.addView(sb)
        val align = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Centre", "Left", "Right")) }
        box.addView(align)
        val fillSpin = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
            listOf("Auto (satin for thin strokes, fill for heavy)", "Always satin", "Always fill (tatami)")) }
        box.addView(fillSpin)
        AlertDialog.Builder(activity).setTitle("Add text").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                try {
                    val n = session.addText(input.text.toString(), h().toFloat(), source(fonts[spin.selectedItemPosition].face),
                        when (align.selectedItemPosition) { 1 -> TextAlign.LEFT; 2 -> TextAlign.RIGHT; else -> TextAlign.CENTER },
                        when (fillSpin.selectedItemPosition) { 1 -> LetterFill.SATIN; 2 -> LetterFill.TATAMI; else -> LetterFill.AUTO })
                    toast("Added text ($n letter shapes). Undo removes it.")
                } catch (e: IllegalArgumentException) { toast(e.message ?: "Can't add that text")
                } catch (e: Throwable) { toast("Text failed: ${e.message}") }
            }.show()
    }
}
