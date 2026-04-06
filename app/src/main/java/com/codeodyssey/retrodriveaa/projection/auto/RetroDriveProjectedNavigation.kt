package com.codeodyssey.retrodriveaa.projection.auto

import android.content.Context
import android.content.Intent
import com.codeodyssey.retrodriveaa.RetroDriveCarLauncher

object RetroDriveProjectedNavigation {
    const val EXTRA_PROJECTED_MODE = "com.codeodyssey.retrodriveaa.extra.PROJECTED_MODE"
    const val EXTRA_OPEN_SECTION = "com.codeodyssey.retrodriveaa.extra.OPEN_SECTION"
    const val EXTRA_AUTO_LAUNCH_DOS = "com.codeodyssey.retrodriveaa.extra.AUTO_LAUNCH_DOS"
    const val EXTRA_AUTO_LAUNCH_GAME_ID = "com.codeodyssey.retrodriveaa.extra.AUTO_LAUNCH_GAME_ID"
    const val EXTRA_FINISH_AFTER_DOS_LAUNCH = "com.codeodyssey.retrodriveaa.extra.FINISH_AFTER_DOS_LAUNCH"

    const val SECTION_HOME = "home"
    const val SECTION_TRANSFER = "transfer"
    const val SECTION_SETTINGS = "settings"
    const val SECTION_ABOUT = "about"

    private const val PREFS_NAME = "retrodriveaa_projected_launch"
    private const val KEY_PENDING_PROJECTED_MODE = "pending_projected_mode"
    private const val KEY_PENDING_SECTION = "pending_section"
    private const val KEY_PENDING_AUTO_LAUNCH_DOS = "pending_auto_launch_dos"
    private const val KEY_PENDING_AUTO_LAUNCH_GAME_ID = "pending_auto_launch_game_id"
    private const val KEY_PENDING_FINISH_AFTER_DOS_LAUNCH = "pending_finish_after_dos_launch"
    private const val KEY_PENDING_GAME_CONFIG_ID = "pending_game_config_id"

    data class PendingLaunch(
        val projectedMode: Boolean,
        val section: String?,
        val autoLaunchDos: Boolean,
        val gameId: String?,
        val finishAfterDosLaunch: Boolean,
        val gameConfigId: String?
    )

    fun createCarLaunchIntent(
        context: Context,
        section: String? = null,
        autoLaunchDos: Boolean = false,
        gameId: String? = null,
        finishAfterDosLaunch: Boolean = false
    ): Intent {
        return createProjectedActivityIntent(
            context = context,
            section = section,
            autoLaunchDos = autoLaunchDos,
            gameId = gameId,
            finishAfterDosLaunch = finishAfterDosLaunch
        )
    }

    fun createProjectedActivityIntent(
        context: Context,
        section: String? = null,
        autoLaunchDos: Boolean = false,
        gameId: String? = null,
        finishAfterDosLaunch: Boolean = false
    ): Intent {
        return Intent(context, RetroDriveProjectedActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_PROJECTED_MODE, true)
            section?.let { putExtra(EXTRA_OPEN_SECTION, it) }
            putExtra(EXTRA_AUTO_LAUNCH_DOS, autoLaunchDos)
            gameId?.let { putExtra(EXTRA_AUTO_LAUNCH_GAME_ID, it) }
            putExtra(EXTRA_FINISH_AFTER_DOS_LAUNCH, finishAfterDosLaunch)
        }
    }

    fun createHostProjectedActivityIntent(
        context: Context,
        section: String? = null,
        autoLaunchDos: Boolean = false,
        gameId: String? = null,
        finishAfterDosLaunch: Boolean = false
    ): Intent {
        return createProjectedActivityIntent(
            context = context,
            section = section,
            autoLaunchDos = autoLaunchDos,
            gameId = gameId,
            finishAfterDosLaunch = finishAfterDosLaunch
        )
    }

    fun createHostCarLauncherIntent(
        context: Context,
        section: String? = null,
        autoLaunchDos: Boolean = false,
        gameId: String? = null,
        finishAfterDosLaunch: Boolean = false
    ): Intent {
        return Intent(context, RetroDriveCarLauncher::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_PROJECTED_MODE, true)
            section?.let { putExtra(EXTRA_OPEN_SECTION, it) }
            putExtra(EXTRA_AUTO_LAUNCH_DOS, autoLaunchDos)
            gameId?.let { putExtra(EXTRA_AUTO_LAUNCH_GAME_ID, it) }
            putExtra(EXTRA_FINISH_AFTER_DOS_LAUNCH, finishAfterDosLaunch)
        }
    }

    fun createBareCarLauncherIntent(context: Context): Intent {
        return Intent(context, RetroDriveCarLauncher::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    fun storePendingLaunch(
        context: Context,
        section: String? = null,
        autoLaunchDos: Boolean = false,
        gameId: String? = null,
        finishAfterDosLaunch: Boolean = false,
        gameConfigId: String? = null
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING_PROJECTED_MODE, true)
            .putString(KEY_PENDING_SECTION, section)
            .putBoolean(KEY_PENDING_AUTO_LAUNCH_DOS, autoLaunchDos)
            .putString(KEY_PENDING_AUTO_LAUNCH_GAME_ID, gameId)
            .putBoolean(KEY_PENDING_FINISH_AFTER_DOS_LAUNCH, finishAfterDosLaunch)
            .putString(KEY_PENDING_GAME_CONFIG_ID, gameConfigId)
            .commit()
    }

    fun clearPendingLaunch(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_PROJECTED_MODE)
            .remove(KEY_PENDING_SECTION)
            .remove(KEY_PENDING_AUTO_LAUNCH_DOS)
            .remove(KEY_PENDING_AUTO_LAUNCH_GAME_ID)
            .remove(KEY_PENDING_FINISH_AFTER_DOS_LAUNCH)
                .remove(KEY_PENDING_GAME_CONFIG_ID)
            .apply()
    }

    fun consumePendingLaunch(context: Context): PendingLaunch? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val projectedMode = prefs.getBoolean(KEY_PENDING_PROJECTED_MODE, false)
        val section = prefs.getString(KEY_PENDING_SECTION, null)
        val autoLaunchDos = prefs.getBoolean(KEY_PENDING_AUTO_LAUNCH_DOS, false)
        val gameId = prefs.getString(KEY_PENDING_AUTO_LAUNCH_GAME_ID, null)
        val finishAfterDosLaunch = prefs.getBoolean(KEY_PENDING_FINISH_AFTER_DOS_LAUNCH, false)
        val gameConfigId = prefs.getString(KEY_PENDING_GAME_CONFIG_ID, null)
        if (!projectedMode && section == null && !autoLaunchDos && gameId == null && !finishAfterDosLaunch && gameConfigId == null) {
            return null
        }

        clearPendingLaunch(context)
        return PendingLaunch(
            projectedMode = projectedMode,
            section = section,
            autoLaunchDos = autoLaunchDos,
            gameId = gameId,
            finishAfterDosLaunch = finishAfterDosLaunch,
            gameConfigId = gameConfigId
        )
    }
}