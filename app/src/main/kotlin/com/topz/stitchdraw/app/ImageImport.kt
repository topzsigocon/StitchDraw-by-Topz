package com.topz.stitchdraw.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.topz.stitchdraw.digitizing.DigitizingSession
import com.topz.stitchdraw.digitizing.auto.AutoDigitizer
import com.topz.stitchdraw.digitizing.auto.AutoOptions
import com.topz.stitchdraw.digitizing.auto.ImageData
import kotlin.concurrent.thread

/** Picks a PNG/JPG/PDF, decodes it small (low RAM), asks for options, auto-digitizes off the UI thread. */
class ImageImport(private val activity: Activity, private val session: DigitizingSession, private val toast: (String) -> Unit) {

    fun launchPicker() {
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp", "application/pdf", "image/svg+xml"))
        }, REQ_IMAGE)
    }

    fun onPicked(uri: Uri) {
        val mime = activity.contentResolver.getType(uri) ?: ""
        val name = uri.lastPathSegment?.lowercase() ?: ""
        if (mime == "image/svg+xml" || name.endsWith(".svg")) { importSvg(uri); return }
        val img = try { decode(uri) } catch (e: Throwable) { toast("Can't read that file: ${e.message}"); return }
        if (img == null) { toast("Unsupported or empty image"); return }
        showOptions(img)
    }

    private fun importSvg(uri: Uri) {
        thread(name = "svg-import") {
            try {
                val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Exception("cannot open file")
                if (bytes.size > 20_000_000) throw Exception("file too large")
                val svg = com.topz.stitchdraw.digitizing.auto.SvgImporter.parse(bytes)
                val width = (session.design.hoop.widthMm * 0.8f).coerceAtMost(svg.width * 1000f)
                activity.runOnUiThread {
                    val n = session.applySvg(svg, width)
                    toast(if (n == 0) "SVG has no drawable shapes" else "Imported $n objects from SVG (one Undo removes them)")
                }
            } catch (e: Throwable) { activity.runOnUiThread { toast("SVG import failed: ${e.message}") } }
        }
    }

    private fun decode(uri: Uri): ImageData? {
        val cr = activity.contentResolver
        val mime = cr.getType(uri) ?: ""
        val bmp: Bitmap = if (mime == "application/pdf") {
            val pfd = cr.openFileDescriptor(uri, "r") ?: return null
            pfd.use {
                PdfRenderer(it).use { r ->
                    if (r.pageCount == 0) return null
                    r.openPage(0).use { p ->
                        val scale = 600f / maxOf(p.width, p.height)
                        val b = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        Canvas(b).drawColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1000) sample *= 2
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val data = ImageData(bmp.width, bmp.height, px)
        bmp.recycle()
        return data
    }

    private fun showOptions(img: ImageData) {
        val hoopMm = (session.design.hoop.widthMm * 0.9f).toInt().coerceAtLeast(20)
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun slider(label: String, min: Int, max: Int, start: Int, fmt: (Int) -> String): () -> Int {
            val tv = TextView(activity); box.addView(tv)
            val sb = SeekBar(activity).apply { this.max = max - min; progress = start - min }
            fun upd() { tv.text = "$label: ${fmt(sb.progress + min)}" }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = upd()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            upd(); box.addView(sb)
            return { sb.progress + min }
        }
        val colors = slider("Thread colors", 2, 12, 5) { "$it" }
        val width = slider("Design width", 20, hoopMm.coerceAtLeast(21), hoopMm.coerceAtMost(80)) { "$it mm" }
        val skip = CheckBox(activity).apply { text = "Skip background (don't stitch fabric colour)"; isChecked = true }
        box.addView(skip)
        AlertDialog.Builder(activity).setTitle("Auto-digitize image").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Digitize") { _, _ -> run(img, AutoOptions(colors = colors(), widthMm = width().toFloat(), skipBackground = skip.isChecked, params = session.params)) }
            .show()
    }

    private fun run(img: ImageData, opts: AutoOptions) {
        toast("Digitizing…")
        thread(name = "auto-digitize") {
            try {
                val result = AutoDigitizer.run(img, opts)
                activity.runOnUiThread {
                    val n = session.applyAutoDigitize(result)
                    toast(if (n == 0) "No stitchable regions found - try more colors or turn off Skip background" else "Added $n regions (one Undo removes them)")
                }
            } catch (e: Throwable) {
                activity.runOnUiThread { toast("Auto-digitize failed: ${e.message}") }
            }
        }
    }

    companion object { const val REQ_IMAGE = 7010 }
}
