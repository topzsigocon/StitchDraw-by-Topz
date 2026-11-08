package com.topz.stitchdraw.files

/**
 * Storage abstraction so the file engine stays free of Android types.
 * The app supplies implementations backed by internal files and the Storage Access Framework
 * (content:// URIs, including external SD card volumes).
 */
interface ByteStore {
    /** Reads the whole file. Throws if missing or unreadable. */
    fun read(location: String): ByteArray
    /** Writes the file, replacing existing content. */
    fun write(location: String, data: ByteArray)
    fun exists(location: String): Boolean
    fun delete(location: String)
    /** Names (not full paths) of entries in a backup directory location. */
    fun list(dir: String): List<String>
}

/** One backup file as shown in the restore list. [valid] is false when it no longer decodes (damaged). */
data class BackupInfo(
    val file: String, val base: String, val timeMs: Long, val valid: Boolean,
    val projectName: String, val objectCount: Int, val sizeBytes: Int,
)

/**
 * Saves/opens projects safely and keeps rolling backups.
 *
 * Save = encode -> verify by decoding -> write backup of the previous file -> write. A failed
 * verification never touches the existing file.
 */
class ProjectRepository(
    private val store: ByteStore,
    private val backupDir: String,
    private val keepBackups: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun save(location: String, project: StdProject, plan: com.topz.stitchdraw.embroidery.StitchPlan? = null) {
        val now = clock()
        val stamped = project.copy(
            createdMs = if (project.createdMs == 0L) now else project.createdMs, modifiedMs = now
        )
        val bytes = StdFormat.encode(stamped, plan)
        StdFormat.decode(bytes) // verify before touching anything on disk
        if (store.exists(location)) {
            runCatching { backup(location, store.read(location)) }
        }
        store.write(location, bytes)
    }

    fun open(location: String): StdProject = StdFormat.decode(store.read(location))

    private fun baseName(location: String) = location.substringAfterLast('/').substringBeforeLast('.').ifBlank { "project" }

    private fun backup(location: String, previous: ByteArray) {
        if (!StdFormat.looksLikeStd(previous)) return
        val base = baseName(location)
        store.write("$backupDir/$base-${clock()}.std.bak", previous)
        val mine = backups(location)
        for (old in mine.dropLast(keepBackups)) store.delete("$backupDir/$old")
    }

    /** Backup file names for [location], oldest first. */
    fun backups(location: String): List<String> {
        val prefix = baseName(location) + "-"
        return store.list(backupDir).filter { it.startsWith(prefix) && it.endsWith(".std.bak") }
            .sortedBy { it.removePrefix(prefix).removeSuffix(".std.bak").toLongOrNull() ?: 0L }
    }

    /** Opens the newest backup that still decodes (crash/corruption recovery). */
    fun recover(location: String): StdProject? =
        backups(location).asReversed().firstNotNullOfOrNull { n ->
            runCatching { StdFormat.decode(store.read("$backupDir/$n")) }.getOrNull()
        }

    /**
     * Every backup in the backup folder (all projects, newest first, at most [limit]), each checked by decoding so the
     * restore list can show which ones are usable.
     */
    fun allBackups(limit: Int = 40): List<BackupInfo> =
        store.list(backupDir).filter { it.endsWith(BAK) }
            .map { n -> val stem = n.removeSuffix(BAK); Triple(n, stem.substringBeforeLast('-', stem), stem.substringAfterLast('-', "").toLongOrNull() ?: 0L) }
            .sortedByDescending { it.third }
            .take(limit)
            .map { (n, base, ts) ->
                val bytes = runCatching { store.read("$backupDir/$n") }.getOrNull()
                val p = bytes?.let { b -> runCatching { StdFormat.decode(b) }.getOrNull() }
                BackupInfo(n, base, ts, p != null, p?.name ?: base, p?.design?.objects?.size ?: 0, bytes?.size ?: 0)
            }

    /** Decodes one backup chosen from [allBackups]. Throws [StdFormatException] if it is damaged. */
    fun restore(file: String): StdProject {
        require(isBackupName(file)) { "not a backup file name" }
        return StdFormat.decode(store.read("$backupDir/$file"))
    }

    fun deleteBackup(file: String) { if (isBackupName(file)) store.delete("$backupDir/$file") }

    private fun isBackupName(f: String) = f.endsWith(BAK) && '/' !in f && '\\' !in f && ".." !in f

    private companion object { const val BAK = ".std.bak" }
}
