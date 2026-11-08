package com.topz.stitchdraw.app

import android.app.Activity
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.topz.stitchdraw.digitizing.DigitizingSession
import com.topz.stitchdraw.simulation.Fabric
import com.topz.stitchdraw.simulation.Playback
import com.topz.stitchdraw.simulation.PlaybackIndex

/**
 * Embroidery simulator controls: play/pause, step, seek, speed, colour-block jumps, fabric and realism.
 * Lives in a non-focusable popup over the bottom of the screen so it does not touch the workspace layout,
 * and only reads the stitch plan, so it never affects the digitizing session.
 */
class SimulatorBar(
    private val activity: Activity,
    private val canvas: DesignCanvasView,
    private val session: DigitizingSession,
) {
    private var popup: PopupWindow? = null
    private val playback = Playback()
    private var index: PlaybackIndex? = null
    private var lastFrame = 0L
    private var userSeeking = false

    private lateinit var playBtn: android.widget.Button
    private lateinit var speedBtn: android.widget.Button
    private lateinit var seek: SeekBar
    private lateinit var hud: TextView

    val isOpen: Boolean get() = popup != null
    var onClosed: (() -> Unit)? = null

    fun toggle(anchor: View) = if (isOpen) close() else open(anchor)

    fun open(anchor: View) {
        if (isOpen) return
        syncPlan()
        playback.showAll()                       // open on the finished result; Play replays it from the start
        canvas.playback = playback
        val c = activity
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
            roundBg(0xF0151519.toInt(), 12f, Theme.ACCENT, 1f)
        }
        hud = c.label("", 12f, Theme.TEXT)
        col.addView(hud)
        seek = SeekBar(c).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) { playback.seekFraction(v / 1000f); refresh() }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true; playback.pause(); updateButtons() }
                override fun onStopTrackingTouch(sb: SeekBar?) { userSeeking = false }
            })
        }
        col.addView(seek)

        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(text: String, tint: Int = Theme.ACCENT, act: () -> Unit) =
            c.textButton(text, tint) { act(); refresh() }.also {
                row.addView(it, LinearLayout.LayoutParams(0, c.dp(44), 1f).apply { marginEnd = c.dp(4) })
            }
        btn("⏮") { playback.restart(); playback.pause(); updateButtons() }
        btn("⏪") { index?.let { playback.seekTo(it.prevBlockStart(playback.shown)) }; playback.pause(); updateButtons() }
        btn("◀") { playback.step(-1); updateButtons() }
        playBtn = btn("▶", Theme.OK) { playback.toggle(); updateButtons(); if (playback.playing) startTicking() }
        btn("▶|") { playback.step(1); updateButtons() }
        btn("⏩") { index?.let { playback.seekTo(it.nextBlockStart(playback.shown)) }; playback.pause(); updateButtons() }
        btn("⏭") { playback.showAll(); updateButtons() }
        speedBtn = btn("", Theme.WARN) { playback.cycleSpeed() }
        col.addView(row)

        val row2 = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fabricBtn = c.textButton("Fabric: ${canvas.sim.fabric.label}", Theme.PURPLE) {}
        fabricBtn.setOnClickListener {
            val m = PopupMenu(c, fabricBtn)
            Fabric.values().forEachIndexed { i, f -> m.menu.add(0, i, i, f.label) }
            m.setOnMenuItemClickListener { canvas.sim.fabric = Fabric.values()[it.itemId]; fabricBtn.text = "Fabric: ${canvas.sim.fabric.label}"; canvas.invalidate(); true }
            m.show()
        }
        row2.addView(fabricBtn, LinearLayout.LayoutParams(0, c.dp(44), 1f).apply { marginEnd = c.dp(6) })
        row2.addView(Switch(c).apply {
            text = "Realistic thread"; isChecked = canvas.sim.realistic; setTextColor(Theme.TEXT)
            setOnCheckedChangeListener { _, v -> canvas.sim.realistic = v; canvas.invalidate() }
        }, LinearLayout.LayoutParams(0, -2, 1.3f))
        row2.addView(c.textButton("✕ Close", Theme.DANGER) { close() }, LinearLayout.LayoutParams(0, c.dp(44), 0.8f))
        col.addView(row2)

        popup = PopupWindow(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = false; isOutsideTouchable = false; elevation = c.dp(8).toFloat()
        }
        popup!!.showAtLocation(anchor, Gravity.BOTTOM, 0, 0)
        updateButtons(); refresh()
    }

    fun close() {
        playback.pause()
        popup?.dismiss(); popup = null
        canvas.playback = null
        onClosed?.invoke()
    }

    /** Called when the app goes to background: stop the clock but keep the popup state consistent. */
    fun onPause() { playback.pause(); if (isOpen) updateButtons() }

    private fun syncPlan() {
        val p = session.plan
        if (index?.plan !== p) { index = PlaybackIndex(p); playback.setTotal(p.stitches.size) }
    }

    private fun updateButtons() {
        if (!isOpen) return
        playBtn.text = if (playback.playing) "⏸" else "▶"
        speedBtn.text = Playback.SPEED_LABELS[playback.speedIndex]
    }

    private fun startTicking() {
        lastFrame = SystemClock.uptimeMillis()
        canvas.postOnAnimation(object : Runnable {
            override fun run() {
                if (!isOpen || !playback.playing) { updateButtons(); return }
                val now = SystemClock.uptimeMillis()
                playback.advance(now - lastFrame); lastFrame = now
                refresh()
                if (!playback.playing) updateButtons()
                canvas.postOnAnimation(this)
            }
        })
    }

    private fun refresh() {
        if (!isOpen) return
        syncPlan()
        val ix = index ?: return
        canvas.invalidate()
        if (!userSeeking) seek.progress = (playback.fraction * 1000).toInt()
        val shown = playback.shown
        val thread = ix.threadAt(shown)?.let { session.design.threads.getOrNull(it) }
        val secs = ix.estimatedSeconds(shown).toInt()
        hud.text = "Stitch ${ix.stitchesSewn(shown)} / ${ix.totalStitches}   •   Color ${ix.blockAt(shown)} / ${ix.colorBlocks}" +
            (thread?.let { " (${it.name})" } ?: "") + "   •   ~%d:%02d at 800 spm".format(secs / 60, secs % 60)
    }
}
