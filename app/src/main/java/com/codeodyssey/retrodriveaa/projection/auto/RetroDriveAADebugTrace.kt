package com.codeodyssey.retrodriveaa.projection.auto

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RetroDriveAADebugTrace {
    private const val FILE_NAME = "aa_host_trace.txt"
    private const val MAX_BYTES = 512 * 1024L
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(
        context: Context,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        level: Int = Log.DEBUG
    ) {
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        val traceFile = File(directory, FILE_NAME)
        val timestamp = synchronized(timestampFormat) {
            timestampFormat.format(Date())
        }
        val levelName = when (level) {
            Log.ERROR -> "E"
            Log.WARN -> "W"
            Log.INFO -> "I"
            else -> "D"
        }
        val throwableSummary = throwable?.let {
            " | ${it.javaClass.simpleName}: ${it.message ?: "<no message>"}"
        }.orEmpty()
        val line = "$timestamp [$levelName/$tag] $message$throwableSummary\n"

        synchronized(lock) {
            runCatching {
                traceFile.parentFile?.mkdirs()
                if (traceFile.exists() && traceFile.length() > MAX_BYTES) {
                    traceFile.writeText("$timestamp [I/$tag] trace rollover\n")
                }
                traceFile.appendText(line)
            }.onFailure {
                Log.w(tag, "Failed to append AA trace file", it)
            }
        }
    }
}