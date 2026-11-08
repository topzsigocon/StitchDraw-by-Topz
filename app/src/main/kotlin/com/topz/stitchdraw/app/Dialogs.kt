package com.topz.stitchdraw.app

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.topz.stitchdraw.embroidery.StitchParams
import com.topz.stitchdraw.embroidery.ThreadColor

private fun Context.dialogBuilder() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

private fun Context.field(hint: String, text: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
    EditText(this).apply {
        this.hint = hint; setText(text); inputType = type
        setTextColor(Theme.TEXT); setHintTextColor(Theme.TEXT_DIM)
        setSingleLine()
    }

fun promptText(ctx: Context, title: String, initial: String, onOk: (String) -> Unit) {
    val input = ctx.field(title, initial)
    ctx.dialogBuilder().setTitle(title).setView(input)
        .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
        .setNegativeButton("Cancel", null).show()
}

fun promptStitchLength(ctx: Context, currentMm: Float, onOk: (Float) -> Unit) {
    val input = ctx.field("mm", "%.1f".format(currentMm), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    ctx.dialogBuilder()
        .setTitle("Stitch length (${StitchParams.MIN_LENGTH}–${StitchParams.MAX_LENGTH} mm)")
        .setView(input)
        .setPositiveButton("OK") { _, _ ->
            val v = input.text.toString().replace(',', '.').toFloatOrNull()
            if (v != null && v in StitchParams.MIN_LENGTH..StitchParams.MAX_LENGTH) onOk(v)
        }
        .setNegativeButton("Cancel", null).show()
}

/** Thread color editor with synchronized HEX and RGB fields. */
fun promptColor(ctx: Context, initial: ThreadColor?, onOk: (ThreadColor) -> Unit) {
    val start = initial ?: ThreadColor(0x1E88E5, "New thread")
    val name = ctx.field("Name", start.name)
    val hex = ctx.field("HEX  #RRGGBB", start.hex)
    val r = ctx.field("R", start.red.toString(), InputType.TYPE_CLASS_NUMBER)
    val g = ctx.field("G", start.green.toString(), InputType.TYPE_CLASS_NUMBER)
    val b = ctx.field("B", start.blue.toString(), InputType.TYPE_CLASS_NUMBER)
    val preview = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(28))
        roundBg(start.rgb or (0xFF shl 24), 6f, Theme.BORDER, 1f)
    }
    var syncing = false
    fun showColor(c: ThreadColor) { preview.roundBg(c.rgb or (0xFF shl 24), 6f, Theme.BORDER, 1f) }
    fun watch(src: EditText, onChange: () -> Unit) = src.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { if (!syncing) { syncing = true; try { onChange() } finally { syncing = false } } }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
    })
    watch(hex) {
        ThreadColor.parseHex(hex.text.toString())?.let {
            r.setText(it.red.toString()); g.setText(it.green.toString()); b.setText(it.blue.toString()); showColor(it)
        }
    }
    val rgbChanged = {
        val c = ThreadColor.rgb(r.text.toString().toIntOrNull() ?: 0, g.text.toString().toIntOrNull() ?: 0, b.text.toString().toIntOrNull() ?: 0)
        hex.setText(c.hex); showColor(c)
    }
    watch(r, rgbChanged); watch(g, rgbChanged); watch(b, rgbChanged)

    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
        addView(name); addView(hex)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            for (f in listOf(r, g, b)) addView(f, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(preview)
    }
    ctx.dialogBuilder().setTitle(if (initial == null) "Add thread color" else "Edit thread color").setView(box)
        .setPositiveButton("OK") { _, _ ->
            val parsed = ThreadColor.parseHex(hex.text.toString(), name.text.toString())
            if (parsed != null) onOk(parsed.copy(name = name.text.toString().ifBlank { parsed.hex }))
        }
        .setNegativeButton("Cancel", null).show()
}

fun confirm(ctx: Context, message: String, onYes: () -> Unit) {
    ctx.dialogBuilder().setMessage(message)
        .setPositiveButton("Yes") { _, _ -> onYes() }
        .setNegativeButton("No", null).show()
}
