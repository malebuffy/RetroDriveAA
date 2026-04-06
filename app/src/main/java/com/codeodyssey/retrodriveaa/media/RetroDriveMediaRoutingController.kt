package com.codeodyssey.retrodriveaa.media

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.codeodyssey.retrodriveaa.R

object RetroDriveMediaRoutingController {
    fun start(context: Context, gameFolder: String?) {
        sendCommand(context, RetroDriveMediaRoutingService.ACTION_START, gameFolder)
    }

    fun resume(context: Context, gameFolder: String?) {
        sendCommand(context, RetroDriveMediaRoutingService.ACTION_RESUME, gameFolder)
    }

    fun pause(context: Context) {
        sendCommand(context, RetroDriveMediaRoutingService.ACTION_PAUSE, null)
    }

    fun stop(context: Context) {
        sendCommand(context, RetroDriveMediaRoutingService.ACTION_PAUSE, null)
    }

    fun shutdown(context: Context) {
        sendCommand(context, RetroDriveMediaRoutingService.ACTION_STOP, null)
    }

    private fun sendCommand(context: Context, action: String, gameFolder: String?) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, RetroDriveMediaRoutingService::class.java).apply {
            this.action = action
            putExtra(RetroDriveMediaRoutingService.EXTRA_SESSION_TITLE, buildSessionTitle(appContext, gameFolder))
        }

        if (action == RetroDriveMediaRoutingService.ACTION_STOP) {
            appContext.startService(intent)
        } else {
            ContextCompat.startForegroundService(appContext, intent)
        }
    }

    private fun buildSessionTitle(context: Context, gameFolder: String?): String =
        context.getString(R.string.media_session_root_title)
}