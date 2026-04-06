package com.codeodyssey.retrodriveaa

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.Display
import java.io.File

object RetroDriveDosLaunchHelper {
    private const val TAG = "RetroDriveDosLaunch"

    data class DosSessionSpec(
        val sessionConfigPath: String,
        val gameFolder: String?,
        val saveStateGameId: String,
        val saveStatePath: String,
        val arguments: Array<String>
    )

    fun getGameFolders(context: Context): List<File> {
        val gameDir = File(context.getExternalFilesDir(null), "game")
        if (!gameDir.exists()) {
            gameDir.mkdirs()
            return emptyList()
        }

        return gameDir.listFiles { file -> file.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun createSessionSpec(context: Context, gameFolder: String? = null): DosSessionSpec {
        val profile = if (gameFolder.isNullOrBlank()) {
            GameProfile(gameId = "")
        } else {
            GameProfileStore.load(context, gameFolder)
        }
        val sessionConfigPath = ConfigManager.generateSessionConfig(context, profile)
        val saveStateGameId = gameFolder ?: "__browse__"
        val saveStatePath = SaveStateRepository.getStateFile(context, saveStateGameId).absolutePath

        return DosSessionSpec(
            sessionConfigPath = sessionConfigPath,
            gameFolder = gameFolder,
            saveStateGameId = saveStateGameId,
            saveStatePath = saveStatePath,
            arguments = buildDosArguments(context, sessionConfigPath, gameFolder)
        )
    }

    fun launchDosBox(
        context: Context,
        gameFolder: String? = null,
        displayId: Int? = null,
        useDisplayOptions: Boolean = true
    ) {
        val spec = createSessionSpec(context, gameFolder)
        val launchedFromActivity = context is Activity

        val intent = Intent().apply {
            component = ComponentName(context.packageName, "com.dosbox.emu.DOSBoxActivity")
            putExtra("SESSION_CONFIG_PATH", spec.sessionConfigPath)
            if (spec.gameFolder != null) {
                putExtra("GAME_FOLDER", spec.gameFolder)
            }
            putExtra("SAVESTATE_GAME_ID", spec.saveStateGameId)
            putExtra("SAVESTATE_PATH", spec.saveStatePath)

            if (!launchedFromActivity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val targetDisplayId = if (useDisplayOptions) {
            when {
                displayId != null -> displayId
                context is Activity -> context.display?.displayId?.takeIf { it != Display.DEFAULT_DISPLAY }
                else -> null
            }
        } else {
            null
        }

        Log.d(
            TAG,
            "Launching DOSBox game='${gameFolder ?: ""}' targetDisplay=${targetDisplayId ?: "current-task"} context=${context.javaClass.simpleName} useDisplayOptions=$useDisplayOptions"
        )

        val options = targetDisplayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it) }

        if (options != null) {
            if (!launchedFromActivity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent, options.toBundle())
            return
        }

        if (launchedFromActivity) {
            context.startActivity(intent)
        } else {
            context.applicationContext.startActivity(intent)
        }
    }

    private fun buildDosArguments(
        context: Context,
        sessionConfigPath: String,
        gameFolder: String?
    ): Array<String> {
        val gamePath = context.getExternalFilesDir(null)?.absolutePath + File.separator + "game"
        val startupDirectory = DosWorkingDirectoryResolver.resolveStartupDirectory(context, gameFolder)

        val argsList = arrayListOf(
            "-conf",
            sessionConfigPath,
            "-c",
            "mount c $gamePath",
            "-c",
            "c:",
            "-c",
            "cd \\",
            "-c",
            "cls"
        )

        if (!gameFolder.isNullOrEmpty()) {
            Log.d(TAG, "Resolved startup directory: ${startupDirectory ?: gameFolder}")
            argsList += listOf(
                "-c",
                "echo Launching $gameFolder...",
                "-c",
                buildDosChangeDirectoryCommand(startupDirectory ?: gameFolder),
                "-c",
                "echo.",
                "-c",
                "echo Current directory:",
                "-c",
                "dir"
            )
        } else {
            argsList += listOf(
                "-c",
                "echo Welcome to RetrodriveAA DOSBox!",
                "-c",
                "echo.",
                "-c",
                "echo C: drive contains your games",
                "-c",
                "echo.",
                "-c",
                "dir /w"
            )
        }

        return argsList.toTypedArray()
    }

    private fun buildDosChangeDirectoryCommand(directory: String): String {
        val normalized = directory.trim().trimStart('\\')
        return "cd \\" + normalized
    }
}