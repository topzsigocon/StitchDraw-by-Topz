package com.topz.stitchdraw.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.topz.stitchdraw.files.ByteStore
import com.topz.stitchdraw.files.ProjectRepository
import com.topz.stitchdraw.files.StdProject
import java.io.File

/**
 * [ByteStore] over both plain internal paths and Storage Access Framework content:// URIs
 * (which includes external SD cards and any document provider). Fully offline.
 */
class AndroidByteStore(private val ctx: Context) : ByteStore {
    private fun isUri(l: String) = l.startsWith("content://")

    override fun read(location: String): ByteArray =
        if (isUri(location)) ctx.contentResolver.openInputStream(Uri.parse(location))?.use { it.readBytes() }
            ?: throw java.io.IOException("Cannot open $location")
        else File(location).readBytes()

    override fun write(location: String, data: ByteArray) {
        if (isUri(location)) {
            // "wt" truncates; a failed write cannot happen after verification, and backups exist for the previous file.
            val out = ctx.contentResolver.openOutputStream(Uri.parse(location), "wt")
                ?: throw java.io.IOException("Cannot write $location")
            out.use { it.write(data) }
        } else {
            val f = File(location); f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(f)) { f.writeBytes(data); tmp.delete() }
        }
    }

    override fun exists(location: String): Boolean =
        if (isUri(location)) runCatching { ctx.contentResolver.openInputStream(Uri.parse(location))?.use { true } ?: false }.getOrDefault(false)
        else File(location).exists()

    override fun delete(location: String) { if (!isUri(location)) File(location).delete() }
    override fun list(dir: String): List<String> = File(dir).list()?.toList() ?: emptyList()
}

/** Owns the "current project" and the Open / Save / Save As / Recover flows. */
class ProjectIO(private val activity: Activity) {
    companion object {
        const val REQ_OPEN = 7001
        const val REQ_SAVE_AS = 7002
        const val REQ_EXPORT = 7003
        const val MIME = "application/octet-stream"
    }

    private val store = AndroidByteStore(activity)
    private val backupDir = File(activity.filesDir, "backups").absolutePath
    private val repo = ProjectRepository(store, backupDir)
    private val autosave = File(activity.filesDir, "projects/autosave.std").absolutePath

    var location: String? = null; private set
    var name: String = "Untitled"; private set

    fun newProject(name: String = "Untitled") { location = null; this.name = name }

    fun launchOpen() {
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_OPEN)
    }

    fun launchSaveAs() {
        activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = MIME; putExtra(Intent.EXTRA_TITLE, "$name.std")
        }, REQ_SAVE_AS)
    }

    /** Returns the opened project for REQ_OPEN, or null after a successful REQ_SAVE_AS. Throws on failure. */
    fun onResult(req: Int, uri: Uri, current: StdProject): StdProject? {
        val loc = uri.toString()
        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return when (req) {
            REQ_OPEN -> repo.open(loc).also { location = loc; name = it.name }
            REQ_SAVE_AS -> { repo.save(loc, current); location = loc; null }
            else -> null
        }
    }

    /** Saves to the current location; returns false when there is none yet (caller should Save As). */
    fun save(current: StdProject): Boolean {
        val loc = location ?: return false
        repo.save(loc, current); return true
    }

    /** Crash safety: always keep an internal copy of the work in progress. */
    fun autosave(current: StdProject) { runCatching { repo.save(autosave, current) } }
    fun hasAutosave() = store.exists(autosave)

    /** Backups of every project (newest first) for the restore list. */
    fun backups(): List<com.topz.stitchdraw.files.BackupInfo> = runCatching { repo.allBackups() }.getOrDefault(emptyList())

    /**
     * Loads a backup as the current project. It is opened as an UNSAVED project (no location), so a plain Save can never
     * overwrite the good file by accident; the user chooses where to save it.
     */
    fun restoreBackup(file: String): StdProject? = runCatching { repo.restore(file) }.getOrNull()?.also { newProject(it.name + " (restored)") }

    fun deleteBackup(file: String) { runCatching { repo.deleteBackup(file) } }
    fun openAutosave(): StdProject? = runCatching { repo.open(autosave) }.getOrNull() ?: repo.recover(autosave)

    private var pendingExport: com.topz.stitchdraw.export.MachineEncoder? = null

    /** Asks the user where to put the machine file; the actual write happens in [finishExport]. */
    fun launchExport(encoder: com.topz.stitchdraw.export.MachineEncoder) {
        pendingExport = encoder
        activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = MIME; putExtra(Intent.EXTRA_TITLE, "$name.${encoder.extension}")
        }, REQ_EXPORT)
    }

    /** Encodes, re-reads the bytes with the format's own validator, then writes. Returns a summary line. */
    fun finishExport(uri: Uri, plan: com.topz.stitchdraw.embroidery.StitchPlan): String {
        val enc = pendingExport ?: error("No export pending")
        pendingExport = null
        val bytes = enc.encode(plan, name)
        when (enc) {   // never write a file our own reader rejects
            com.topz.stitchdraw.export.DstEncoder -> com.topz.stitchdraw.export.DstEncoder.decode(bytes)
            com.topz.stitchdraw.export.ExpEncoder -> com.topz.stitchdraw.export.ExpEncoder.decode(bytes)
            com.topz.stitchdraw.export.JefEncoder -> com.topz.stitchdraw.export.JefEncoder.decode(bytes)
            else -> {}
        }
        store.write(uri.toString(), bytes)
        return "${enc.formatName}: ${bytes.size} bytes, ${plan.stats.stitchCount} stitches, ${plan.stats.colorChanges + 1} colours"
    }

    fun makeProject(design: com.topz.stitchdraw.embroidery.EmbroideryDesign, vectorArtJson: String? = null) =
        StdProject(name, design, vectorArtJson = vectorArtJson)
}
