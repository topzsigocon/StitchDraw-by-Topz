package com.topz.stitchdraw.vectorart

import android.content.ContentResolver
import android.graphics.Typeface
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Built-in font families plus .ttf / .otf files the user imports (copied into the app's private storage, fully offline). */
object Fonts {
    val BUILTIN = listOf("sans", "serif", "mono", "cursive", "casual")
    private var dir: File? = null
    private val cache = HashMap<String, Typeface>()

    fun init(filesDir: File) { dir = File(filesDir, "fonts").also { it.mkdirs() } }

    /** Names offered in the text dialog. */
    fun all(): List<String> = BUILTIN + (dir?.listFiles()?.filter { it.isFile && (it.extension.equals("ttf", true) || it.extension.equals("otf", true)) }?.map { it.name }?.sorted() ?: emptyList())

    private fun base(name: String): Typeface = cache.getOrPut(name) {
        when (name) {
            "", "sans" -> Typeface.SANS_SERIF
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
            "casual" -> Typeface.create("casual", Typeface.NORMAL)
            else -> try { val f = File(dir, name); if (f.exists()) Typeface.createFromFile(f) else Typeface.SANS_SERIF } catch (e: Exception) { Typeface.SANS_SERIF }
        }
    }

    fun typeface(name: String, bold: Boolean, italic: Boolean): Typeface {
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        return Typeface.create(base(name), style)
    }

    fun isCustom(name: String) = name.isNotEmpty() && name !in BUILTIN

    fun cssFamily(name: String): String = when (name) {
        "", "sans" -> "sans-serif"; "serif" -> "serif"; "mono" -> "monospace"; "cursive" -> "cursive"; "casual" -> "fantasy"
        else -> "'" + name.substringBeforeLast('.').replace("'", "") + "', sans-serif"
    }

    fun fontData(name: String): String? { val f = File(dir, name); return if (f.exists()) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null }
    fun mime(name: String) = if (name.endsWith(".otf", true)) "font/otf" else "font/ttf"

    private fun safeName(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
        return leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "font.ttf" }
    }

    private fun uniqueFile(rawName: String): Pair<String, File> {
        val d = dir ?: throw IllegalStateException("Font storage is not initialized")
        val name = safeName(rawName)
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "ttf")
        var candidate = name
        var index = 2
        while (File(d, candidate).exists()) { candidate = "${stem}_$index.$ext"; index++ }
        return candidate to File(d, candidate)
    }

    /** Copy and validate a picked TTF/OTF file into app-private storage. */
    fun import(cr: ContentResolver, uri: Uri, displayName: String?): String? {
        val raw = displayName ?: "font.ttf"
        if (!raw.substringAfterLast('.', "").equals("ttf", true) && !raw.substringAfterLast('.', "").equals("otf", true)) return null
        val (name, file) = try { uniqueFile(raw) } catch (e: Exception) { return null }
        return try {
            cr.openInputStream(uri)!!.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 20L * 1024 * 1024) throw IllegalStateException("Font is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (file.length() < 100L) throw IllegalStateException("Invalid font size")
            Typeface.createFromFile(file)
            cache.remove(name)
            name
        } catch (e: Exception) { file.delete(); null }
    }

    /** Import valid TTF/OTF files from a ZIP without extracting arbitrary paths. Limits protect against ZIP bombs. */
    fun importZip(cr: ContentResolver, uri: Uri): Pair<List<String>, Int> {
        val imported = mutableListOf<String>()
        var skipped = 0
        var totalBytes = 0L
        var entries = 0
        val d = dir ?: return imported to 0
        try {
            cr.openInputStream(uri)?.use { source ->
                ZipInputStream(source).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        if (entries > 500) throw IllegalStateException("ZIP contains too many entries")
                        val raw = entry.name
                        val ext = raw.substringAfterLast('.', "").lowercase()
                        if (entry.isDirectory || (ext != "ttf" && ext != "otf")) {
                            if (!entry.isDirectory) skipped++
                            zip.closeEntry()
                            continue
                        }
                        val nameOnly = raw.substringAfterLast('/').substringAfterLast('\\')
                        if (nameOnly.isBlank()) { skipped++; zip.closeEntry(); continue }
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var fontBytes = 0
                        while (true) {
                            val count = zip.read(chunk)
                            if (count < 0) break
                            fontBytes += count
                            totalBytes += count
                            if (fontBytes > 20 * 1024 * 1024 || totalBytes > 100L * 1024 * 1024) throw IllegalStateException("ZIP font data exceeds safety limit")
                            buffer.write(chunk, 0, count)
                        }
                        try {
                            if (fontBytes < 100) throw IllegalStateException("Empty font")
                            val (storedName, file) = uniqueFile(nameOnly)
                            file.writeBytes(buffer.toByteArray())
                            try { Typeface.createFromFile(file) } catch (e: Exception) { file.delete(); throw e }
                            cache.remove(storedName)
                            imported.add(storedName)
                        } catch (e: Exception) { skipped++ }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("Cannot read ZIP")
        } catch (e: Exception) {
            // Keep fonts already imported from a partially readable archive.
            if (imported.isEmpty()) return emptyList<String>() to maxOf(1, skipped)
        }
        return imported to skipped
    }
}
