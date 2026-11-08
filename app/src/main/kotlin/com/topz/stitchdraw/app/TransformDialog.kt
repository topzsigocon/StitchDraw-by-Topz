package com.topz.stitchdraw.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.topz.stitchdraw.digitizing.DigitizingSession
import com.topz.stitchdraw.digitizing.ObjectTransform

/** Numeric move / scale / rotate / mirror of the selected object. Every apply is one Undo step. */
class TransformDialog(private val activity: Activity, private val session: DigitizingSession, private val toast: (String) -> Unit) {

    fun show(objectId: Int) {
        session.select(objectId)
        val b = session.selectedBounds() ?: return
        val w = b.second.x - b.first.x; val h = b.second.y - b.first.y
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        box.addView(TextView(activity).apply { text = "Current size: %.1f x %.1f mm".format(w, h) })
        fun field(label: String, hint: String): EditText {
            box.addView(TextView(activity).apply { text = label })
            return EditText(activity).apply {
                this.hint = hint
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                box.addView(this)
            }
        }
        val dx = field("Move right (mm, negative = left)", "0")
        val dy = field("Move down (mm, negative = up)", "0")
        val sc = field("Scale (%)", "100")
        val rot = field("Rotate clockwise (degrees)", "0")
        val fh = CheckBox(activity).apply { text = "Mirror left-right" }
        val fv = CheckBox(activity).apply { text = "Mirror top-bottom" }
        box.addView(fh); box.addView(fv)
        fun num(e: EditText, def: Float) = e.text.toString().trim().toFloatOrNull() ?: def
        val sv = android.widget.ScrollView(activity).apply { addView(box) }
        AlertDialog.Builder(activity).setTitle("Move / Scale / Rotate").setView(sv)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val pct = num(sc, 100f)
                if (pct <= 0f) { toast("Scale must be above 0%"); return@setPositiveButton }
                val t = ObjectTransform(num(dx, 0f), num(dy, 0f), pct / 100f, num(rot, 0f), fh.isChecked, fv.isChecked)
                if (t.isIdentity) return@setPositiveButton
                if (!session.transformSelected(t)) toast("Can't transform: object is on a locked layer")
                else if (pct / 100f !in ObjectTransform.MIN_SCALE..ObjectTransform.MAX_SCALE) toast("Scale limited to 5% - 2000%")
            }.show()
    }
}
