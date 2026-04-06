package com.codeodyssey.retrodriveaa

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipInputStream

object GameImportManager {
    private const val TAG = "GameImportManager"

    data class ImportResult(
        val success: Boolean,
        val message: String
    )

    fun importUploadedStream(context: Context, filename: String, inputStream: InputStream): ImportResult {
        val gamesDirectory = File(context.getExternalFilesDir(null), "game")
        if (!gamesDirectory.exists() && !gamesDirectory.mkdirs()) {
            return ImportResult(false, "Failed to create games directory")
        }

        val safeFilename = sanitizeFilename(filename)
        var tempFile: File? = null

        return try {
            tempFile = File(gamesDirectory, "$safeFilename.uploading")
            var bytesCopied = 0L
            FileOutputStream(tempFile).use { fos ->
                bytesCopied = inputStream.copyTo(fos)
                fos.flush()
            }

            if (bytesCopied <= 0L) {
                return ImportResult(false, "Uploaded file was empty")
            }

            val finalFile = File(gamesDirectory, safeFilename)
            if (finalFile.exists() && !finalFile.delete()) {
                return ImportResult(false, "Failed to replace existing file")
            }
            if (!tempFile.renameTo(finalFile)) {
                return ImportResult(false, "Failed to finalize uploaded file")
            }

            if (safeFilename.endsWith(".zip", ignoreCase = true)) {
                val gameName = safeFilename.substringBeforeLast(".zip")
                val gameDir = File(gamesDirectory, gameName)
                val extractingDir = File(gamesDirectory, ".${gameName}.extracting")

                if (extractingDir.exists()) {
                    extractingDir.deleteRecursively()
                }
                if (!extractingDir.mkdirs()) {
                    return ImportResult(false, "Failed to prepare extraction folder")
                }

                try {
                    val (extractedFiles, extractedBytes) = extractZip(finalFile, extractingDir)
                    if (extractedFiles <= 0 || extractedBytes <= 0L) {
                        return ImportResult(false, "ZIP extraction produced no usable files")
                    }

                    if (gameDir.exists() && !gameDir.deleteRecursively()) {
                        return ImportResult(false, "Failed to replace existing game folder")
                    }

                    if (!extractingDir.renameTo(gameDir)) {
                        return ImportResult(false, "Failed to finalize extracted game files")
                    }
                } finally {
                    if (extractingDir.exists()) {
                        extractingDir.deleteRecursively()
                    }
                    finalFile.delete()
                }
            }

            ImportResult(true, "File imported successfully: $safeFilename")
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            ImportResult(false, "Import failed: ${e.message}")
        } finally {
            try {
                if (tempFile?.exists() == true) {
                    tempFile.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun extractZip(zipFile: File, targetDir: File): Pair<Int, Long> {
        var extractedFiles = 0
        var extractedBytes = 0L
        val targetRoot = targetDir.canonicalFile

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                val canonicalFile = file.canonicalFile
                if (!canonicalFile.path.startsWith(targetRoot.path + File.separator)) {
                    throw IOException("Invalid ZIP entry path")
                }

                if (entry.isDirectory) {
                    canonicalFile.mkdirs()
                } else {
                    canonicalFile.parentFile?.mkdirs()
                    FileOutputStream(canonicalFile).use { fos ->
                        val written = zis.copyTo(fos)
                        extractedBytes += written
                    }
                    extractedFiles += 1
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        return Pair(extractedFiles, extractedBytes)
    }

    private fun sanitizeFilename(input: String): String {
        val base = input.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
