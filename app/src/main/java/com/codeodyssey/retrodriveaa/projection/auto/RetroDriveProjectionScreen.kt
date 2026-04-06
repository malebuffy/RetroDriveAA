package com.codeodyssey.retrodriveaa.projection.auto

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import com.codeodyssey.retrodriveaa.R
import com.codeodyssey.retrodriveaa.projection.RetroDriveScreenMirrorManager

class RetroDriveProjectionScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val handler = Handler(Looper.getMainLooper())
    private var callbackRegistered = false

    private var scrollStartX = 0f
    private var scrollStartY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var scrollDownTime = 0L
    private var scrollMoveTime = 0L
    private var scrollUpPending: Runnable? = null

    override fun onGetTemplate(): Template {
        ensureSurfaceCallback()
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.projection_action_home))
                            .setOnClickListener { RetroDriveScreenMirrorManager.goHome() }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.projection_action_back))
                            .setOnClickListener { RetroDriveScreenMirrorManager.performBack() }
                            .build()
                    )
                    .build()
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build()
            )
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (surfaceContainer.surface == null) return
        RetroDriveScreenMirrorManager.setSurface(surfaceContainer)
        scrollStartX = surfaceContainer.width / 2f
        scrollStartY = surfaceContainer.height / 2f
    }

    override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) = Unit

    override fun onStableAreaChanged(stableArea: android.graphics.Rect) = Unit

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        RetroDriveScreenMirrorManager.releaseSurface(surfaceContainer)
        cancelPendingScrollUp()
    }

    override fun onClick(x: Float, y: Float) {
        cancelPendingScrollUp()
        RetroDriveScreenMirrorManager.tap(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val now = SystemClock.uptimeMillis()
        scrollMoveTime = now

        if (scrollUpPending == null) {
            scrollX = scrollStartX
            scrollY = scrollStartY
            RetroDriveScreenMirrorManager.dispatchMotion(scrollX, scrollY, MotionEvent.ACTION_DOWN, now)
            scrollDownTime = now
            scheduleScrollUp(500L)
        }

        scrollX -= distanceX
        scrollY -= distanceY
        RetroDriveScreenMirrorManager.dispatchMotion(scrollX, scrollY, MotionEvent.ACTION_MOVE, scrollDownTime)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        scrollStartX = focusX
        scrollStartY = focusY
    }

    private fun ensureSurfaceCallback() {
        if (callbackRegistered) return

        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        callbackRegistered = true
    }

    private fun scheduleScrollUp(delayMillis: Long) {
        val runnable = Runnable {
            if (scrollUpPending == null) return@Runnable

            val now = SystemClock.uptimeMillis()
            val endTime = scrollMoveTime + delayMillis
            if (endTime > now) {
                scheduleScrollUp(endTime - now)
            } else {
                scrollUpPending = null
                RetroDriveScreenMirrorManager.dispatchMotion(
                    scrollX,
                    scrollY,
                    MotionEvent.ACTION_UP,
                    scrollDownTime
                )
            }
        }

        scrollUpPending = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun cancelPendingScrollUp() {
        scrollUpPending?.let(handler::removeCallbacks)
        scrollUpPending = null
    }
}