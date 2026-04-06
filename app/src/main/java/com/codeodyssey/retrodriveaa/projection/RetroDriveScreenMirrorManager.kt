package com.codeodyssey.retrodriveaa.projection

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.car.app.SurfaceContainer
import com.codeodyssey.retrodriveaa.RetroDriveApp
import com.codeodyssey.retrodriveaa.RetroDriveForegroundActivityTracker
import com.codeodyssey.retrodriveaa.projection.auto.RetroDriveProjectedActivity

object RetroDriveScreenMirrorManager {
    private const val TAG = "RetroDriveProjection"

    private val handler = Handler(Looper.getMainLooper())
    private val locationBuffer = IntArray(2)

    private var surfaceContainer: SurfaceContainer? = null
    private var session: HostedSession? = null
    private var landscapeMetrics: Metrics? = null
    private var portraitMetrics: Metrics? = null

    @Synchronized
    fun setSurface(container: SurfaceContainer) {
        if (surfaceContainer === container) return

        surfaceContainer = container
        landscapeMetrics = null
        portraitMetrics = null

        val currentSession = session
        val activeSession = if (currentSession != null) {
            val reboundSession = runCatching {
                currentSession.virtualDisplay.surface = container.surface
                currentSession.virtualDisplay.resize(container.width, container.height, container.dpi)
                currentSession
            }.onFailure {
                Log.w(TAG, "Failed to rebind Android Auto surface, recreating projected display", it)
                destroySession()
            }.getOrNull()

            reboundSession ?: createSession(container)
        } else {
            createSession(container)
        }

        activeSession?.let { launchProjectedHome(it.displayId) }
    }

    @Synchronized
    fun releaseSurface(container: SurfaceContainer) {
        if (surfaceContainer !== container) return

        surfaceContainer = null
        landscapeMetrics = null
        portraitMetrics = null
        destroySession()
    }

    @Synchronized
    fun hasActiveSession(): Boolean = session != null

    @Synchronized
    fun clear() {
        destroySession()
        surfaceContainer = null
        landscapeMetrics = null
        portraitMetrics = null
    }

    fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        dispatchMotion(x, y, MotionEvent.ACTION_DOWN, now)
        dispatchMotion(x, y, MotionEvent.ACTION_UP, now)
    }

    fun dispatchMotion(x: Float, y: Float, action: Int, downTime: Long) {
        val activity = projectedActivity() ?: return
        val metrics = metrics(activity) ?: return

        activity.window.decorView.getLocationOnScreen(locationBuffer)
        val translatedX = (x - metrics.offsetX) * metrics.scale - locationBuffer[0]
        val translatedY = (y - metrics.offsetY) * metrics.scale - locationBuffer[1]

        handler.post {
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                translatedX,
                translatedY,
                0
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }

            activity.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    fun performBack() {
        val activity = projectedActivity() ?: return
        handler.post {
            (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    fun goHome() {
        session?.let { launchProjectedHome(it.displayId, force = true) }
    }

    private fun launchProjectedHome(displayId: Int, force: Boolean = false) {
        if (!force && projectedActivity(displayId) != null) return

        val appContext = RetroDriveApp.instance.applicationContext
        val intent = Intent(appContext, RetroDriveProjectedActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)

        handler.post {
            runCatching {
                appContext.startActivity(intent, options.toBundle())
            }.onFailure {
                Log.e(TAG, "Failed to launch RetroDrive on Android Auto display $displayId", it)
            }
        }
    }

    private fun projectedActivity(displayId: Int? = session?.displayId): Activity? {
        val targetDisplayId = displayId ?: return null
        return RetroDriveForegroundActivityTracker.findActivity { activity ->
            activity.display?.displayId == targetDisplayId
        }
    }

    @Synchronized
    private fun createSession(container: SurfaceContainer): HostedSession? {
        val displayManager = RetroDriveApp.instance.getSystemService(DisplayManager::class.java)
        val createdSession = runCatching {
            HostedSession(
                virtualDisplay = displayManager.createVirtualDisplay(
                    "RetroDriveProjection",
                    container.width,
                    container.height,
                    container.dpi,
                    container.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                ) ?: throw IllegalStateException("createVirtualDisplay returned null")
            )
        }.onFailure {
            Log.e(TAG, "Failed to create Android Auto virtual display", it)
        }.getOrNull()

        session = createdSession
        return createdSession
    }

    private fun metrics(activity: Activity): Metrics? {
        val container = surfaceContainer ?: return null
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var metrics = if (landscape) landscapeMetrics else portraitMetrics

        if (metrics == null) {
            val size = Point()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                size.x = bounds.width()
                size.y = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay
                    .getRealSize(size)
            }

            metrics = Metrics(
                deviceWidth = size.x.toFloat(),
                deviceHeight = size.y.toFloat(),
                surfaceWidth = container.width.toFloat(),
                surfaceHeight = container.height.toFloat()
            )
            if (landscape) {
                landscapeMetrics = metrics
            } else {
                portraitMetrics = metrics
            }
        }

        return metrics
    }

    @Synchronized
    private fun destroySession() {
        session?.close()
        session = null
    }

    private class HostedSession(
        val virtualDisplay: VirtualDisplay
    ) {
        val displayId: Int = virtualDisplay.display.displayId

        fun close() {
            virtualDisplay.release()
        }
    }

    private class Metrics(
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