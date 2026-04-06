package com.codeodyssey.retrodriveaa.projection.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.codeodyssey.retrodriveaa.BuildConfig
import com.codeodyssey.retrodriveaa.R
import com.codeodyssey.retrodriveaa.RetroDriveDosEnvironment
import com.codeodyssey.retrodriveaa.RetroDriveDosLaunchHelper
import com.codeodyssey.retrodriveaa.SaveStateRepository
import com.codeodyssey.retrodriveaa.media.RetroDriveMediaRoutingController
import com.dosbox.emu.DOSBoxJNI
import com.dosbox.emu.WifiControllerServer
import com.dosbox.emu.VirtualButtonsView
import com.dosbox.emu.VirtualDPadView
import com.dosbox.emu.input.InputDirector
import com.dosbox.emu.input.NativeBridge
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLEmbeddedSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.sqrt

class RetroDriveAANativeDosSession(
    private val activity: RetroDriveLegacyCarActivity,
    private val hostContainer: FrameLayout,
    private val onReturnHome: (destroySession: Boolean, finishHost: Boolean) -> Unit
) : WifiControllerServer.ControllerEventListener,
    VirtualDPadView.OnDPadEventListener,
    VirtualButtonsView.OnButtonEventListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sdlContainer: RelativeLayout? = null
    private var sdlSession: SDLEmbeddedSession? = null
    private var inputDirector = InputDirector(InputDirector.InputMode.TOUCHPAD_EMULATION)
    private var wifiServer: WifiControllerServer? = null
    private var isWifiServerRunning = false
    private var exiting = false
    private var lastMouseEventTime = 0L
    private var lastJoystickMouseEventTime = 0L
    private var currentGameFolder: String? = null
    private var currentSaveStateGameId: String = "__browse__"
    private var currentSaveStatePath: String = ""
    private var virtualDPad: VirtualDPadView? = null
    private var virtualButtons: VirtualButtonsView? = null
    private var floatingToolStack: LinearLayout? = null
    private var exitButton: FrameLayout? = null
    private var keyboardShown = false
    private var overlayGestureActive = false
    private var keyboardInputContainer: FrameLayout? = null
    private var keyboardPanelTitleView: TextView? = null
    private var keyboardRowsContainer: LinearLayout? = null
    private var keyboardShiftEnabled = false
    private var keyboardSymbolsEnabled = false
    private var keyboardMode = KeyboardOverlayMode.TEXT_ENTRY
    private var remoteControllerOverlay: FrameLayout? = null
    private var activeControllerUrl: String? = null
    private var settingsOverlay: FrameLayout? = null
    private var saveStateOverlay: FrameLayout? = null
    private var saveStateProgressOverlay: FrameLayout? = null
    private var settingsStatusText: TextView? = null
    private val settingsValueViews = linkedMapOf<String, TextView>()
    private var pendingKeyCapture: KeyBindingSpec? = null
    private var saveStateInProgress = false
    private var sessionStartToken = 0

    fun start(gameFolder: String?): Boolean {
        traceDebug("start game='${gameFolder ?: ""}' currentToken=$sessionStartToken")
        stopImmediately()
        exiting = false

        val sessionSpec = RetroDriveDosLaunchHelper.createSessionSpec(activity, gameFolder)
        val startToken = ++sessionStartToken
        currentGameFolder = gameFolder
        currentSaveStateGameId = sessionSpec.saveStateGameId
        currentSaveStatePath = sessionSpec.saveStatePath
        RetroDriveDosEnvironment.prepareRuntime(activity)

        val container = RelativeLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, _, event -> dispatchKeyEvent(event) }
            setOnGenericMotionListener { _, event -> dispatchGenericMotionEvent(event) }
        }

        hostContainer.removeAllViews()
        hostContainer.addView(container)
        sdlContainer = container
        inputDirector = InputDirector(InputDirector.InputMode.TOUCHPAD_EMULATION)
        hostContainer.bringToFront()
        container.bringToFront()
        container.requestFocus()
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                updateOverlayLayout()
            }
        }
        container.post {
            if (sessionStartToken != startToken || sdlContainer !== container) {
                Log.d(TAG, "Skipping stale embedded start token=$startToken")
                traceWarn("Skipping stale embedded start token=$startToken")
                return@post
            }

            RetroDriveMediaRoutingController.start(activity, currentGameFolder)

            val hostWindow = activity.resolveHostWindow()
            traceDebug(
                "starting embedded token=$startToken hostWindow=${hostWindow != null} container=${System.identityHashCode(container)} args=${sessionSpec.arguments.size}"
            )

            val embeddedSession = SDLEmbeddedSession.start(
                activity,
                hostWindow,
                container,
                sessionSpec.arguments
            ) {
                finishToHome(destroySession = true, finishHost = true)
            }

            if (sessionStartToken != startToken || sdlContainer !== container) {
                Log.d(TAG, "Destroying stale embedded session token=$startToken after start")
                traceWarn("Destroying stale embedded session token=$startToken after start")
                embeddedSession?.destroy()
                RetroDriveMediaRoutingController.stop(activity)
                return@post
            }

            if (embeddedSession == null) {
                Log.e(TAG, "Embedded SDL session failed to start for token=$startToken")
                traceError("Embedded SDL session failed to start for token=$startToken")
                RetroDriveMediaRoutingController.shutdown(activity)
                finishToHome(destroySession = true, finishHost = true)
                return@post
            }

            sdlSession = embeddedSession
            traceDebug("Embedded SDL session started token=$startToken")
            RetroDriveDosEnvironment.initializeSaveState(
                sessionSpec.saveStateGameId,
                sessionSpec.saveStatePath
            )
            RetroDriveDosEnvironment.initializeScreenDimensions(
                width = container.width.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.widthPixels,
                height = container.height.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.heightPixels
            )
            addVirtualDPad()
            addVirtualActionButtons()
            addFloatingToolStack()
            addExitButton()
            updateOverlayLayout()
        }
        return true
    }

    fun resume() {
        traceDebug("resume hasSession=${sdlSession != null}")
        RetroDriveMediaRoutingController.resume(activity, currentGameFolder)
        sdlSession?.resume()
    }

    fun pause() {
        traceDebug("pause hasSession=${sdlSession != null}")
        sdlSession?.pause()
        RetroDriveMediaRoutingController.pause(activity)
    }

    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return inputDirector.processTouchEvent(event)
    }

    fun shouldRouteTouchToOverlay(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                overlayGestureActive = isTouchOnInteractiveOverlay(event)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val routeToOverlay = overlayGestureActive
                overlayGestureActive = false
                return routeToOverlay
            }
        }

        return overlayGestureActive
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handlePendingKeyCapture(event)) {
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when {
                settingsOverlay != null -> {
                    hideSettingsOverlay()
                }

                saveStateOverlay != null -> {
                    hideSaveStateOverlay()
                }

                saveStateProgressOverlay != null -> {
                }

                remoteControllerOverlay != null -> {
                    hideRemoteControllerOverlay()
                }

                else -> {
                    exitToHome()
                }
            }
            return true
        }

        if (settingsOverlay != null || remoteControllerOverlay != null) {
            return false
        }

        if (inputDirector.processKeyEvent(event)) {
            return true
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                NativeBridge.sendKey(event.keyCode, true)
                true
            }

            KeyEvent.ACTION_UP -> {
                NativeBridge.sendKey(event.keyCode, false)
                true
            }

            else -> false
        }
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return inputDirector.processGenericEvent(event)
    }

    fun exitToHome() {
        if (exiting) return

        Log.d(TAG, "Exit FAB requested return to AA home and destroy DOS session")
        traceDebug("exitToHome requested")
        stopWifiServer()
        hideRemoteControllerOverlay()
        hideSettingsOverlay()
        hideKeyboardInput()
        finishToHome(destroySession = true, finishHost = false)
    }

    fun matchesGameFolder(gameFolder: String?): Boolean {
        return currentGameFolder == gameFolder
    }

    fun hasActiveSession(): Boolean {
        return sdlSession != null
    }

    fun stopImmediately(resetExitState: Boolean = true) {
        traceDebug("stopImmediately resetExitState=$resetExitState hasSession=${sdlSession != null} tokenBefore=$sessionStartToken")
        sessionStartToken++
        RetroDriveMediaRoutingController.stop(activity)
        hideKeyboardInput()
        stopWifiServer()
        sdlSession?.destroy()
        sdlSession = null
        virtualDPad = null
        virtualButtons = null
        floatingToolStack = null
        exitButton = null
        sdlContainer = null
        hostContainer.removeAllViews()
        if (resetExitState) {
            exiting = false
        }
        keyboardShown = false
        overlayGestureActive = false
        keyboardPanelTitleView = null
        keyboardRowsContainer = null
        keyboardShiftEnabled = false
        keyboardSymbolsEnabled = false
        keyboardMode = KeyboardOverlayMode.TEXT_ENTRY
        keyboardInputContainer = null
        remoteControllerOverlay = null
        activeControllerUrl = null
        settingsOverlay = null
        saveStateOverlay = null
        saveStateProgressOverlay = null
        settingsStatusText = null
        settingsValueViews.clear()
        pendingKeyCapture = null
        saveStateInProgress = false
    }

    override fun onControllerKeyEvent(keyCode: Int, pressed: Boolean) {
        mainHandler.post {
            NativeBridge.sendKey(keyCode, pressed)
        }
    }

    override fun onControllerMouseMove(dx: Int, dy: Int) {
        val now = System.currentTimeMillis()
        if (lastMouseEventTime > 0) {
            val timeSinceLastEvent = now - lastMouseEventTime
            if (timeSinceLastEvent > 100) {
                Log.d(TAG, "Lag spike detected (${timeSinceLastEvent}ms), dropping remote mouse event")
                lastMouseEventTime = now
                return
            }
        }
        lastMouseEventTime = now

        if (abs(dx) < 3 && abs(dy) < 3) {
            return
        }

        mainHandler.post {
            runCatching {
                SDLActivity.onNativeMouseRelative(dx.toFloat(), dy.toFloat())
            }.onFailure {
                Log.e(TAG, "Failed to send remote mouse movement", it)
            }
        }
    }

    override fun onControllerMouseButton(button: Int, pressed: Boolean) {
        val sdlButton = (button - 1).coerceAtLeast(0)
        mainHandler.post {
            NativeBridge.sendMouseButton(sdlButton, pressed)
        }
    }

    override fun onTrackpadEnd() {
        lastMouseEventTime = 0L
    }

    override fun onControllerJoystick(x: Float, y: Float, timestamp: Long) {
        val now = System.currentTimeMillis()
        if (lastJoystickMouseEventTime == 0L) {
            lastJoystickMouseEventTime = now
        }
        var dt = now - lastJoystickMouseEventTime
        lastJoystickMouseEventTime = now
        if (dt < 1L) dt = 1L
        if (dt > 50L) dt = 50L

        val magnitude = sqrt(x * x + y * y)
        if (magnitude < 0.12f) {
            return
        }

        val timeScale = dt / 16.0f
        val dx = x * 11.0f * timeScale
        val dy = y * 11.0f * timeScale
        mainHandler.post {
            runCatching {
                SDLActivity.onNativeMouseRelative(dx, dy)
            }.onFailure {
                Log.e(TAG, "Failed to send remote joystick movement", it)
            }
        }
    }

    override fun onControllerTextLine(text: String) {
        if (text.isBlank()) return

        sendTextAsKeyEvents(text, appendEnter = true)
    }

    override fun onDPadPress(direction: Int, keyCode: Int) {
        dispatchSyntheticKey(keyCode, true)
    }

    override fun onDPadRelease(direction: Int, keyCode: Int) {
        dispatchSyntheticKey(keyCode, false)
    }

    override fun onButtonPress(button: Int, keyCode: Int) {
        dispatchSyntheticKey(keyCode, true)
    }

    override fun onButtonRelease(button: Int, keyCode: Int) {
        dispatchSyntheticKey(keyCode, false)
    }

    private fun finishToHome(destroySession: Boolean, finishHost: Boolean) {
        if (exiting) {
            traceWarn("finishToHome ignored because exiting=true destroySession=$destroySession finishHost=$finishHost")
            return
        }

        Log.d(TAG, "Finishing embedded DOS session back to AA home destroySession=$destroySession finishHost=$finishHost")
        traceDebug("finishToHome destroySession=$destroySession finishHost=$finishHost")
        exiting = true
        if (destroySession) {
            stopImmediately(resetExitState = false)
        }
        onReturnHome(destroySession, finishHost)
    }

    private fun traceDebug(message: String) {
        RetroDriveAADebugTrace.log(activity, TAG, message)
    }

    private fun traceWarn(message: String, throwable: Throwable? = null) {
        RetroDriveAADebugTrace.log(activity, TAG, message, throwable, Log.WARN)
    }

    private fun traceError(message: String, throwable: Throwable? = null) {
        RetroDriveAADebugTrace.log(activity, TAG, message, throwable, Log.ERROR)
    }

    private fun startWifiServer() {
        if (isWifiServerRunning) {
            activeControllerUrl?.let { showRemoteControllerOverlay(it) }
            return
        }

        if (!hasExternalInternetConnection()) {
            Toast.makeText(activity, "Internet is required for phone controller.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Skipping phone remote controller because internet is not available")
            return
        }

        try {
            wifiServer = WifiControllerServer(this, BuildConfig.CONTROLLER_WS_BASE_URL)
            isWifiServerRunning = true
            val url = wifiServer?.buildHostedControllerUrl(BuildConfig.CONTROLLER_WEB_BASE_URL).orEmpty()
            activeControllerUrl = url
            Log.d(TAG, "Phone remote controller started at $url")
            updateWifiButtonState()
            showRemoteControllerOverlay(url)
        } catch (error: IOException) {
            Log.e(TAG, "Failed to start phone remote controller", error)
            Toast.makeText(activity, "Failed to start phone controller", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopWifiServer() {
        if (!isWifiServerRunning) {
            return
        }

        runCatching {
            wifiServer?.stop()
        }.onFailure {
            Log.w(TAG, "Failed to stop phone remote controller cleanly", it)
        }
        hideRemoteControllerOverlay()
        wifiServer = null
        isWifiServerRunning = false
        activeControllerUrl = null
        updateWifiButtonState()
    }

    private fun hasExternalInternetConnection(): Boolean {
        val connectivityManager = activity.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        val hasRequiredTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return hasRequiredTransport &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun addVirtualDPad() {
        val rootView = sdlContainer ?: return
        if (virtualDPad != null) return

        val dPad = VirtualDPadView(activity).apply {
            setGameFolder(currentGameFolder)
            setOnDPadEventListener(this@RetroDriveAANativeDosSession)
            visibility = View.GONE
        }

        virtualDPad = dPad
        rootView.addView(dPad)
    }

    private fun addVirtualActionButtons() {
        val rootView = sdlContainer ?: return
        if (virtualButtons != null) return

        val buttons = VirtualButtonsView(activity).apply {
            setGameFolder(currentGameFolder)
            setOnButtonEventListener(this@RetroDriveAANativeDosSession)
            visibility = View.GONE
        }

        virtualButtons = buttons
        rootView.addView(buttons)
    }

    private fun addFloatingToolStack() {
        val rootView = sdlContainer ?: return
        if (floatingToolStack != null) return

        val spec = calculateToolStackLayoutSpec()

        val stack = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val saveButton = createFabWithText(0xCC607D8B.toInt(), spec.fabSizePx, "💾").apply {
            setOnClickListener { showSaveLoadMenu() }
        }
        stack.addView(saveButton)
        addFabSpacer(stack, spec.fabSpacingPx)

        val settingsButton = createFabWithText(0xCCFF9800.toInt(), spec.fabSizePx, "⚙").apply {
            setOnClickListener { showSettingsOverlay() }
        }
        stack.addView(settingsButton)
        addFabSpacer(stack, spec.fabSpacingPx)

        val dpadButton = createFabWithText(0xCC4CAF50.toInt(), spec.fabSizePx, "🎮").apply {
            setOnClickListener { toggleVirtualDPad() }
        }
        stack.addView(dpadButton)
        addFabSpacer(stack, spec.fabSpacingPx)

        val keyboardButton = createFabWithText(0xCC2196F3.toInt(), spec.fabSizePx, "⌨").apply {
            setOnClickListener { toggleOnScreenKeyboard() }
        }
        stack.addView(keyboardButton)
        addFabSpacer(stack, spec.fabSpacingPx)

        val remoteButton = createFabWithDrawable(0xCC9C27B0.toInt(), spec.fabSizePx, R.drawable.ic_phone).apply {
            setOnClickListener { toggleWifiController() }
        }
        stack.addView(remoteButton)

        floatingToolStack = stack
        rootView.addView(stack)
        updateWifiButtonState()
    }

    private fun addExitButton() {
        val rootView = sdlContainer ?: return
        if (exitButton != null) return

        val spec = calculateExitButtonLayoutSpec()
        val button = createFabWithText(0xCCF44336.toInt(), spec.sizePx, "✕").apply {
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { exitToHome() }
        }

        exitButton = button
        rootView.addView(button)
    }

    private fun createFabWithDrawable(color: Int, size: Int, drawableResId: Int): FrameLayout {
        val fabContainer = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            alpha = 0.8f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> alpha = 1.0f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 0.8f
                }
                false
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val fab = ImageButton(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(3, Color.WHITE)
            }
            setImageResource(drawableResId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Color.WHITE)
            val padding = (size * 0.25f).toInt()
            setPadding(padding, padding, padding, padding)
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }

        fabContainer.addView(fab)
        return fabContainer
    }

    private fun createFabWithText(color: Int, size: Int, icon: String): FrameLayout {
        val fabContainer = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            alpha = 0.8f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> alpha = 1.0f
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> alpha = 0.8f
                }
                false
            }
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val fab = ImageButton(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(3, Color.WHITE)
            }
            isClickable = false
            isFocusable = false
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        fabContainer.addView(fab)

        fabContainer.addView(TextView(activity).apply {
            text = icon
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                20f * (size / (56f * activity.resources.displayMetrics.density))
            )
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        })

        return fabContainer
    }

    private fun addFabSpacer(parent: LinearLayout, height: Int) {
        parent.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        })
    }

    private fun calculateGamepadLayoutSpec(): GamepadLayoutSpec {
        val density = activity.resources.displayMetrics.density
        val screenWidth = currentHostWidthPx()
        val baseDpadSize = (250f * density).toInt()
        val baseButtonAreaSize = (220f * density).toInt()
        val minDpadSize = (120f * density).toInt()
        val minButtonAreaSize = (110f * density).toInt()
        val leftMarginMin = (12f * density).toInt()
        val rightMarginMin = maxOf((12f * density).toInt(), calculateFabStackReservedRightWidthPx())
        val bottomMargin = (40f * density).toInt()
        val desiredGap = maxOf((48f * density).toInt(), (screenWidth * 0.20f).toInt())
        val maxCombinedControlWidth = maxOf(
            minDpadSize + minButtonAreaSize,
            screenWidth - leftMarginMin - rightMarginMin - desiredGap
        )

        var scale = minOf(1f, maxCombinedControlWidth / (baseDpadSize + baseButtonAreaSize).toFloat())
        var dpadSize = maxOf(minDpadSize, (baseDpadSize * scale).toInt())
        var buttonAreaSize = maxOf(minButtonAreaSize, (baseButtonAreaSize * scale).toInt())
        var combined = dpadSize + buttonAreaSize
        if (combined > maxCombinedControlWidth) {
            val adjust = maxCombinedControlWidth / combined.toFloat()
            dpadSize = maxOf(minDpadSize, (dpadSize * adjust).toInt())
            buttonAreaSize = maxOf(minButtonAreaSize, (buttonAreaSize * adjust).toInt())
            combined = dpadSize + buttonAreaSize
        }

        val remaining = maxOf(0, screenWidth - desiredGap - combined - leftMarginMin - rightMarginMin)
        val leftMargin = leftMarginMin + remaining / 2
        val rightMargin = rightMarginMin + remaining - (remaining / 2)
        val actualGap = maxOf(0, screenWidth - leftMargin - dpadSize - rightMargin - buttonAreaSize)

        return GamepadLayoutSpec(
            dpadSizePx = dpadSize,
            buttonAreaSizePx = buttonAreaSize,
            leftMarginPx = leftMargin,
            rightMarginPx = rightMargin,
            bottomMarginPx = bottomMargin,
            centerGapPx = actualGap
        )
    }

    private fun calculateFabStackReservedRightWidthPx(): Int {
        val density = activity.resources.displayMetrics.density
        val stackRightMargin = (16 * density).toInt()
        val extraClearance = (10 * density).toInt()
        return stackRightMargin + calculateToolStackLayoutSpec().fabSizePx + extraClearance
    }

    private fun updateOverlayLayout() {
        updateVirtualGamepadLayout()
        updateFloatingToolStackLayout()
        updateExitButtonLayout()
    }

    private fun updateVirtualGamepadLayout() {
        applyVirtualGamepadLayout(calculateGamepadLayoutSpec())
    }

    private fun applyVirtualGamepadLayout(spec: GamepadLayoutSpec) {
        virtualDPad?.layoutParams = RelativeLayout.LayoutParams(spec.dpadSizePx, spec.dpadSizePx).apply {
            addRule(RelativeLayout.ALIGN_PARENT_START)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            setMargins(spec.leftMarginPx, 0, 0, spec.bottomMarginPx)
        }

        virtualButtons?.layoutParams = RelativeLayout.LayoutParams(spec.buttonAreaSizePx, spec.buttonAreaSizePx).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            setMargins(0, 0, spec.rightMarginPx, spec.bottomMarginPx)
        }
    }

    private fun updateFloatingToolStackLayout() {
        val stack = floatingToolStack ?: return
        val spec = calculateToolStackLayoutSpec()
        stack.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            setMargins(0, 0, spec.rightMarginPx, spec.bottomMarginPx)
        }
    }

    private fun updateExitButtonLayout() {
        val button = exitButton ?: return
        val spec = calculateExitButtonLayoutSpec()
        button.layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
            addRule(RelativeLayout.ALIGN_PARENT_END)
            setMargins(0, spec.topMarginPx, spec.rightMarginPx, 0)
        }
    }

    private fun calculateToolStackLayoutSpec(): ToolStackLayoutSpec {
        val density = activity.resources.displayMetrics.density
        val hostHeight = currentHostHeightPx()
        val hostWidth = currentHostWidthPx()
        val minFabSize = (32 * density).toInt()
        val minFabMargin = (4 * density).toInt()
        val maxFabSize = (56 * density).toInt()
        val maxFabMargin = (12 * density).toInt()
        val fabCount = 5
        val exitSpec = calculateExitButtonLayoutSpec()
        val rightMargin = (16 * density).toInt()
        val bottomMargin = (16 * density).toInt()
        val exitClearance = (16 * density).toInt()
        val reservedTopHeight = exitSpec.topMarginPx + exitSpec.sizePx + exitClearance
        val availableHeight = maxOf(
            maxFabSize,
            hostHeight - reservedTopHeight - bottomMargin
        )
        val scaleH = (availableHeight - (32 * density).toInt()) /
            (fabCount * maxFabSize + (fabCount - 1) * maxFabMargin).toFloat()
        val scaleW = (hostWidth * 0.18f) / maxFabSize.toFloat()
        val scale = minOf(scaleH, scaleW, 1.0f).coerceAtLeast(0.55f)

        return ToolStackLayoutSpec(
            fabSizePx = maxOf(minFabSize, (maxFabSize * scale).toInt()),
            fabSpacingPx = maxOf(minFabMargin, (maxFabMargin * scale).toInt()),
            rightMarginPx = rightMargin,
            bottomMarginPx = bottomMargin
        )
    }

    private fun calculateExitButtonLayoutSpec(): ExitButtonLayoutSpec {
        val density = activity.resources.displayMetrics.density
        val hostHeight = currentHostHeightPx()
        val minFabSize = (40 * density).toInt()
        val maxFabSize = (56 * density).toInt()
        val topMargin = calculateTopInsetMarginPx()
        val rightMargin = (16 * density).toInt()
        val availableHeight = maxOf((minFabSize + (8 * density).toInt()), hostHeight - topMargin)
        val size = if (availableHeight < maxFabSize + (16 * density).toInt()) {
            maxOf(minFabSize, availableHeight - (8 * density).toInt())
        } else {
            maxFabSize
        }

        return ExitButtonLayoutSpec(
            sizePx = size,
            topMarginPx = topMargin,
            rightMarginPx = rightMargin
        )
    }

    private fun calculateTopInsetMarginPx(): Int {
        val density = activity.resources.displayMetrics.density
        val baseMargin = (40 * density).toInt()
        val insets = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            hostContainer.rootWindowInsets
        } else {
            null
        }
        val topInset = when {
            insets == null -> 0
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R -> {
                insets.getInsets(WindowInsets.Type.systemBars()).top
            }

            else -> insets.systemWindowInsetTop
        }

        return if (topInset > 0) topInset + (8 * density).toInt() else baseMargin
    }

    private fun currentHostWidthPx(): Int {
        return sdlContainer?.width?.takeIf { it > 0 }
            ?: hostContainer.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
    }

    private fun currentHostHeightPx(): Int {
        return sdlContainer?.height?.takeIf { it > 0 }
            ?: hostContainer.height.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels
    }

    private fun isTouchOnInteractiveOverlay(event: MotionEvent): Boolean {
        val interactiveViews = buildList {
            remoteControllerOverlay?.takeIf { it.visibility == View.VISIBLE }?.let(::add)
            settingsOverlay?.takeIf { it.visibility == View.VISIBLE }?.let(::add)
            saveStateOverlay?.takeIf { it.visibility == View.VISIBLE }?.let(::add)
            saveStateProgressOverlay?.takeIf { it.visibility == View.VISIBLE }?.let(::add)
            keyboardInputContainer?.takeIf { it.visibility == View.VISIBLE }?.let(::add)
            virtualDPad?.takeIf { it.visibility == View.VISIBLE }?.let(::add)
            virtualButtons?.takeIf { it.visibility == View.VISIBLE }?.let(::add)
            floatingToolStack?.let { stack ->
                for (index in 0 until stack.childCount) {
                    val child = stack.getChildAt(index)
                    if (child.visibility == View.VISIBLE && child.isClickable) {
                        add(child)
                    }
                }
            }
            exitButton?.takeIf { it.visibility == View.VISIBLE }?.let(::add)
        }

        return interactiveViews.any { view ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val left = location[0].toFloat()
            val top = location[1].toFloat()
            val right = left + view.width
            val bottom = top + view.height
            event.rawX in left..right && event.rawY in top..bottom
        }
    }

    private fun toggleVirtualDPad() {
        val dPad = virtualDPad ?: return
        if (dPad.visibility == View.VISIBLE) {
            dPad.visibility = View.GONE
            virtualButtons?.visibility = View.GONE
        } else {
            dPad.visibility = View.VISIBLE
            virtualButtons?.visibility = View.VISIBLE
        }
        sdlContainer?.requestFocus()
    }

    private fun showSaveLoadMenu() {
        hideRemoteControllerOverlay()
        hideSettingsOverlay()
        if (keyboardShown) {
            hideKeyboardInput()
        }

        val rootView = sdlContainer ?: return
        hideSaveStateOverlay()
        val theme = getDialogThemeColors()
        val density = activity.resources.displayMetrics.density

        val overlay = createDismissibleOverlay { hideSaveStateOverlay() }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
            background = GradientDrawable().apply {
                setColor(theme.cardBackground)
                cornerRadius = 20f * density
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        card.addView(TextView(activity).apply {
            text = "Game state (Load game first)"
            setTextColor(theme.primaryText)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, (16 * density).toInt())
        })

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        actionsRow.addView(createSaveStateActionButton(
            label = "Save",
            backgroundColor = theme.primaryButtonBg,
            textColor = theme.primaryButtonText,
            borderColor = theme.buttonBorder
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                hideSaveStateOverlay()
                performManualSaveState()
            }
        })

        actionsRow.addView(createSaveStateActionButton(
            label = "Load",
            backgroundColor = theme.secondaryButtonBg,
            textColor = theme.secondaryButtonText,
            borderColor = theme.buttonBorder
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (8 * density).toInt()
            }
            setOnClickListener { showLoadSlotsOverlay() }
        })

        card.addView(actionsRow)

        overlay.addView(card, FrameLayout.LayoutParams(
            (currentHostWidthPx() * 0.62f).toInt().coerceAtLeast((280 * density).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        rootView.addView(overlay)
        saveStateOverlay = overlay
        bringOverlayToFront(overlay)
    }

    private fun hideSaveStateOverlay() {
        val overlay = saveStateOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        saveStateOverlay = null
    }

    private fun showLoadSlotsOverlay() {
        val rootView = sdlContainer ?: return
        hideSaveStateOverlay()
        val theme = getDialogThemeColors()
        val density = activity.resources.displayMetrics.density
        val slots = SaveStateRepository.getSlots(activity, currentSaveStateGameId)

        val overlay = createDismissibleOverlay { hideSaveStateOverlay() }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), (16 * density).toInt())
            background = GradientDrawable().apply {
                setColor(theme.cardBackground)
                cornerRadius = 20f * density
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        card.addView(TextView(activity).apply {
            text = "Load State"
            setTextColor(theme.primaryText)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, (12 * density).toInt())
        })

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
        }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val listPadding = (12 * density).toInt()
            setPadding(listPadding, listPadding, listPadding, listPadding)
        }
        scroll.addView(list)
        card.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        slots.forEach { slotInfo ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(theme.cardBackground)
                    cornerRadius = 14f * density
                    if (theme.buttonBorder != Color.TRANSPARENT) {
                        setStroke((1 * density).toInt().coerceAtLeast(1), theme.buttonBorder)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
            }

            val thumb = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams((72 * density).toInt(), (40 * density).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (slotInfo.exists && slotInfo.thumbnailFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(slotInfo.thumbnailFile.absolutePath)
                    if (bitmap != null) {
                        setImageBitmap(bitmap)
                    } else {
                        setImageResource(android.R.drawable.ic_menu_report_image)
                    }
                } else {
                    setImageResource(android.R.drawable.ic_menu_report_image)
                    alpha = 0.45f
                }
            }
            row.addView(thumb)

            val label = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (10 * density).toInt()
                }
                setTextColor(theme.primaryText)
                if (slotInfo.exists) {
                    val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(slotInfo.lastModified))
                    text = "Slot ${slotInfo.slot}\n$whenText"
                } else {
                    text = "Slot ${slotInfo.slot}\nEmpty"
                    alpha = 0.6f
                }
            }
            row.addView(label)

            if (slotInfo.exists) {
                val loadClick = View.OnClickListener {
                    hideSaveStateOverlay()
                    requestManualLoadState(slotInfo.stateFile.absolutePath)
                }
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener(loadClick)
                thumb.setOnClickListener(loadClick)
                label.setOnClickListener(loadClick)
            }

            row.addView(ImageButton(activity).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(theme.primaryText)
                contentDescription = "Delete slot ${slotInfo.slot}"
                isEnabled = slotInfo.exists
                alpha = if (slotInfo.exists) 1.0f else 0.45f
                setOnClickListener {
                    val deleted = SaveStateRepository.deleteSlot(activity, currentSaveStateGameId, slotInfo.slot)
                    Toast.makeText(
                        activity,
                        if (deleted) "Deleted slot ${slotInfo.slot}" else "Delete failed",
                        Toast.LENGTH_SHORT
                    ).show()
                    showLoadSlotsOverlay()
                }
            })

            list.addView(row)
        }

        overlay.addView(card, FrameLayout.LayoutParams(
            (currentHostWidthPx() * 0.82f).toInt().coerceAtLeast((320 * density).toInt()),
            (currentHostHeightPx() * 0.78f).toInt(),
            Gravity.CENTER
        ))

        rootView.addView(overlay)
        saveStateOverlay = overlay
        bringOverlayToFront(overlay)
    }

    private fun showSaveSlotsFullOverlay() {
        val rootView = sdlContainer ?: return
        hideSaveStateOverlay()
        val theme = getDialogThemeColors()
        val density = activity.resources.displayMetrics.density

        val overlay = createDismissibleOverlay { hideSaveStateOverlay() }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
            background = GradientDrawable().apply {
                setColor(theme.cardBackground)
                cornerRadius = 20f * density
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        card.addView(TextView(activity).apply {
            text = "Save slots full"
            setTextColor(theme.primaryText)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, (10 * density).toInt())
        })

        card.addView(TextView(activity).apply {
            text = "All 5 save slots are used. Delete one slot from Load State to save again."
            setTextColor(theme.secondaryText)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, (16 * density).toInt())
        })

        card.addView(createSaveStateActionButton(
            label = "OK",
            backgroundColor = theme.primaryButtonBg,
            textColor = theme.primaryButtonText,
            borderColor = theme.buttonBorder
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { hideSaveStateOverlay() }
        })

        overlay.addView(card, FrameLayout.LayoutParams(
            (currentHostWidthPx() * 0.68f).toInt().coerceAtLeast((300 * density).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        rootView.addView(overlay)
        saveStateOverlay = overlay
        bringOverlayToFront(overlay)
    }

    private fun performManualSaveState() {
        if (saveStateInProgress) {
            Toast.makeText(activity, "Save already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        val nextSlot = SaveStateRepository.findFirstEmptySlot(activity, currentSaveStateGameId)
        if (nextSlot == null) {
            showSaveSlotsFullOverlay()
            return
        }

        val targetStatePath = SaveStateRepository
            .getStateFile(activity, currentSaveStateGameId, nextSlot)
            .absolutePath
        DOSBoxJNI.nativeSetSaveStateContext(currentSaveStateGameId, targetStatePath)

        captureCurrentFrameBitmap { capturedBitmap ->
            startManualSaveState(nextSlot, targetStatePath, capturedBitmap)
        }
    }

    private fun captureCurrentFrameBitmap(callback: (Bitmap?) -> Unit) {
        val rootContent = sdlContainer ?: hostContainer
        val width = rootContent.width.takeIf { it > 0 } ?: currentHostWidthPx()
        val height = rootContent.height.takeIf { it > 0 } ?: currentHostHeightPx()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sdlSurface: Surface? = SDLActivity.getNativeSurface()
            if (sdlSurface != null && sdlSurface.isValid && width > 0 && height > 0) {
                val surfaceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val handlerThread = HandlerThread("savestate-pre-capture").apply { start() }
                PixelCopy.request(
                    sdlSurface,
                    surfaceBitmap,
                    { result ->
                        val captured = if (result == PixelCopy.SUCCESS) surfaceBitmap else null
                        if (captured == null) {
                            surfaceBitmap.recycle()
                        }
                        mainHandler.post { callback(captured) }
                        handlerThread.quitSafely()
                    },
                    Handler(handlerThread.looper)
                )
                return
            }

            if (width <= 0 || height <= 0) {
                callback(null)
                return
            }

            val hostWindow = activity.resolveHostWindow()
            if (hostWindow != null) {
                val windowBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val handlerThread = HandlerThread("savestate-pre-capture").apply { start() }
                PixelCopy.request(
                    hostWindow,
                    windowBitmap,
                    PixelCopy.OnPixelCopyFinishedListener { result ->
                        val captured = if (result == PixelCopy.SUCCESS) windowBitmap else null
                        if (captured == null) {
                            windowBitmap.recycle()
                        }
                        mainHandler.post { callback(captured) }
                        handlerThread.quitSafely()
                    },
                    Handler(handlerThread.looper)
                )
                return
            }
        }

        try {
            val fallbackBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fallbackBitmap)
            rootContent.draw(canvas)
            callback(fallbackBitmap)
        } catch (t: Throwable) {
            Log.e(TAG, "Fallback pre-save capture failed", t)
            callback(null)
        }
    }

    private fun startManualSaveState(targetSlot: Int, targetStatePath: String, capturedThumbnail: Bitmap?) {
        saveStateInProgress = true
        showSaveStateProgressOverlay()

        Thread({
            var ok = false
            try {
                val stateFile = File(targetStatePath)
                val beforeModified = if (stateFile.exists()) stateFile.lastModified() else 0L
                ok = DOSBoxJNI.nativeSaveStateAndWait(12000)
                if (!ok && stateFile.exists() && stateFile.lastModified() > beforeModified) {
                    ok = true
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Manual save failed", t)
            }

            val saveOk = ok
            mainHandler.post {
                hideSaveStateProgressOverlay()
                saveStateInProgress = false
                if (saveOk) {
                    if (capturedThumbnail != null) {
                        val thumbFile = SaveStateRepository.getThumbnailFile(activity, currentSaveStateGameId, targetSlot)
                        persistThumbnail(capturedThumbnail, thumbFile)
                        capturedThumbnail.recycle()
                    } else {
                        saveSlotThumbnailAsync(targetSlot)
                    }
                    Toast.makeText(activity, "State saved", Toast.LENGTH_SHORT).show()
                } else {
                    if (capturedThumbnail != null && !capturedThumbnail.isRecycled) {
                        capturedThumbnail.recycle()
                    }
                    Toast.makeText(activity, "Save failed", Toast.LENGTH_SHORT).show()
                }
            }
        }, "savestate-save-thread").start()
    }

    private fun showSaveStateProgressOverlay() {
        if (saveStateProgressOverlay != null) {
            return
        }

        val rootView = sdlContainer ?: return
        val density = activity.resources.displayMetrics.density
        val overlay = FrameLayout(activity).apply {
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
            setBackgroundColor(0x66000000)
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * density).toInt()
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(0xCC1F1F1F.toInt())
                cornerRadius = 12f * density
            }
        }

        content.addView(ProgressBar(activity, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = true
        })

        content.addView(TextView(activity).apply {
            text = "Saving state..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (12 * density).toInt()
            }
        })

        overlay.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        rootView.addView(overlay)
        saveStateProgressOverlay = overlay
        bringOverlayToFront(overlay)
    }

    private fun hideSaveStateProgressOverlay() {
        val overlay = saveStateProgressOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        saveStateProgressOverlay = null
    }

    private fun saveSlotThumbnailAsync(slot: Int) {
        val thumbFile = SaveStateRepository.getThumbnailFile(activity, currentSaveStateGameId, slot)
        val rootContent = sdlContainer ?: hostContainer
        val width = rootContent.width.takeIf { it > 0 } ?: return
        val height = rootContent.height.takeIf { it > 0 } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hostWindow = activity.resolveHostWindow()
            if (hostWindow != null) {
                val windowBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val handlerThread = HandlerThread("savestate-thumb-copy").apply { start() }
                PixelCopy.request(
                    hostWindow,
                    windowBitmap,
                    PixelCopy.OnPixelCopyFinishedListener { result ->
                        try {
                            if (result == PixelCopy.SUCCESS) {
                                persistThumbnail(windowBitmap, thumbFile)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to persist slot thumbnail", t)
                        } finally {
                            windowBitmap.recycle()
                            handlerThread.quitSafely()
                        }
                    },
                    Handler(handlerThread.looper)
                )
                return
            }
        }

        try {
            val fallbackBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fallbackBitmap)
            rootContent.draw(canvas)
            persistThumbnail(fallbackBitmap, thumbFile)
            fallbackBitmap.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "Fallback thumbnail capture failed", t)
        }
    }

    private fun persistThumbnail(source: Bitmap, targetFile: File) {
        val scaled = Bitmap.createScaledBitmap(source, 240, 135, true)
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        try {
            FileOutputStream(targetFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing thumbnail file: ${targetFile.absolutePath}", t)
        } finally {
            scaled.recycle()
        }
    }

    private fun requestManualLoadState(statePath: String) {
        val selectedState = File(statePath)
        if (!selectedState.exists() || selectedState.length() <= 0L) {
            Toast.makeText(activity, "No saved state", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            DOSBoxJNI.nativeSetSaveStateContext(currentSaveStateGameId, statePath)
            DOSBoxJNI.nativeRequestLoadState()
            Toast.makeText(activity, "State load requested", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e(TAG, "Manual load request failed", t)
            Toast.makeText(activity, "Load failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createDismissibleOverlay(onDismiss: () -> Unit): FrameLayout {
        return FrameLayout(activity).apply {
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onDismiss() }
        }
    }

    private fun createSaveStateActionButton(
        label: String,
        backgroundColor: Int,
        textColor: Int,
        borderColor: Int
    ): Button {
        val density = activity.resources.displayMetrics.density
        return Button(activity).apply {
            text = label
            isAllCaps = false
            setTextColor(textColor)
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = 26f * density
                if (borderColor != Color.TRANSPARENT) {
                    setStroke((1 * density).toInt().coerceAtLeast(1), borderColor)
                }
            }
        }
    }

    private fun getDialogThemeColors(): DialogThemeColors {
        val themeMode = activity.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, "DARK")

        return when (themeMode) {
            "LIGHT" -> DialogThemeColors(
                cardBackground = Color.parseColor("#ECEAF4"),
                primaryText = Color.parseColor("#000000"),
                secondaryText = Color.parseColor("#000000"),
                primaryButtonBg = Color.parseColor("#4CAF50"),
                primaryButtonText = Color.WHITE,
                secondaryButtonBg = Color.parseColor("#2196F3"),
                secondaryButtonText = Color.WHITE,
                buttonBorder = Color.TRANSPARENT
            )

            "DARK_RETRO" -> DialogThemeColors(
                cardBackground = Color.parseColor("#101615"),
                primaryText = Color.parseColor("#39FF14"),
                secondaryText = Color.parseColor("#39FF14"),
                primaryButtonBg = Color.BLACK,
                primaryButtonText = Color.parseColor("#39FF14"),
                secondaryButtonBg = Color.BLACK,
                secondaryButtonText = Color.parseColor("#39FF14"),
                buttonBorder = Color.parseColor("#39FF14")
            )

            else -> DialogThemeColors(
                cardBackground = Color.parseColor("#1A1F26"),
                primaryText = Color.WHITE,
                secondaryText = Color.WHITE,
                primaryButtonBg = Color.BLACK,
                primaryButtonText = Color.WHITE,
                secondaryButtonBg = Color.BLACK,
                secondaryButtonText = Color.WHITE,
                buttonBorder = Color.WHITE
            )
        }
    }

    private fun toggleOnScreenKeyboard() {
        if (keyboardShown && keyboardMode == KeyboardOverlayMode.TEXT_ENTRY) {
            hideKeyboardInput()
        } else {
            pendingKeyCapture = null
            settingsStatusText?.text = "Tap Assign to choose a key from the on-screen keyboard."
            showKeyboardOverlay(KeyboardOverlayMode.TEXT_ENTRY)
        }
    }

    private fun toggleWifiController() {
        if (isWifiServerRunning) {
            activeControllerUrl?.let { showRemoteControllerOverlay(it) }
        } else {
            startWifiServer()
        }
    }

    private fun updateWifiButtonState() {
        val stack = floatingToolStack ?: return
        val remoteButton = stack.getChildAt(stack.childCount - 1) as? FrameLayout ?: return
        val imageButton = remoteButton.getChildAt(0) as? ImageButton ?: return
        val drawable = imageButton.background as? GradientDrawable ?: return
        drawable.setColor(if (isWifiServerRunning) 0xCC4CAF50.toInt() else 0xCC9C27B0.toInt())
    }

    private fun dispatchSyntheticKey(keyCode: Int, pressed: Boolean) {
        val event = KeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
        if (inputDirector.processKeyEvent(event)) {
            return
        }
        NativeBridge.sendKey(keyCode, pressed)
    }

    private fun bringOverlayToFront(view: View?) {
        val rootView = sdlContainer ?: return
        val overlayView = view ?: return
        if (overlayView.parent === rootView) {
            rootView.bringChildToFront(overlayView)
            rootView.requestLayout()
            rootView.invalidate()
        }
    }

    private fun showKeyboardOverlay(mode: KeyboardOverlayMode) {
        keyboardMode = mode
        val rootView = sdlContainer ?: return
        val overlay = keyboardInputContainer ?: createKeyboardOverlay().also {
            rootView.addView(it)
            keyboardInputContainer = it
        }
        overlay.visibility = View.VISIBLE
        bringOverlayToFront(overlay)
        keyboardShown = true
        rebuildKeyboardOverlay()
    }

    private fun hideKeyboardInput() {
        keyboardInputContainer?.visibility = View.GONE
        keyboardShown = false
        keyboardShiftEnabled = false
        keyboardSymbolsEnabled = false
        keyboardMode = KeyboardOverlayMode.TEXT_ENTRY
        if (pendingKeyCapture != null) {
            cancelPendingKeyCapture()
        }
        sdlContainer?.requestFocus()
    }

    private fun createKeyboardOverlay(): FrameLayout {
        val density = activity.resources.displayMetrics.density
        val overlay = FrameLayout(activity).apply {
            visibility = View.GONE
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x44000000)
            isClickable = true
            isFocusable = true
            setOnClickListener { hideKeyboardInput() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18f * density
                setColor(0xEE111827.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), 0xFF475569.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        keyboardPanelTitleView = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        card.addView(keyboardPanelTitleView)

        keyboardRowsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(keyboardRowsContainer)

        overlay.addView(card, FrameLayout.LayoutParams(
            (currentHostWidthPx() * 0.92f).toInt().coerceAtLeast((520 * density).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            setMargins((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (12 * density).toInt())
        })

        return overlay
    }

    private fun rebuildKeyboardOverlay() {
        keyboardPanelTitleView?.text = when (keyboardMode) {
            KeyboardOverlayMode.TEXT_ENTRY -> "DOS Keyboard"
            KeyboardOverlayMode.KEY_CAPTURE -> "Choose Key For ${pendingKeyCapture?.label ?: "Control"}"
        }

        val rowsContainer = keyboardRowsContainer ?: return
        val density = activity.resources.displayMetrics.density
        val rows = keyboardLayoutRows()
        rowsContainer.removeAllViews()
        rows.forEachIndexed { index, row ->
            val rowView = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = row.fold(0f) { total, key -> total + key.weight }
            }
            row.forEachIndexed { keyIndex, key ->
                rowView.addView(
                    createKeyboardKeyButton(key),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, key.weight).apply {
                        if (keyIndex < row.lastIndex) {
                            marginEnd = (6 * density).toInt()
                        }
                    }
                )
            }
            rowsContainer.addView(rowView)
            if (index < rows.lastIndex) {
                rowsContainer.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (6 * density).toInt()
                    )
                })
            }
        }
    }

    private fun keyboardLayoutRows(): List<List<KeyboardKeySpec>> {
        val controlRow = listOf(
            specialKeyboardKey("Esc", KeyEvent.KEYCODE_ESCAPE, 1.1f),
            specialKeyboardKey("Tab", KeyEvent.KEYCODE_TAB, 1.1f),
            specialKeyboardKey("<-", KeyEvent.KEYCODE_DPAD_LEFT, 1f),
            specialKeyboardKey("^", KeyEvent.KEYCODE_DPAD_UP, 1f),
            specialKeyboardKey("v", KeyEvent.KEYCODE_DPAD_DOWN, 1f),
            specialKeyboardKey("->", KeyEvent.KEYCODE_DPAD_RIGHT, 1f),
            specialKeyboardKey("Bksp", KeyEvent.KEYCODE_DEL, 1.3f),
            KeyboardKeySpec("Close", KeyboardKeyAction.CLOSE, weight = 1.2f)
        )

        val numberRow = "1234567890".map { char -> textKeyboardKey(char.toString()) }
        val toggleLabel = if (keyboardSymbolsEnabled) "ABC" else "123"
        val shiftLabel = if (keyboardShiftEnabled) "Shift*" else "Shift"

        return if (keyboardSymbolsEnabled) {
            val symbolRow3 = mutableListOf<KeyboardKeySpec>()
            symbolRow3 += KeyboardKeySpec(shiftLabel, KeyboardKeyAction.TOGGLE_SHIFT, weight = 1.25f)
            symbolRow3 += listOf(";", ":", "'", "\"", ",", ".", "/", "?").map(::textKeyboardKey)
            symbolRow3 += specialKeyboardKey("Enter", KeyEvent.KEYCODE_ENTER, 1.4f)

            listOf(
                controlRow,
                numberRow,
                listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")").map(::textKeyboardKey),
                listOf("-", "_", "=", "+", "[", "]", "{", "}", "\\", "|").map(::textKeyboardKey),
                symbolRow3,
                listOf(
                    KeyboardKeySpec(toggleLabel, KeyboardKeyAction.TOGGLE_SYMBOLS, weight = 1.2f),
                    specialKeyboardKey("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1.1f),
                    specialKeyboardKey("Alt", KeyEvent.KEYCODE_ALT_LEFT, 1.1f),
                    KeyboardKeySpec("Space", KeyboardKeyAction.TYPE_TEXT, text = " ", weight = 4f),
                    textKeyboardKey("`"),
                    textKeyboardKey("~")
                )
            )
        } else {
            val lettersRow1 = "qwertyuiop".map { char -> textKeyboardKey(letterForDisplay(char)) }
            val lettersRow2 = mutableListOf<KeyboardKeySpec>()
            lettersRow2 += "asdfghjkl".map { char -> textKeyboardKey(letterForDisplay(char)) }
            lettersRow2 += textKeyboardKey(";")

            val lettersRow3 = mutableListOf<KeyboardKeySpec>()
            lettersRow3 += KeyboardKeySpec(shiftLabel, KeyboardKeyAction.TOGGLE_SHIFT, weight = 1.25f)
            lettersRow3 += "zxcvbnm".map { char -> textKeyboardKey(letterForDisplay(char)) }
            lettersRow3 += listOf(textKeyboardKey(","), textKeyboardKey("."), textKeyboardKey("/"))
            lettersRow3 += specialKeyboardKey("Enter", KeyEvent.KEYCODE_ENTER, 1.4f)

            listOf(
                controlRow,
                numberRow,
                lettersRow1,
                lettersRow2,
                lettersRow3,
                listOf(
                    KeyboardKeySpec(toggleLabel, KeyboardKeyAction.TOGGLE_SYMBOLS, weight = 1.2f),
                    specialKeyboardKey("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1.1f),
                    specialKeyboardKey("Alt", KeyEvent.KEYCODE_ALT_LEFT, 1.1f),
                    KeyboardKeySpec("Space", KeyboardKeyAction.TYPE_TEXT, text = " ", weight = 4f)
                )
            )
        }
    }

    private fun createKeyboardKeyButton(spec: KeyboardKeySpec): View {
        val density = activity.resources.displayMetrics.density
        val pressedFlashDurationMs = 120L
        val isToggleActive = (spec.action == KeyboardKeyAction.TOGGLE_SHIFT && keyboardShiftEnabled) ||
            (spec.action == KeyboardKeyAction.TOGGLE_SYMBOLS && keyboardSymbolsEnabled)
        val backgroundColor = when {
            spec.action == KeyboardKeyAction.CLOSE -> 0xFFDC2626.toInt()
            isToggleActive -> 0xFF2563EB.toInt()
            spec.action == KeyboardKeyAction.SEND_KEYCODE -> 0xFF334155.toInt()
            else -> 0xFF1F2937.toInt()
        }
        val defaultTextColor = Color.WHITE
        val pressedTextColor = 0xFF111827.toInt()

        fun keyBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(fillColor)
                setStroke((2 * density).toInt().coerceAtLeast(1), strokeColor)
            }
        }

        val defaultBackground = keyBackground(backgroundColor, 0xFF64748B.toInt())
        val pressedBackground = keyBackground(Color.WHITE, backgroundColor)

        return TextView(activity).apply {
            text = spec.label
            gravity = Gravity.CENTER
            setTextColor(defaultTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            minHeight = (44 * density).toInt()
            minimumHeight = (44 * density).toInt()
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            background = defaultBackground
            isClickable = true
            isFocusable = false

            fun applyPressedState(pressed: Boolean) {
                background = if (pressed) pressedBackground else defaultBackground
                setTextColor(if (pressed) pressedTextColor else defaultTextColor)
                scaleX = if (pressed) 0.93f else 1f
                scaleY = if (pressed) 0.93f else 1f
                alpha = if (pressed) 0.92f else 1f
                invalidate()
            }

            val clearPressedState = Runnable { applyPressedState(false) }

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.removeCallbacks(clearPressedState)
                        applyPressedState(true)
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val isInside = event.x >= 0f && event.x <= view.width &&
                            event.y >= 0f && event.y <= view.height
                        view.removeCallbacks(clearPressedState)
                        applyPressedState(isInside)
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val isInside = event.x >= 0f && event.x <= view.width &&
                            event.y >= 0f && event.y <= view.height
                        if (isInside) {
                            applyPressedState(true)
                            performClick()
                            view.postDelayed(clearPressedState, pressedFlashDurationMs)
                        } else {
                            applyPressedState(false)
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        view.removeCallbacks(clearPressedState)
                        applyPressedState(false)
                        true
                    }

                    else -> false
                }
            }
            setOnClickListener { handleKeyboardKey(spec) }
        }
    }

    private fun handleKeyboardKey(spec: KeyboardKeySpec) {
        when (spec.action) {
            KeyboardKeyAction.CLOSE -> {
                hideKeyboardInput()
            }

            KeyboardKeyAction.TOGGLE_SHIFT -> {
                if (keyboardMode == KeyboardOverlayMode.KEY_CAPTURE) {
                    applyCapturedKey(KeyEvent.KEYCODE_SHIFT_LEFT)
                    hideKeyboardInput()
                } else {
                    keyboardShiftEnabled = !keyboardShiftEnabled
                    rebuildKeyboardOverlay()
                }
            }

            KeyboardKeyAction.TOGGLE_SYMBOLS -> {
                keyboardSymbolsEnabled = !keyboardSymbolsEnabled
                keyboardShiftEnabled = false
                rebuildKeyboardOverlay()
            }

            KeyboardKeyAction.SEND_KEYCODE -> {
                val keyCode = spec.keyCode ?: return
                if (keyboardMode == KeyboardOverlayMode.KEY_CAPTURE) {
                    applyCapturedKey(keyCode)
                    hideKeyboardInput()
                } else {
                    sendKeyCodeTap(keyCode)
                }
            }

            KeyboardKeyAction.TYPE_TEXT -> {
                val text = spec.text ?: return
                if (keyboardMode == KeyboardOverlayMode.KEY_CAPTURE) {
                    val keyCode = keyCodeForText(text) ?: return
                    applyCapturedKey(keyCode)
                    hideKeyboardInput()
                } else {
                    sendTextAsKeyEvents(text)
                    if (keyboardShiftEnabled && text.length == 1 && text[0].isLetter()) {
                        keyboardShiftEnabled = false
                        rebuildKeyboardOverlay()
                    }
                }
            }
        }
    }

    private fun specialKeyboardKey(label: String, keyCode: Int, weight: Float = 1f): KeyboardKeySpec {
        return KeyboardKeySpec(label, KeyboardKeyAction.SEND_KEYCODE, keyCode = keyCode, weight = weight)
    }

    private fun textKeyboardKey(text: String): KeyboardKeySpec {
        return KeyboardKeySpec(text, KeyboardKeyAction.TYPE_TEXT, text = text)
    }

    private fun letterForDisplay(c: Char): String {
        return if (keyboardShiftEnabled) c.uppercaseChar().toString() else c.toString()
    }

    private fun keyCodeForText(text: String): Int? {
        if (text.isEmpty()) {
            return null
        }
        if (text == " ") {
            return KeyEvent.KEYCODE_SPACE
        }
        return if (text.length == 1) keyCodeForChar(text[0]).takeIf { it != KeyEvent.KEYCODE_UNKNOWN } else null
    }

    private fun sendKeyCodeTap(keyCode: Int) {
        dispatchSyntheticKey(keyCode, true)
        dispatchSyntheticKey(keyCode, false)
    }

    private fun keyCodeForChar(c: Char): Int {
        return when (c) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (c - 'A')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\n' -> KeyEvent.KEYCODE_ENTER
            '\t' -> KeyEvent.KEYCODE_TAB
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '/' -> KeyEvent.KEYCODE_SLASH
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '`' -> KeyEvent.KEYCODE_GRAVE
            '!' -> KeyEvent.KEYCODE_1
            '@' -> KeyEvent.KEYCODE_2
            '#' -> KeyEvent.KEYCODE_3
            '$' -> KeyEvent.KEYCODE_4
            '%' -> KeyEvent.KEYCODE_5
            '^' -> KeyEvent.KEYCODE_6
            '&' -> KeyEvent.KEYCODE_7
            '*' -> KeyEvent.KEYCODE_8
            '(' -> KeyEvent.KEYCODE_9
            ')' -> KeyEvent.KEYCODE_0
            '_' -> KeyEvent.KEYCODE_MINUS
            '+' -> KeyEvent.KEYCODE_EQUALS
            '{' -> KeyEvent.KEYCODE_LEFT_BRACKET
            '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '|' -> KeyEvent.KEYCODE_BACKSLASH
            ':' -> KeyEvent.KEYCODE_SEMICOLON
            '"' -> KeyEvent.KEYCODE_APOSTROPHE
            '<' -> KeyEvent.KEYCODE_COMMA
            '>' -> KeyEvent.KEYCODE_PERIOD
            '?' -> KeyEvent.KEYCODE_SLASH
            '~' -> KeyEvent.KEYCODE_GRAVE
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    private fun sendTextAsKeyEvents(text: String, appendEnter: Boolean = false) {
        val payload = if (appendEnter) text + "\n" else text
        val characterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = characterMap.getEvents(payload.toCharArray()) ?: return
        mainHandler.post {
            events.forEach { keyEvent ->
                when (keyEvent.action) {
                    KeyEvent.ACTION_DOWN -> dispatchSyntheticKey(keyEvent.keyCode, true)
                    KeyEvent.ACTION_UP -> dispatchSyntheticKey(keyEvent.keyCode, false)
                }
            }
        }
    }

    private fun showRemoteControllerOverlay(url: String) {
        hideRemoteControllerOverlay()

        val rootView = sdlContainer ?: return
        val density = activity.resources.displayMetrics.density
        val qrSize = (minOf(currentHostWidthPx(), currentHostHeightPx()) * 0.42f).toInt()
            .coerceAtLeast((180 * density).toInt())
        val qrBitmap = generateQrCode(url, qrSize, qrSize)
        val overlay = FrameLayout(activity).apply {
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { hideRemoteControllerOverlay() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (18 * density).toInt(), (20 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(0xEE1A1F26.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        card.addView(TextView(activity).apply {
            text = "Remote Controller"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, (10 * density).toInt())
        })

        card.addView(TextView(activity).apply {
            text = "Scan the QR code to open the hosted phone controller."
            setTextColor(0xFFD0D7DE.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, (12 * density).toInt())
        })

        if (qrBitmap != null) {
            card.addView(ImageView(activity).apply {
                setImageBitmap(qrBitmap)
                layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = (12 * density).toInt()
                }
            })
        }

        card.addView(TextView(activity).apply {
            text = url
            setTextColor(0xFFB8C4D0.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), (14 * density).toInt())
        })

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        actionsRow.addView(createOverlayActionButton("Keep Running", 0xFF334155.toInt(), Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener { hideRemoteControllerOverlay() }
        })

        actionsRow.addView(createOverlayActionButton("Stop Server", 0xFFDC2626.toInt(), Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                stopWifiServer()
                Toast.makeText(activity, "Phone controller stopped", Toast.LENGTH_SHORT).show()
            }
        })

        card.addView(actionsRow)

        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            addView(card)
        }

        overlay.addView(scrollView, FrameLayout.LayoutParams(
            (currentHostWidthPx() * 0.74f).toInt().coerceAtLeast((280 * density).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        rootView.addView(overlay)
        remoteControllerOverlay = overlay
    }

    private fun hideRemoteControllerOverlay() {
        val overlay = remoteControllerOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        remoteControllerOverlay = null
    }

    private fun showSettingsOverlay() {
        hideRemoteControllerOverlay()
        if (settingsOverlay != null) {
            return
        }

        val rootView = sdlContainer ?: return
        val density = activity.resources.displayMetrics.density
        settingsValueViews.clear()

        val overlay = FrameLayout(activity).apply {
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { hideSettingsOverlay() }
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(0xEE1A1F26.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        card.addView(TextView(activity).apply {
            text = "Configure Controls"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, 0, (8 * density).toInt())
        })

        settingsStatusText = TextView(activity).apply {
            text = "Tap Assign, then press a key on the built-in keyboard or a physical controller."
            setTextColor(0xFFD0D7DE.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        card.addView(settingsStatusText)

        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        configBindings().forEachIndexed { index, binding ->
            content.addView(createSettingsBindingRow(binding))
            if (index != configBindings().lastIndex) {
                content.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (10 * density).toInt()
                    )
                })
            }
        }
        scrollView.addView(content)
        card.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        card.addView(TextView(activity).apply {
            text = "Tap Assign to open the in-app key chooser."
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, (12 * density).toInt())
        })

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }

        actionsRow.addView(createOverlayActionButton("Reset", 0xFF334155.toInt(), Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                resetKeyBindingsToDefaults()
                reloadVirtualMappings()
                settingsStatusText?.text = "Controls reset to defaults."
            }
        })

        actionsRow.addView(createOverlayActionButton("Done", 0xFF2563EB.toInt(), Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { hideSettingsOverlay() }
        })
        card.addView(actionsRow)

        overlay.addView(card, FrameLayout.LayoutParams(
            (currentHostWidthPx() * 0.78f).toInt().coerceAtLeast((320 * density).toInt()),
            (currentHostHeightPx() * 0.82f).toInt(),
            Gravity.CENTER
        ))

        rootView.addView(overlay)
        settingsOverlay = overlay
        bringOverlayToFront(overlay)
        refreshSettingsLabels()
    }

    private fun hideSettingsOverlay() {
        cancelPendingKeyCapture()
        val overlay = settingsOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        settingsOverlay = null
        settingsStatusText = null
        settingsValueViews.clear()
        if (keyboardMode == KeyboardOverlayMode.KEY_CAPTURE) {
            hideKeyboardInput()
        }
    }

    private fun createSettingsBindingRow(binding: KeyBindingSpec): View {
        val density = activity.resources.displayMetrics.density
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(0xFF111827.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), 0xFF374151.toInt())
            }

            addView(TextView(activity).apply {
                text = binding.label
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
            })

            val valueView = TextView(activity).apply {
                setTextColor(0xFFD0D7DE.toInt())
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            settingsValueViews[binding.storageKey(currentKeyPrefix())] = valueView
            addView(valueView)

            addView(createOverlayActionButton("Assign", 0xFF4B5563.toInt(), Color.WHITE).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { beginKeyCapture(binding) }
            })
        }
    }

    private fun beginKeyCapture(binding: KeyBindingSpec) {
        pendingKeyCapture = binding
        settingsStatusText?.text = "Choose a key for ${binding.label} from the on-screen keyboard."
        showKeyboardOverlay(KeyboardOverlayMode.KEY_CAPTURE)
    }

    private fun cancelPendingKeyCapture() {
        pendingKeyCapture = null
        settingsStatusText?.text = "Tap Assign to open the in-app key chooser."
    }

    private fun handlePendingKeyCapture(event: KeyEvent): Boolean {
        val binding = pendingKeyCapture ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                cancelPendingKeyCapture()
                true
            }

            else -> {
                applyCapturedKey(event.keyCode, binding)
                true
            }
        }
    }

    private fun applyCapturedKey(keyCode: Int, binding: KeyBindingSpec? = pendingKeyCapture) {
        val resolvedBinding = binding ?: return
        val prefs = activity.getSharedPreferences(resolvedBinding.prefsName, Context.MODE_PRIVATE)
        prefs.edit().putInt(resolvedBinding.storageKey(currentKeyPrefix()), keyCode).apply()
        settingsValueViews[resolvedBinding.storageKey(currentKeyPrefix())]?.text = displayKeyName(keyCode)
        pendingKeyCapture = null
        settingsStatusText?.text = "${resolvedBinding.label} mapped to ${displayKeyName(keyCode)}."
        reloadVirtualMappings()
        Toast.makeText(activity, "${resolvedBinding.label} mapped to ${displayKeyName(keyCode)}", Toast.LENGTH_SHORT).show()
    }

    private fun keyCodeFromTextInput(text: String): Int? {
        if (text.isBlank()) {
            return null
        }

        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            ?: return null
        return events.lastOrNull {
            it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode)
        }?.keyCode
    }

    private fun resetKeyBindingsToDefaults() {
        configBindings().forEach { binding ->
            activity.getSharedPreferences(binding.prefsName, Context.MODE_PRIVATE)
                .edit()
                .putInt(binding.storageKey(currentKeyPrefix()), binding.defaultKeyCode)
                .apply()
        }
        refreshSettingsLabels()
    }

    private fun refreshSettingsLabels() {
        configBindings().forEach { binding ->
            settingsValueViews[binding.storageKey(currentKeyPrefix())]?.text = displayKeyName(loadBindingKeyCode(binding))
        }
    }

    private fun reloadVirtualMappings() {
        virtualDPad?.reloadKeyMappings()
        virtualButtons?.reloadKeyMappings()
    }

    private fun loadBindingKeyCode(binding: KeyBindingSpec): Int {
        return activity.getSharedPreferences(binding.prefsName, Context.MODE_PRIVATE)
            .getInt(binding.storageKey(currentKeyPrefix()), binding.defaultKeyCode)
    }

    private fun currentKeyPrefix(): String {
        val gameFolder = currentGameFolder?.trim().orEmpty()
        return if (gameFolder.isNotEmpty()) "game_${gameFolder}_" else ""
    }

    private fun displayKeyName(keyCode: Int): String {
        val raw = KeyEvent.keyCodeToString(keyCode)
        return if (raw.startsWith("KEYCODE_")) raw.removePrefix("KEYCODE_") else raw
    }

    private fun configBindings(): List<KeyBindingSpec> = listOf(
        KeyBindingSpec("UP", "dpad_config", VirtualDPadView.PREF_KEY_UP, KeyEvent.KEYCODE_DPAD_UP),
        KeyBindingSpec("DOWN", "dpad_config", VirtualDPadView.PREF_KEY_DOWN, KeyEvent.KEYCODE_DPAD_DOWN),
        KeyBindingSpec("LEFT", "dpad_config", VirtualDPadView.PREF_KEY_LEFT, KeyEvent.KEYCODE_DPAD_LEFT),
        KeyBindingSpec("RIGHT", "dpad_config", VirtualDPadView.PREF_KEY_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT),
        KeyBindingSpec("A", "action_buttons_config", VirtualButtonsView.PREF_KEY_A, KeyEvent.KEYCODE_ENTER),
        KeyBindingSpec("B", "action_buttons_config", VirtualButtonsView.PREF_KEY_B, KeyEvent.KEYCODE_ESCAPE),
        KeyBindingSpec("X", "action_buttons_config", VirtualButtonsView.PREF_KEY_X, KeyEvent.KEYCODE_SPACE),
        KeyBindingSpec("Y", "action_buttons_config", VirtualButtonsView.PREF_KEY_Y, KeyEvent.KEYCODE_SHIFT_LEFT)
    )

    private fun createOverlayActionButton(label: String, backgroundColor: Int, textColor: Int): Button {
        return Button(activity).apply {
            text = label
            setTextColor(textColor)
            background = GradientDrawable().apply {
                cornerRadius = 24f * activity.resources.displayMetrics.density
                setColor(backgroundColor)
            }
            isAllCaps = false
        }
    }

    private fun generateQrCode(text: String, width: Int, height: Int): Bitmap? {
        return runCatching {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { bitmap ->
                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                }
            }
        }.onFailure {
            Log.e(TAG, "Failed to generate QR code", it)
        }.getOrNull()
    }

    private data class GamepadLayoutSpec(
        val dpadSizePx: Int,
        val buttonAreaSizePx: Int,
        val leftMarginPx: Int,
        val rightMarginPx: Int,
        val bottomMarginPx: Int,
        val centerGapPx: Int
    )

    private data class ToolStackLayoutSpec(
        val fabSizePx: Int,
        val fabSpacingPx: Int,
        val rightMarginPx: Int,
        val bottomMarginPx: Int
    )

    private data class DialogThemeColors(
        val cardBackground: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val primaryButtonBg: Int,
        val primaryButtonText: Int,
        val secondaryButtonBg: Int,
        val secondaryButtonText: Int,
        val buttonBorder: Int
    )

    private data class ExitButtonLayoutSpec(
        val sizePx: Int,
        val topMarginPx: Int,
        val rightMarginPx: Int
    )

    private enum class KeyboardOverlayMode {
        TEXT_ENTRY,
        KEY_CAPTURE
    }

    private enum class KeyboardKeyAction {
        TYPE_TEXT,
        SEND_KEYCODE,
        TOGGLE_SHIFT,
        TOGGLE_SYMBOLS,
        CLOSE
    }

    private data class KeyboardKeySpec(
        val label: String,
        val action: KeyboardKeyAction,
        val text: String? = null,
        val keyCode: Int? = null,
        val weight: Float = 1f
    )

    private data class KeyBindingSpec(
        val label: String,
        val prefsName: String,
        val prefKey: String,
        val defaultKeyCode: Int
    ) {
        fun storageKey(prefix: String): String = prefix + prefKey
    }

    private companion object {
        private const val TAG = "RetroDriveAANative"
        private const val UI_PREFS_NAME = "retrodrive_ui_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}