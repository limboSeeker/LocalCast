package com.localcast.app.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.FileProvider
import com.localcast.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Share log": diagnostics + this process's logcat in a text file under cache/logs, shared through
 * a FileProvider, so a tester can send it without a cable. [build] does blocking IO: call it off
 * the main thread.
 */
object LogExport {
    const val DIR = "logs"
    const val MAX_LOG_BYTES = 2 * 1024 * 1024
    const val KEEP_FILES = 3
    private const val LOGCAT_TIMEOUT_S = 10L
    private const val EXIT_REASONS = 5

    fun authority(): String = "${BuildConfig.APPLICATION_ID}.logs"

    fun fileName(now: Date): String = "localcast-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}.txt"

    fun header(diagnostics: String, version: String, now: Date): String = buildString {
        appendLine("LocalCast log")
        appendLine("version: $version")
        appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)}")
        appendLine()
        appendLine(diagnostics.trimEnd())
        appendLine()
        appendLine("----- logcat -----")
    }

    /** The last [max] bytes of [input], starting on a line boundary, and how many bytes were dropped. */
    fun readTail(input: InputStream, max: Int): Pair<ByteArray, Long> {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var dropped = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            // Compact once the buffer is twice the cap, so memory stays bounded on a huge log.
            if (buf.size() > 2 * max) {
                val all = buf.toByteArray()
                val keep = all.size - max
                dropped += keep
                buf.reset()
                buf.write(all, keep, max)
            }
        }
        // Compaction may have cut a line in half: realign on the next line break then too.
        val (tail, cut) = trimToLast(buf.toByteArray(), max, cutHappened = dropped > 0)
        return tail to dropped + cut
    }

    /** Keeps the last [max] bytes, dropping the partial first line; returns the bytes and how many were cut. */
    fun trimToLast(bytes: ByteArray, max: Int, cutHappened: Boolean = false): Pair<ByteArray, Long> {
        if (bytes.size <= max && !cutHappened) return bytes to 0L
        var start = (bytes.size - max).coerceAtLeast(0)
        val nl = (start until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() }
        if (nl != null) start = nl + 1
        return bytes.copyOfRange(start, bytes.size) to start.toLong()
    }

    /** Deletes the oldest files in [dir] so that at most [keep] remain. */
    fun pruneOld(dir: File, keep: Int) {
        val files = dir.listFiles { f -> f.isFile } ?: return
        files.sortedByDescending { it.name }.drop(keep).forEach { it.delete() }
    }

    /** Writes the log file and returns it. Logcat failures end up as a note inside the file. */
    fun build(context: Context, now: Date = Date()): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, fileName(now))
        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val diagnostics = try {
            Diagnostics.text(context)
        } catch (e: Exception) {
            "diagnostics unavailable: ${e.javaClass.simpleName}"
        }
        file.outputStream().buffered().use { out ->
            out.write(header(diagnostics, version, now).toByteArray())
            out.write(exitReasons(context).toByteArray())
            out.write(logcat())
        }
        pruneOld(dir, KEEP_FILES)
        return file
    }

    private fun logcat(): ByteArray {
        var p: java.lang.Process? = null
        // A logcat that never reaches EOF would hang the read: kill it after the timeout, the
        // read then ends and the file keeps what came so far.
        val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        return try {
            val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}")
                .redirectErrorStream(true)
                .start()
            p = proc
            watchdog.schedule({ proc.destroyForcibly() }, LOGCAT_TIMEOUT_S, TimeUnit.SECONDS)
            val (tail, dropped) = proc.inputStream.use { readTail(it, MAX_LOG_BYTES) }
            val note = when {
                tail.isEmpty() -> "[logcat returned nothing]\n"
                dropped > 0 -> "[older lines trimmed: $dropped bytes]\n"
                else -> ""
            }
            note.toByteArray() + tail
        } catch (e: Exception) {
            "[logcat failed: ${e.javaClass.simpleName}: ${e.message}]\n".toByteArray()
        } finally {
            watchdog.shutdownNow()
            p?.destroyForcibly()
        }
    }

    /**
     * Why earlier runs of the app ended (crash, ANR, killed for memory, user stop...): the log of
     * the current process does not contain lines of a process that crashed.
     */
    private fun exitReasons(context: Context): String = try {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, EXIT_REASONS)
        buildString {
            append("== Previous exits ==\n")
            if (reasons.isEmpty()) append("(none recorded)\n")
            reasons.forEach { r ->
                append(fmt.format(Date(r.timestamp))).append(" reason=").append(r.reason)
                    .append(" status=").append(r.status).append(" importance=").append(r.importance)
                    .append(" pss=").append(r.pss).append("KB")
                r.description?.let { append(" ").append(it) }
                append('\n')
            }
            append('\n')
        }
    } catch (e: Exception) {
        "== Previous exits ==\n(unavailable: ${e.javaClass.simpleName})\n\n"
    }

    /** ACTION_SEND chooser for [file], with read access granted to the receiving app. */
    fun shareIntent(context: Context, file: File, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, authority(), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
