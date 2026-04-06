package com.codeodyssey.retrodriveaa.projection.auto

import android.app.Activity
import android.content.Context
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import com.codeodyssey.retrodriveaa.RetroDriveCarLauncher
import com.codeodyssey.retrodriveaa.RetroDriveDosLaunchHelper
import com.codeodyssey.retrodriveaa.RetroDriveForegroundActivityTracker
import org.libsdl.app.SDLActivity

object RetroDriveDosMirrorController {
    private const val TAG = "RetroDriveDosMirror"
    private const val DOSBOX_ACTIVITY_NAME = "com.dosbox.emu.DOSBoxActivity"

    private val captureThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HandlerThread("retrodrive-dos-mirror").apply { start() }
    }
    private val captureHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(captureThread.looper)
    }
    private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    fun launchDosOnPhone(context: Context, gameFolder: String?): Boolean {
        return runCatching {
            val intent = RetroDriveCarLauncher.createPhoneDosIntent(context, gameFolder)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            context.applicationContext.startActivity(intent, options.toBundle())
            Log.d(TAG, "Requested phone-side MainActivity DOS handoff for game='${gameFolder ?: ""}'")
            true
        }.onFailure {
            Log.w(TAG, "Failed to launch phone-side MainActivity DOS handoff on default display, retrying without display override", it)
            runCatching {
                context.applicationContext.startActivity(RetroDriveCarLauncher.createPhoneDosIntent(context, gameFolder))
                Log.d(TAG, "Requested phone-side MainActivity DOS handoff without display override for game='${gameFolder ?: ""}'")
                true
            }.onFailure { fallbackError ->
                Log.e(TAG, "Failed to launch phone-side MainActivity DOS handoff for game='${gameFolder ?: ""}'", fallbackError)
            }.getOrDefault(false)
        }.getOrDefault(false)
    }

    fun isDosActivityActive(): Boolean = dosActivity() != null

    fun captureFrame(targetWidth: Int, targetHeight: Int, onCaptured: (Bitmap?) -> Unit) {
        val activity = dosActivity()
        if (activity == null) {
            onCaptured(null)
            return
        }

        val rootContent = activity.window.decorView.findViewById<View>(android.R.id.content)
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val surface = runCatching { SDLActivity.getNativeSurface() }.getOrNull()
            if (surface != null && surface.isValid) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(
                    surface,
                    bitmap,
                    { result ->
                        mainHandler.post {
                            onCaptured(if (result == PixelCopy.SUCCESS) bitmap else bitmap.recycleAndNull())
                        }
                    },
                    captureHandler
                )
                return
            }

            val rootWidth = rootContent?.width ?: 0
            val rootHeight = rootContent?.height ?: 0
            if (rootWidth > 0 && rootHeight > 0) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(
                    activity.window,
                    bitmap,
                    { result ->
                        mainHandler.post {
                            onCaptured(if (result == PixelCopy.SUCCESS) bitmap else bitmap.recycleAndNull())
                        }
                    },
                    captureHandler
                )
                return
            }
        }

        if (rootContent == null || rootContent.width <= 0 || rootContent.height <= 0) {
            onCaptured(null)
            return
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scaleX = width.toFloat() / rootContent.width.toFloat()
        val scaleY = height.toFloat() / rootContent.height.toFloat()
        canvas.scale(scaleX, scaleY)
        rootContent.draw(canvas)
        onCaptured(bitmap)
    }

    fun dispatchTouchEvent(event: MotionEvent, sourceWidth: Int, sourceHeight: Int): Boolean {
        val activity = dosActivity() ?: return false
        val rootContent = activity.window.decorView.findViewById<View>(android.R.id.content) ?: return false
        val targetWidth = rootContent.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val targetHeight = rootContent.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return false
        }

        val metrics = MirrorMetrics(
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

    fun performBack(): Boolean {
        val activity = dosActivity() ?: return false
        if (activity is ComponentActivity) {
            activity.onBackPressedDispatcher.onBackPressed()
        } else {
            @Suppress("DEPRECATION")
            activity.onBackPressed()
        }
        return true
    }

    private fun dosActivity(): Activity? {
        return RetroDriveForegroundActivityTracker.findActivity { activity ->
            activity.javaClass.name == DOSBOX_ACTIVITY_NAME
        }
    }

    private fun Bitmap.recycleAndNull(): Bitmap? {
        recycle()
        return null
    }

    private class MirrorMetrics(
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