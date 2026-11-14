package com.topz.stitchdraw.app

import android.app.Activity
import android.app.AlertDialog
import com.topz.stitchdraw.digitizing.DigitizingSession
import java.text.DateFormat
import java.util.Date

/** Lists saved backups (all projects, newest first) and restores the one the user picks. */
class RestoreDialog(
    private val activity: Activity,
    private val session: DigitizingSession,
    private val io: ProjectIO,
    private val toast: (String) -> Unit,
    private val getVectorArt: () -> String?,
    private val setVectorArt: (String?) -> Unit,
) {

    fun show() {
        val all = io.backups()
        val usable = all.filter { it.valid }
        if (usable.isEmpty()) {
            toast(if (all.isEmpty()) "No backups yet. They are made automatically every time you save." else "All backups are damaged and cannot be opened.")
            return
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val labels = usable.map { "${it.projectName}\n${fmt.format(Date(it.timeMs))}  ·  ${it.objectCount} objects  ·  ${it.sizeBytes / 1024 + 1} KB" }.toTypedArray()
        AlertDialog.Builder(activity).setTitle("Restore from backup")
            .setItems(labels) { _, i -> confirm(usable[i].file, usable[i].projectName) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(file: String, name: String) {
        AlertDialog.Builder(activity).setTitle("Restore \"$name\"?")
            .setMessage("This replaces the design on screen. Your current work is saved as a backup first, so you can come back to it from this same list.\n\nThe restored design opens as a new unsaved project: use Save As to choose where it goes.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete this backup") { _, _ -> io.deleteBackup(file); toast("Backup deleted") }
            .setPositiveButton("Restore") { _, _ ->
                io.autosave(io.makeProject(session.design, getVectorArt()))                 // keep what is on screen recoverable
                val p = io.restoreBackup(file)
                if (p == null) toast("That backup could not be opened")
                else { session.load(p.design); setVectorArt(p.vectorArtJson); toast("Restored. Use File > Save As to keep it.") }
            }.show()
    }
}
