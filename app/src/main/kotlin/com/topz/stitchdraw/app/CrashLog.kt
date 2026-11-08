package com.topz.stitchdraw.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the stack trace of an uncaught exception to a file in app-internal storage before the process dies, so
 * the next start can offer to share it. Fully offline: nothing is sent anywhere unless the user taps Share.
 */
object CrashLog {
    private const val MAX_CHARS = 60_000

    private fun file(ctx: Context) = File(ctx.filesDir, "crash/last-crash.txt")

    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    fun header(ctx: Context): String =
        "StitchDraw by Topz ${versionName(ctx)}\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Screen ${ctx.resources.configuration.screenWidthDp} x ${ctx.resources.configuration.screenHeightDp} dp\n"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val f = file(app)
                f.parentFile?.mkdirs()
                f.writeText(header(app) + "Time $stamp, thread ${t.name}\n\n" + sw.toString().take(MAX_CHARS))
            } catch (ignored: Throwable) { /* never mask the real crash */ }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = try { file(ctx).takeIf { it.exists() }?.readText() } catch (e: Exception) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (e: Exception) { /* ignore */ } }

    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "StitchDraw by Topz log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, "Share log"))
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("StitchDraw log", text))
    }
}
