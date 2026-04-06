package com.codeodyssey.retrodriveaa.projection.auto

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import com.codeodyssey.retrodriveaa.RetroDriveDosLaunchHelper
import com.codeodyssey.retrodriveaa.RetroDriveForegroundActivityTracker

object RetroDriveAADosController {
    private const val TAG = "RetroDriveAADos"
    private const val DOSBOX_ACTIVITY_NAME = "com.dosbox.emu.DOSBoxActivity"

    fun launchDosOnDisplay(context: Context, displayId: Int, gameFolder: String?): Boolean {
        return runCatching {
            RetroDriveDosLaunchHelper.launchDosBox(
                context = context,
                gameFolder = gameFolder,
                displayId = displayId,
                useDisplayOptions = true
            )
            Log.d(TAG, "Requested DOSBox on AA display=$displayId game='${gameFolder ?: ""}'")
            true
        }.onFailure {
            Log.e(TAG, "Failed to launch DOSBox on AA display=$displayId game='${gameFolder ?: ""}'", it)
        }.getOrDefault(false)
    }

    fun isDosActivityActive(displayId: Int): Boolean = dosActivity(displayId) != null

    fun dispatchTouchEvent(
        displayId: Int,
        event: MotionEvent,
        sourceWidth: Int,
        sourceHeight: Int
    ): Boolean {
        val activity = dosActivity(displayId) ?: return false
        val rootContent = activity.window.decorView.findViewById<View>(android.R.id.content) ?: return false
        val targetWidth = rootContent.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val targetHeight = rootContent.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return false
        }

        val metrics = DisplayMetrics(
            deviceWidth = targetWidth.toFloat(),
            deviceHeight = targetHeight.toFloat(),
            surfaceWidth = sourceWidth.toFloat(),
            surfaceHeight = sourceHeight.toFloat()
        )

        val translatedEvent = MotionEvent.obtain(event)
        val translatedX = ((event.x - metrics.offsetX) * metrics.scale)
            .coerceIn(0f, (targetWidth - 1).coerceAtLeast(0).toFloat())
        val translatedY = ((event.y - metrics.offsetY) * metrics.scale)
            .coerceIn(0f, (targetHeight - 1).coerceAtLeast(0).toFloat())
        translatedEvent.setLocation(translatedX, translatedY)

        val handled = activity.dispatchTouchEvent(translatedEvent)
        translatedEvent.recycle()
        return handled
    }

    fun performBack(displayId: Int): Boolean {
        val activity = dosActivity(displayId) ?: return false
        if (activity is ComponentActivity) {
            activity.onBackPressedDispatcher.onBackPressed()
        } else {
            @Suppress("DEPRECATION")
            activity.onBackPressed()
        }
        return true
    }

    private fun dosActivity(displayId: Int): Activity? {
        return RetroDriveForegroundActivityTracker.findActivity { activity ->
            activity.javaClass.name == DOSBOX_ACTIVITY_NAME && activity.display?.displayId == displayId
        }
    }

    private class DisplayMetrics(
        deviceWidth: Float,
        deviceHeight: Float,
        surfaceWidth: Float,
        surfaceHeight: Float
    ) {
        val offsetX: Float
        val offsetY: Float
        val scale: Float

        init {
            var currentScale = deviceWidth / surfaceWidth
            val scaledHeight = deviceHeight / currentScale
            if (scaledHeight <= surfaceHeight) {
                offsetX = 0f
                offsetY = (surfaceHeight - scaledHeight) / 2f
                scale = currentScale
            } else {
                currentScale = deviceHeight / surfaceHeight
                offsetX = (surfaceWidth - deviceWidth / currentScale) / 2f
                offsetY = 0f
                scale = currentScale
            }
        }
    }
}