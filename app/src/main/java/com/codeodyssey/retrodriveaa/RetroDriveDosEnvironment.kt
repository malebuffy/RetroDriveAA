package com.codeodyssey.retrodriveaa

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.dosbox.emu.DOSBoxJNI
import com.dosbox.emu.input.NativeBridge
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object RetroDriveDosEnvironment {
    private const val TAG = "RetroDriveDosEnv"

    fun prepareRuntime(context: Context) {
        copyAssetAll(context, "dosbox.conf")
        copyAssetAll(context, "dosbox_base.conf")
        ensureGameDirectoryExists(context)
    }

    fun initializeSaveState(gameId: String, statePath: String) {
        DOSBoxJNI.nativeSetSaveStateContext(gameId, statePath)
    }

    fun initializeScreenDimensions(width: Int, height: Int) {
        NativeBridge.initScreenDimensions(width, height)
    }

    private fun copyAssetAll(context: Context, srcPath: String) {
        val assetMgr = context.assets
        try {
            val destPath = context.getExternalFilesDir(null)?.absolutePath + File.separator + srcPath
            val assets = assetMgr.list(srcPath) ?: return
            if (assets.isEmpty()) {
                copyFile(assetMgr, srcPath, destPath)
            } else {
                val dir = File(destPath)
                if (!dir.exists()) {
                    dir.mkdir()
                }
                assets.forEach { element ->
                    copyAssetAll(context, srcPath + File.separator + element)
                }
            }
        } catch (error: IOException) {
            Log.e(TAG, "Failed to copy asset path $srcPath", error)
        }
    }

    private fun copyFile(assetMgr: AssetManager, srcFile: String, destFile: String) {
        try {
            assetMgr.open(srcFile).use { input ->
                val outputFile = File(destFile)
                if (!outputFile.exists() || shouldOverwriteAsset(srcFile)) {
                    FileOutputStream(outputFile).use { output ->
                        copyStream(input, output)
                    }
                    Log.v(TAG, "Copied asset to $destFile")
                }
            }
        } catch (error: IOException) {
            Log.e(TAG, "Failed to copy asset file $srcFile", error)
        }
    }

    private fun shouldOverwriteAsset(srcFile: String): Boolean {
        return srcFile == "dosbox.conf" || srcFile == "dosbox_base.conf"
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) return
            output.write(buffer, 0, read)
        }
    }

    private fun ensureGameDirectoryExists(context: Context) {
        try {
            val gameDir = File(context.getExternalFilesDir(null), "game")
            if (!gameDir.exists()) {
                val created = gameDir.mkdirs()
                Log.d(TAG, "Game directory created=$created path=${gameDir.absolutePath}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to ensure game directory exists", error)
        }
    }
}