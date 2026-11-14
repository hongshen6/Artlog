package com.artlog.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal in-app crash reporter (round 2, crash-fix work).
 *
 * Why this exists: the round-1 APK crashed instantly on the user's phone
 * (Android 16, Dimensity 8350) with no stack trace available to us — no
 * device, no emulator and no logcat here. Instead of guessing again, the
 * next APK records every uncaught exception to app-private storage so the
 * real stack trace can be read back in Settings.
 *
 * Installed as the very first thing in [ArtlogApplication.onCreate] (and
 * again at the top of MainActivity.onCreate, before super.onCreate, so
 * crashes during activity instantiation are also caught). It chains to
 * the previous handler, so the system's normal crash behaviour is kept.
 * The handler never throws: any failure while writing the report is
 * swallowed so reporting can never mask or replace the original crash.
 *
 * Storage: filesDir/Artlog/crash/crash-<yyyyMMdd-HHmmss>.log, newest 5
 * kept. Fully local: nothing is uploaded anywhere.
 *
 * Reachability fix (crash-fix round, 2026-11-12): the internal copy lives
 * in app-private storage, which the user cannot see in a file manager —
 * and the in-app viewer sits in Settings, which is unreachable when the
 * app crashes on launch. So every report is ALSO copied to public
 * storage via MediaStore at Documents/Artlog/crash-<timestamp>.log, where
 * the phone's Files app can open and share it without opening Artlog.
 * No storage permission is needed for the app's own files there on
 * Android 10+. The public copy is best-effort: if it fails, the internal
 * copy is still written.
 */
object CrashReporter {

    private const val DIR_NAME = "Artlog/crash"
    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".log"
    private const val MAX_FILES = 5

    fun install(context: Context) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(context.applicationContext, current)
        )
    }

    private class CrashHandler(
        private val appContext: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Reporting must never interfere with the original crash.
            }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // No previous handler (or it threw): the runtime kills the
                // process when this method returns, as usual.
            }
        }
    }

    fun writeReport(context: Context, thread: Thread, throwable: Throwable): File? {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        val report = buildString {
            appendLine("Artlog crash report")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("app: ${appVersion(context)}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("thread: ${thread.name}")
            appendLine()
            appendStackTrace(this, throwable)
        }
        file.outputStream().use { out ->
            out.write(report.toByteArray(Charsets.UTF_8))
            out.flush()
            try {
                out.fd.sync()
            } catch (_: Throwable) {
            }
        }
        pruneOldReports(dir)
        // Public copy, reachable without opening the app (see class doc).
        writePublicCopy(context, file.name, report)
        return file
    }

    /**
     * Writes the same report to Documents/Artlog/ via MediaStore so the
     * user can reach it with the phone's Files app even when Artlog
     * itself won't open. Best-effort only: any failure is swallowed, the
     * internal copy above is the primary record.
     */
    private fun writePublicCopy(context: Context, fileName: String, report: String) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Artlog")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Files.getContentUri("external"), values
            ) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(report.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: Throwable) {
            // Public copy is a convenience; never let it affect the crash.
        }
    }

    private fun appendStackTrace(out: StringBuilder, throwable: Throwable) {
        var current: Throwable? = throwable
        var first = true
        while (current != null) {
            if (first) {
                out.append(current.javaClass.name)
                out.append(": ")
                out.appendLine(current.message ?: "(no message)")
                first = false
            } else {
                out.append("Caused by: ")
                out.append(current.javaClass.name)
                out.append(": ")
                out.appendLine(current.message ?: "(no message)")
            }
            for (element in current.stackTrace) {
                out.append("\tat ").appendLine(element.toString())
            }
            current = current.cause
        }
    }

    private fun appVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        } catch (_: Throwable) {
            "(unknown)"
        }
    }

    /** Newest crash log, or null when no crash has been recorded yet. */
    fun latestLog(context: Context): File? {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.isDirectory) return null
        return dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.maxByOrNull { it.lastModified() }
    }

    fun latestLogText(context: Context): String? {
        return try {
            latestLog(context)?.readText(Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    private fun pruneOldReports(dir: File) {
        try {
            val files = dir.listFiles { f ->
                f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
            }?.sortedByDescending { it.lastModified() } ?: return
            files.drop(MAX_FILES).forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }
}
