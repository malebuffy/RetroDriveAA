package com.codeodyssey.retrodriveaa.projection.auto

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import com.codeodyssey.retrodriveaa.DeviceUploadActivity
import com.codeodyssey.retrodriveaa.RetroDriveCarLauncher
import com.codeodyssey.retrodriveaa.RetroDriveDosLaunchHelper
import com.codeodyssey.retrodriveaa.media.RetroDriveMediaRoutingController
import com.google.android.apps.auto.sdk.CarActivity

class RetroDriveLegacyCarActivity : CarActivity(), SurfaceHolder.Callback,
    RetroDriveAAHomePresentation.Listener {

    private var virtualDisplay: VirtualDisplay? = null
    private var homePresentation: RetroDriveAAHomePresentation? = null
    private var forwardingTouchToPresentation = false
    private var homeSurfaceView: SurfaceView? = null
    private var dosHostContainer: FrameLayout? = null
    private var dosSession: RetroDriveAANativeDosSession? = null
    private var pendingDosGameFolder: String? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDensityDpi = 0
    private var displayMode = DisplayMode.HOME
    private var homeProjectionRestoreRequested = false
    private var libraryChangedReceiverContext: Context? = null
    private val libraryChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DeviceUploadActivity.ACTION_LIBRARY_CHANGED) {
                refreshHomePresentation()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        traceDebug("onCreate")

        setIgnoreConfigChanges(0xFFFF)
        runCatching {
            carUiController.statusBarController.hideAppHeader()
            carUiController.menuController.hideMenuButton()
        }

        val surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@RetroDriveLegacyCarActivity)
            setOnTouchListener { _, event ->
                forwardTouchToHomePresentation(event, source = "surface")
            }
        }
        homeSurfaceView = surfaceView

        val wrapper = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (displayMode == DisplayMode.DOS_NATIVE) {
                    return super.dispatchTouchEvent(event)
                }

                if (forwardTouchToHomePresentation(event, source = "wrapper")) {
                    return true
                }

                return super.dispatchTouchEvent(event)
            }
        }

        wrapper.addView(
            surfaceView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val dosContainer = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val session = dosSession
                if (displayMode == DisplayMode.DOS_NATIVE && session != null) {
                    if (session.shouldRouteTouchToOverlay(event)) {
                        return super.dispatchTouchEvent(event)
                    }

                    return session.dispatchTouchEvent(event)
                }

                return super.dispatchTouchEvent(event)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        dosHostContainer = dosContainer
        wrapper.addView(dosContainer)

        setContentView(wrapper)

        registerLibraryChangedReceiver()
    }

    private fun registerLibraryChangedReceiver() {
        val receiverContext = applicationContext
        val filter = IntentFilter(DeviceUploadActivity.ACTION_LIBRARY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            receiverContext.registerReceiver(
                libraryChangedReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            receiverContext.registerReceiver(libraryChangedReceiver, filter)
        }
        libraryChangedReceiverContext = receiverContext
    }

    override fun onPause() {
        Log.d(TAG, "onPause mode=${displayMode.logName}")
        traceDebug("onPause mode=${displayMode.logName}")
        if (displayMode == DisplayMode.DOS_NATIVE) {
            dosSession?.pause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume mode=${displayMode.logName} hasHome=${hasActiveHomeProjection()}")
        traceDebug("onResume mode=${displayMode.logName} hasHome=${hasActiveHomeProjection()}")

        if (displayMode == DisplayMode.DOS_NATIVE) {
            if (dosSession == null) {
                showDosNative()
            } else {
                dosSession?.resume()
            }
            return
        }

        if (!hasActiveHomeProjection()) {
            requestHomeProjectionRestore("onResume")
        } else {
            refreshHomePresentation()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy mode=${displayMode.logName}")
        traceDebug("onDestroy mode=${displayMode.logName}")
        runCatching {
            libraryChangedReceiverContext?.unregisterReceiver(libraryChangedReceiver)
            libraryChangedReceiverContext = null
        }
        stopDosNativeSession(shutdownMediaSession = true)
        releaseProjection()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        updateSurfaceMetrics(holder)
        if (displayMode == DisplayMode.DOS_NATIVE) {
            return
        }
        Log.d(TAG, "surfaceCreated ${surfaceWidth}x${surfaceHeight} dpi=$surfaceDensityDpi mode=${displayMode.logName}")
        traceDebug("surfaceCreated ${surfaceWidth}x${surfaceHeight} dpi=$surfaceDensityDpi mode=${displayMode.logName}")
        requestHomeProjectionRestore("surfaceCreated")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        updateSurfaceMetrics(holder, width, height)

        if (displayMode == DisplayMode.DOS_NATIVE) {
            return
        }

        val activeDisplay = virtualDisplay
        if (activeDisplay == null) {
            requestHomeProjectionRestore("surfaceChanged-noDisplay")
            return
        }

        runCatching {
            activeDisplay.surface = holder.surface
            activeDisplay.resize(surfaceWidth, surfaceHeight, surfaceDensityDpi)
        }.onFailure {
            Log.w(TAG, "Failed to resize ${displayMode.logName} virtual display, recreating", it)
            traceWarn("Failed to resize ${displayMode.logName} virtual display, recreating", it)
            requestHomeProjectionRestore("surfaceChanged-resizeFailure")
            return
        }

        if (displayMode == DisplayMode.HOME) {
            refreshHomePresentation()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        traceDebug("surfaceDestroyed")
        surfaceHolder = null
        surfaceWidth = 0
        surfaceHeight = 0
        surfaceDensityDpi = 0
        if (displayMode == DisplayMode.HOME) {
            releaseProjection()
        }
    }

    override fun onLaunchDos(gameFolder: String?) {
        Log.d(TAG, "Launching DOS game='${gameFolder ?: ""}' in AA host")
        traceDebug("onLaunchDos game='${gameFolder ?: ""}'")
        pendingDosGameFolder = gameFolder
        showDosNative()
    }

    private fun forwardTouchToHomePresentation(event: MotionEvent, source: String): Boolean {
        if (displayMode == DisplayMode.DOS_NATIVE) {
            val handled = dosSession?.dispatchTouchEvent(event) == true
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                Log.d(TAG, "Touch source=$source handled=$handled mode=${displayMode.logName}")
            }
            return handled
        }

        if (forwardingTouchToPresentation) {
            return false
        }

        val presentation = homePresentation
        if (presentation == null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                Log.d(TAG, "Touch source=$source ignored because homePresentation is null mode=${displayMode.logName}")
                requestHomeProjectionRestore("touch-noPresentation")
            }
            return false
        }

        forwardingTouchToPresentation = true
        val handled = runCatching {
            presentation.injectTouchEvent(event)
        }.onFailure {
            Log.e(TAG, "Failed to forward AA touch to home presentation from $source", it)
        }.getOrDefault(false)
        forwardingTouchToPresentation = false

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            Log.d(TAG, "Touch source=$source handled=$handled mode=${displayMode.logName}")
        }

        return handled
    }

    override fun onUploadFromDevice() {
        RetroDriveProjectedNavigation.storePendingLaunch(
            context = this,
            section = RetroDriveProjectedNavigation.SECTION_TRANSFER
        )
        val intent = RetroDriveCarLauncher.createPhoneTransferIntent(this)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)

        runCatching {
            startActivity(intent, options.toBundle())
        }.onFailure {
            Log.w(TAG, "Failed to launch phone-side transfer flow on default display, retrying without display override", it)
            runCatching {
                startActivity(intent)
            }.onFailure { activityFallbackError ->
                Log.w(TAG, "Failed to launch phone-side transfer flow from activity context, retrying with application context", activityFallbackError)
                runCatching {
                    applicationContext.startActivity(intent, options.toBundle())
                }.onFailure { fallbackError ->
                    RetroDriveProjectedNavigation.clearPendingLaunch(this)
                    Log.e(TAG, "Failed to launch phone-side transfer flow", fallbackError)
                }
            }
        }
    }

    override fun onConfigureGame(gameFolder: String) {
        RetroDriveProjectedNavigation.storePendingLaunch(
            context = this,
            gameConfigId = gameFolder
        )
        val intent = RetroDriveCarLauncher.createPhoneGameConfigIntent(this, gameFolder)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)

        runCatching {
            startActivity(intent, options.toBundle())
        }.onFailure {
            Log.w(TAG, "Failed to launch phone-side config for $gameFolder on default display, retrying without display override", it)
            runCatching {
                startActivity(intent)
            }.onFailure { activityFallbackError ->
                Log.w(TAG, "Failed to launch phone-side config for $gameFolder from activity context, retrying with application context", activityFallbackError)
                runCatching {
                    applicationContext.startActivity(intent, options.toBundle())
                }.onFailure { fallbackError ->
                    RetroDriveProjectedNavigation.clearPendingLaunch(this)
                    Log.e(TAG, "Failed to launch phone-side config for $gameFolder", fallbackError)
                }
            }
        }
    }

    private fun showHome() {
        val display = virtualDisplay?.display ?: return
        Log.d(TAG, "showHome on display ${display.displayId}")
        traceDebug("showHome display=${display.displayId}")

        homeSurfaceView?.visibility = View.VISIBLE
        dosHostContainer?.visibility = View.GONE
        homePresentation?.dismiss()
        val presentation = RetroDriveAAHomePresentation(this, display)
        presentation.listener = this
        presentation.show()
        presentation.updateGameFolders(RetroDriveDosLaunchHelper.getGameFolders(this).map { it.name })
        homePresentation = presentation
    }

    private fun showDosNative() {
        val container = dosHostContainer ?: return
        val launchGameFolder = pendingDosGameFolder
        val existingSession = dosSession
        traceDebug("showDosNative game='${launchGameFolder ?: ""}' existingSession=${existingSession != null} hasHome=${hasActiveHomeProjection()}")
        if (existingSession != null && !existingSession.hasActiveSession()) {
            Log.w(TAG, "Discarding stale AA-native DOS session wrapper for game='${launchGameFolder ?: ""}'")
            traceWarn("Discarding stale AA-native DOS session wrapper for game='${launchGameFolder ?: ""}'")
            stopDosNativeSession(clearPendingGame = false)
        } else if (existingSession != null && existingSession.matchesGameFolder(launchGameFolder)) {
            displayMode = DisplayMode.DOS_NATIVE
            Log.d(TAG, "Resuming parked AA-native DOS session for game='${launchGameFolder ?: ""}'")
            traceDebug("Resuming parked AA-native DOS session for game='${launchGameFolder ?: ""}'")
            container.visibility = View.VISIBLE
            container.bringToFront()
            existingSession.resume()
            return
        }

        stopDosNativeSession(clearPendingGame = false)

        displayMode = DisplayMode.DOS_NATIVE
        Log.d(TAG, "showDosNative hasHome=${hasActiveHomeProjection()} game='${launchGameFolder ?: ""}'")
        traceDebug("Preparing new AA-native DOS session game='${launchGameFolder ?: ""}'")
        homeSurfaceView?.visibility = View.GONE
        releaseProjection()
        container.visibility = View.VISIBLE
        container.bringToFront()

        val session = RetroDriveAANativeDosSession(this, container) { destroySession, finishHost ->
            showHomeFromDos(destroySession, finishHost)
        }
        dosSession = session

        Log.d(TAG, "Starting AA-native DOS session for game='${launchGameFolder ?: ""}'")
        traceDebug("Starting AA-native DOS session for game='${launchGameFolder ?: ""}'")
        val started = session.start(launchGameFolder)
        if (!started) {
            Log.e(TAG, "Failed to start AA-native DOS session for game='${launchGameFolder ?: ""}'")
            traceError("Failed to start AA-native DOS session for game='${launchGameFolder ?: ""}'")
            showHomeFromDos(destroySession = true, finishHost = true)
        }
    }

    private fun showHomeFromDos(destroySession: Boolean, finishHost: Boolean = false) {
        Log.d(TAG, "showHomeFromDos destroySession=$destroySession finishHost=$finishHost hasHome=${hasActiveHomeProjection()} holderReady=${surfaceHolder?.surface?.isValid == true}")
        traceDebug(
            "showHomeFromDos destroySession=$destroySession finishHost=$finishHost hasHome=${hasActiveHomeProjection()} holderReady=${surfaceHolder?.surface?.isValid == true}"
        )
        displayMode = DisplayMode.HOME
        if (destroySession) {
            stopDosNativeSession(shutdownMediaSession = finishHost)
            homeSurfaceView?.visibility = if (finishHost) View.GONE else View.VISIBLE
            dosHostContainer?.visibility = View.GONE
            if (finishHost) {
                releaseProjection()
                finishCarActivityToHome()
                return
            }
        } else {
            pendingDosGameFolder = null
        }
        homeSurfaceView?.visibility = View.VISIBLE
        dosHostContainer?.visibility = View.GONE

        if (hasActiveHomeProjection()) {
            refreshHomePresentation()
        } else {
            traceDebug("showHomeFromDos waiting for surfaceCreated/onResume to restore home projection")
        }
    }

    private fun refreshHomePresentation() {
        if (displayMode != DisplayMode.HOME) return

        if (!hasActiveHomeProjection()) {
            Log.d(TAG, "refreshHomePresentation detected stale home projection; recreating")
            traceWarn("refreshHomePresentation detected stale home projection; recreating")
            requestHomeProjectionRestore("refreshHomePresentation-stale")
            return
        }

        Log.d(TAG, "refreshHomePresentation updating existing home presentation")
        traceDebug("refreshHomePresentation updating existing home presentation")
        homePresentation?.updateGameFolders(
            RetroDriveDosLaunchHelper.getGameFolders(this).map { it.name }
        )
    }

    private fun hasActiveHomeProjection(): Boolean {
        val display = virtualDisplay?.display ?: return false
        val presentation = homePresentation ?: return false
        return presentation.isShowing && presentation.display.displayId == display.displayId
    }

    private fun requestHomeProjectionRestore(reason: String) {
        if (displayMode != DisplayMode.HOME) {
            return
        }

        homeProjectionRestoreRequested = true
        restoreHomeProjectionIfReady(reason)
    }

    private fun restoreHomeProjectionIfReady(reason: String) {
        if (displayMode != DisplayMode.HOME || !homeProjectionRestoreRequested) {
            return
        }

        if (virtualDisplay != null || homePresentation != null) {
            traceDebug(
                "restoreHomeProjectionIfReady reason=$reason existingProjection=true hasDisplay=${virtualDisplay != null} hasPresentation=${homePresentation != null}"
            )
            homeProjectionRestoreRequested = false
            if (hasActiveHomeProjection()) {
                refreshHomePresentation()
            }
            return
        }

        if (hasActiveHomeProjection()) {
            traceDebug("restoreHomeProjectionIfReady reason=$reason active=true")
            homeProjectionRestoreRequested = false
            refreshHomePresentation()
            return
        }

        val holder = surfaceHolder
        if (holder == null || !holder.surface.isValid) {
            traceDebug("restoreHomeProjectionIfReady reason=$reason waitingForSurface=true")
            return
        }

        traceDebug("restoreHomeProjectionIfReady reason=$reason recreating")
        homeProjectionRestoreRequested = false
        recreateVirtualDisplay(DisplayMode.HOME)
    }

    private fun recreateVirtualDisplay(mode: DisplayMode): Int? {
        val holder = surfaceHolder
        if (holder == null || !holder.surface.isValid) {
            Log.w(TAG, "Cannot create ${mode.logName} virtual display before AA surface is ready")
            traceWarn("Cannot create ${mode.logName} virtual display before AA surface is ready")
            displayMode = mode
            return null
        }

        val width = surfaceWidth.takeIf { it > 0 } ?: holder.surfaceFrame.width()
        val height = surfaceHeight.takeIf { it > 0 } ?: holder.surfaceFrame.height()
        val dpi = surfaceDensityDpi.takeIf { it > 0 } ?: resources.displayMetrics.densityDpi

        releaseProjection()
        displayMode = mode
        if (mode == DisplayMode.HOME) {
            homeProjectionRestoreRequested = false
        }

        Log.d(TAG, "Creating ${mode.logName} virtual display ${width}x${height} dpi=$dpi")
        traceDebug("Creating ${mode.logName} virtual display ${width}x${height} dpi=$dpi")
        virtualDisplay = runCatching {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).createVirtualDisplay(
                "RetroDriveLegacyAA",
                width,
                height,
                dpi,
                holder.surface,
                mode.flags
            )
        }.onFailure {
            Log.e(TAG, "Failed to create ${mode.logName} virtual display", it)
            traceError("Failed to create ${mode.logName} virtual display", it)
        }.getOrNull()

        val displayId = virtualDisplay?.display?.displayId
        if (displayId != null) {
            Log.d(TAG, "Created ${mode.logName} virtual display $displayId")
            traceDebug("Created ${mode.logName} virtual display $displayId")
            when (mode) {
                DisplayMode.HOME -> showHome()
                DisplayMode.DOS_NATIVE -> showDosNative()
            }
        }

        return displayId
    }

    private fun updateSurfaceMetrics(holder: SurfaceHolder, width: Int? = null, height: Int? = null) {
        surfaceHolder = holder
        surfaceWidth = width ?: holder.surfaceFrame.width()
        surfaceHeight = height ?: holder.surfaceFrame.height()
        surfaceDensityDpi = resources.displayMetrics.densityDpi
    }

    private fun releaseProjection() {
        Log.d(TAG, "releaseProjection")
        traceDebug("releaseProjection hasPresentation=${homePresentation != null} hasDisplay=${virtualDisplay != null}")
        homePresentation?.dismiss()
        homePresentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun stopDosNativeSession(clearPendingGame: Boolean = true, shutdownMediaSession: Boolean = false) {
        traceDebug(
            "stopDosNativeSession clearPendingGame=$clearPendingGame shutdownMediaSession=$shutdownMediaSession hasSession=${dosSession != null}"
        )
        dosSession?.stopImmediately()
        dosSession = null
        if (shutdownMediaSession) {
            RetroDriveMediaRoutingController.shutdown(this)
        }
        if (clearPendingGame) {
            pendingDosGameFolder = null
        }
    }

    fun resolveHostWindow(): Window? {
        val hostWindow = runCatching {
            var cls: Class<*>? = javaClass
            while (cls != null) {
                val method = cls.declaredMethods.firstOrNull {
                    it.name == "c" && it.parameterCount == 0 && Window::class.java.isAssignableFrom(it.returnType)
                }
                if (method != null) {
                    method.isAccessible = true
                    return@runCatching method.invoke(this) as? Window
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()

        if (hostWindow == null) {
            Log.w(TAG, "Failed to resolve CarActivity host window")
            traceWarn("Failed to resolve CarActivity host window")
        } else {
            traceDebug("Resolved CarActivity host window class=${hostWindow.javaClass.name}")
        }
        return hostWindow
    }

    private fun finishCarActivityToHome() {
        val host = runCatching {
            var cls: Class<*>? = javaClass
            while (cls != null) {
                val field = cls.declaredFields.firstOrNull {
                    it.name == "a" && it.type.name == "com.google.android.gms.car.CarActivityHost"
                }
                if (field != null) {
                    field.isAccessible = true
                    return@runCatching field.get(this)
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()

        if (host == null) {
            Log.w(TAG, "CarActivity host object was not available for finish()")
            traceWarn("CarActivity host object was not available for finish()")
            return
        }

        val candidateMethods = listOf("finish", "isFinishing", "getWindow")
        Log.d(TAG, "CarActivity host class=${host.javaClass.name}")
        traceDebug("CarActivity host class=${host.javaClass.name}")
        for (methodName in candidateMethods) {
            val method = host.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            } ?: continue
            runCatching {
                val result = method.invoke(host)
                Log.d(TAG, "CarActivity host $methodName() -> $result")
                traceDebug("CarActivity host $methodName() -> $result")
                if (methodName == "finish") {
                    return
                }
            }.onFailure {
                Log.w(TAG, "Failed invoking $methodName() on CarActivity host", it)
                traceWarn("Failed invoking $methodName() on CarActivity host", it)
            }
        }

        Log.w(TAG, "No finish() method resolved on CarActivity host; AA home return will rely on projection teardown only")
        traceWarn("No finish() method resolved on CarActivity host; AA home return will rely on projection teardown only")
    }

    private fun traceDebug(message: String) {
        RetroDriveAADebugTrace.log(this, TAG, message)
    }

    private fun traceWarn(message: String, throwable: Throwable? = null) {
        RetroDriveAADebugTrace.log(this, TAG, message, throwable, Log.WARN)
    }

    private fun traceError(message: String, throwable: Throwable? = null) {
        RetroDriveAADebugTrace.log(this, TAG, message, throwable, Log.ERROR)
    }

    private enum class DisplayMode(val flags: Int, val logName: String) {
        HOME(
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            "home"
        ),
        DOS_NATIVE(
            0,
            "dos-native"
        )
    }

    private companion object {
        private const val TAG = "RetroDriveLegacyAA"
    }
}