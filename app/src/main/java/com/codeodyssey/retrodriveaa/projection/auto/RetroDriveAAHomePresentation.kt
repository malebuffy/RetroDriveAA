package com.codeodyssey.retrodriveaa.projection.auto

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.codeodyssey.retrodriveaa.BuildConfig
import com.codeodyssey.retrodriveaa.GameProfileStore
import com.codeodyssey.retrodriveaa.R
import com.codeodyssey.retrodriveaa.RetroDriveDosLaunchHelper
import com.codeodyssey.retrodriveaa.ui.theme.AppThemeMode
import androidx.core.content.res.ResourcesCompat
import java.io.File

class RetroDriveAAHomePresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    interface Listener {
        fun onLaunchDos(gameFolder: String? = null)
        fun onConfigureGame(gameFolder: String)
        fun onUploadFromDevice()
    }

    private enum class Screen {
        MAIN,
        SETTINGS,
        ABOUT
    }

    private data class Palette(
        val background: Int,
        val surface: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val buttonBackground: Int,
        val buttonText: Int,
        val chipBackground: Int,
        val outline: Int,
        val accent: Int
    )

    var listener: Listener? = null

    private val appContext = outerContext.applicationContext
    private val decorLocation = IntArray(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retroTypeface by lazy(LazyThreadSafetyMode.NONE) {
        ResourcesCompat.getFont(appContext, R.font.press_start_2p_regular)
    }

    private lateinit var root: FrameLayout
    private var gameFolders: List<String> = emptyList()
    private var currentScreen: Screen = Screen.MAIN
    private var currentThemeMode: AppThemeMode = AppThemeMode.LIGHT
    private var currentFontScale: Float = 1.0f
    private var selectedGameFolder: String? = null
    private var gameSelectorExpanded: Boolean = false
    private var deleteConfirmationGame: String? = null
    private var transientMainMessage: String? = null
    private val clearTransientMainMessage = Runnable {
        if (transientMainMessage != null) {
            transientMainMessage = null
            if (::root.isInitialized && currentScreen == Screen.MAIN) {
                renderCurrentScreen()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        setContentView(root)
        renderCurrentScreen()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop display=${display.displayId}")
    }

    fun updateGameFolders(folders: List<String>) {
        gameFolders = folders.sortedBy { it.lowercase() }
        syncSelectedGameFolder()
        if (::root.isInitialized && currentScreen == Screen.MAIN) {
            renderCurrentScreen()
        }
    }

    fun injectTouchEvent(event: MotionEvent): Boolean {
        val window = window ?: return false
        val decorView = window.decorView ?: return false
        val translatedEvent = MotionEvent.obtain(event)

        decorView.getLocationOnScreen(decorLocation)
        translatedEvent.offsetLocation(
            -decorLocation[0].toFloat(),
            -decorLocation[1].toFloat()
        )

        val handled = window.superDispatchTouchEvent(translatedEvent) ||
            decorView.dispatchTouchEvent(translatedEvent)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            Log.d(TAG, "injectTouchEvent handled=$handled x=${translatedEvent.x} y=${translatedEvent.y}")
        }
        translatedEvent.recycle()
        return handled
    }

    private fun renderCurrentScreen() {
        currentThemeMode = loadAppThemeMode()
        currentFontScale = loadUiFontScale()
        root.removeAllViews()
        root.setBackgroundColor(palette().background)

        val screenView = when (currentScreen) {
            Screen.MAIN -> buildMainMenuView()
            Screen.SETTINGS -> buildSettingsView()
            Screen.ABOUT -> buildAboutView()
        }
        root.addView(screenView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        renderCurrentScreen()
    }

    private fun buildMainMenuView(): View {
        refreshGamesFromStorage()
        val palette = palette()
        val buttonWidth = primaryButtonWidth()
        val content = centeredContentColumn()

        content.addView(emojiLabel())
        content.addView(spacer(14))
        content.addView(titleLabel("RetrodriveAA Ready"))

        transientMainMessage?.let { message ->
            content.addView(spacer(12))
            content.addView(buildMainBanner(message, buttonWidth))
        }

        if (gameFolders.isNotEmpty()) {
            content.addView(spacer(20))
            content.addView(buildQuickLaunchSelectorSection(buttonWidth))
            content.addView(spacer(20))
        } else {
            content.addView(spacer(18))
            content.addView(bodyLabel(
                text = "No games found. Upload a ZIP from this phone or copy games into the RetrodriveAA game folder.",
                width = buttonWidth,
                textColor = palette.secondaryText
            ))
            content.addView(spacer(20))
        }

        content.addView(buildPrimaryButton(buttonWidth, if (gameFolders.isEmpty()) "Launch DOS Games" else "Enter DOS") {
            listener?.onLaunchDos(null)
        })
        content.addView(spacer(12))

        content.addView(buildPrimaryButton(buttonWidth, "UI Settings") {
            showScreen(Screen.SETTINGS)
        })
        content.addView(spacer(12))

        content.addView(buildPrimaryButton(buttonWidth, "About") {
            showScreen(Screen.ABOUT)
        })

        content.addView(spacer(24))
        content.addView(bodyLabel(
            text = "LEGAL NOTICE: RetrodriveAA does not include any games. Only use software you own or have permission to run.",
            width = (buttonWidth * 1.18f).toInt(),
            textColor = palette.secondaryText
        ))

        return wrapInScroll(content)
    }

    private fun buildSettingsView(): View {
        val palette = palette()
        val buttonWidth = primaryButtonWidth()
        val content = buildSectionScaffold(
            title = "UI Settings",
            subtitle = "These settings write to the same shared preferences as phone mode."
        )

        val fontOptions = listOf(0.8f to "Small", 1.0f to "Normal", 1.35f to "Large")
        val themeOptions = listOf(
            AppThemeMode.LIGHT to "Light",
            AppThemeMode.DARK to "Dark",
            AppThemeMode.DARK_RETRO to "Dark Retro"
        )

        content.addView(sectionHeader("Text Size", buttonWidth))
        content.addView(spacer(8))
        content.addView(buildChoiceRow(buttonWidth, fontOptions.map { option ->
            ChoiceItem(
                label = option.second,
                selected = kotlin.math.abs(currentFontScale - option.first) < 0.01f,
                onClick = {
                    saveUiFontScale(option.first)
                    renderCurrentScreen()
                }
            )
        }))

        content.addView(spacer(20))
        content.addView(sectionHeader("Theme", buttonWidth))
        content.addView(spacer(8))
        content.addView(buildChoiceRow(buttonWidth, themeOptions.map { option ->
            ChoiceItem(
                label = option.second,
                selected = currentThemeMode == option.first,
                onClick = {
                    saveAppThemeMode(option.first)
                    renderCurrentScreen()
                }
            )
        }))

        content.addView(spacer(20))
        content.addView(bodyLabel(
            text = "Current theme: ${themeOptions.first { it.first == currentThemeMode }.second}\nCurrent text size: ${fontOptions.first { kotlin.math.abs(it.first - currentFontScale) < 0.01f }.second}",
            width = buttonWidth,
            textColor = palette.primaryText,
            background = palette.surface,
            strokeColor = palette.outline
        ))

        return wrapInScroll(content)
    }

    private fun buildAboutView(): View {
        val buttonWidth = primaryButtonWidth()
        val content = buildSectionScaffold(
            title = "About",
            subtitle = "RetrodriveAA ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        )

        content.addView(bodyLabel(
            text = "RetrodriveAA runs classic DOS software on Android and shares the same library, save-state, and upload-from-device flow between phone mode and Android Auto.",
            width = buttonWidth
        ))
        content.addView(spacer(16))
        content.addView(sectionHeader("Included Components", buttonWidth))
        content.addView(spacer(8))
        content.addView(bodyLabel("DOSBox Emulator Core", buttonWidth))
        content.addView(spacer(8))
        content.addView(bodyLabel("Simple DirectMedia Layer (SDL)", buttonWidth))
        content.addView(spacer(8))
        content.addView(bodyLabel("Built-in ZIP game importer", buttonWidth))
        content.addView(spacer(16))
        content.addView(bodyLabel(
            text = "No bundled games are included. Import the DOS software you legally own or are allowed to use.",
            width = buttonWidth
        ))
        content.addView(spacer(16))
        content.addView(bodyLabel(
            text = "No affiliation or ownership is claimed over third-party software. Only run games you legally own or are allowed to use.",
            width = buttonWidth
        ))

        return wrapInScroll(content)
    }

    private fun buildSectionScaffold(title: String, subtitle: String): LinearLayout {
        val buttonWidth = primaryButtonWidth()
        return centeredContentColumn().apply {
            addView(buildPrimaryButton(buttonWidth, "Back") { showScreen(Screen.MAIN) })
            addView(spacer(18))
            addView(titleLabel(title))
            addView(spacer(8))
            addView(bodyLabel(subtitle, buttonWidth))
            addView(spacer(20))
        }
    }

    private fun buildQuickLaunchSelectorSection(buttonWidth: Int): View {
        val palette = palette()
        val selectedGame = selectedGameFolder
        val deleteArmed = deleteConfirmationGame == selectedGame && selectedGame != null

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(buttonWidth, WRAP_CONTENT)

            addView(sectionHeader("Quick Launch", buttonWidth))
            addView(spacer(8))
            addView(buildGameSelector(buttonWidth))
            addView(spacer(10))
            addView(buildQuickLaunchActionRow(buttonWidth))
            addView(spacer(8))
            addView(bodyLabel(
                text = if (deleteArmed) {
                    "Tap Confirm again to erase \"$selectedGame\"."
                } else {
                    "Start here or erase the selected game."
                },
                width = buttonWidth,
                textColor = if (deleteArmed) Color.WHITE else palette.secondaryText,
                background = if (deleteArmed) Color.parseColor("#7F1D1D") else palette.surface,
                strokeColor = if (deleteArmed) Color.parseColor("#FCA5A5") else palette.outline
            ))
        }
    }

    private fun buildMainBanner(text: String, width: Int): View {
        val palette = palette()
        return bodyLabel(
            text = text,
            width = width,
            textColor = palette.buttonText,
            background = palette.buttonBackground,
            strokeColor = palette.accent
        )
    }

    private fun buildGameSelector(buttonWidth: Int): View {
        val palette = palette()
        val items = gameFolders
        val selectedGame = selectedGameFolder

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(buttonWidth, WRAP_CONTENT)

            addView(TextView(context).apply {
                text = selectedGame ?: "Choose a game"
                setTextColor(palette.buttonText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(17f))
                typeface = themedTypeface(preferBold = true)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = GradientDrawable().apply {
                    setColor(palette.buttonBackground)
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), if (gameSelectorExpanded) palette.accent else palette.outline)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    gameSelectorExpanded = !gameSelectorExpanded
                    renderCurrentScreen()
                }
                layoutParams = LinearLayout.LayoutParams(buttonWidth, WRAP_CONTENT)
            })

            if (gameSelectorExpanded) {
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
                })

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(palette.buttonBackground)
                        cornerRadius = dp(24).toFloat()
                        setStroke(dp(1), palette.outline)
                    }
                    layoutParams = LinearLayout.LayoutParams(buttonWidth, WRAP_CONTENT)

                    items.forEachIndexed { index, gameName ->
                        addView(buildSelectorOption(gameName, gameName == selectedGame))
                        if (index < items.lastIndex) {
                            addView(View(context).apply {
                                setBackgroundColor(palette.outline)
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    dp(1)
                                ).apply {
                                    marginStart = dp(12)
                                    marginEnd = dp(12)
                                }
                            })
                        }
                    }
                })
            }
        }
    }

    private fun buildSelectorOption(gameName: String, selected: Boolean): TextView {
        val palette = palette()
        return TextView(context).apply {
            text = gameName
            setTextColor(palette.buttonText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(17f))
            typeface = themedTypeface(preferBold = selected)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(palette.buttonBackground)
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat()
                )
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (selectedGameFolder != gameName) {
                    selectedGameFolder = gameName
                    deleteConfirmationGame = null
                }
                gameSelectorExpanded = false
                renderCurrentScreen()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun buildQuickLaunchActionRow(buttonWidth: Int): View {
        val selectedGame = selectedGameFolder
        val deleteArmed = deleteConfirmationGame == selectedGame && selectedGame != null

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(buttonWidth, WRAP_CONTENT)

            addView(buildQuickLaunchControlButton(
                width = buttonWidth,
                label = "Start",
                primary = true,
                destructive = false,
                onClick = { selectedGameFolder?.let { listener?.onLaunchDos(it) } }
            ))
            addView(spacer(10))
            addView(buildQuickLaunchControlButton(
                width = buttonWidth,
                label = if (deleteArmed) "Confirm" else "Erase",
                primary = false,
                destructive = deleteArmed,
                onClick = {
                    val gameName = selectedGameFolder ?: return@buildQuickLaunchControlButton
                    if (deleteConfirmationGame == gameName) {
                        val deleted = deleteSelectedGame(gameName)
                        deleteConfirmationGame = null
                        Toast.makeText(
                            appContext,
                            if (deleted) "\"$gameName\" deleted" else "Failed to delete \"$gameName\"",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        deleteConfirmationGame = gameName
                    }
                    renderCurrentScreen()
                }
            ))
        }
    }

    private fun buildQuickLaunchControlButton(
        width: Int,
        label: String,
        primary: Boolean,
        destructive: Boolean,
        onClick: () -> Unit
    ): TextView {
        val palette = palette()
        val backgroundColor = when {
            destructive -> Color.parseColor("#991B1B")
            else -> palette.buttonBackground
        }
        val textColor = when {
            destructive -> Color.WHITE
            else -> palette.buttonText
        }
        val strokeColor = when {
            destructive -> Color.parseColor("#FCA5A5")
            else -> palette.outline
        }

        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(17f))
            typeface = themedTypeface(preferBold = true)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), strokeColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(width, WRAP_CONTENT)
        }
    }

    private fun deleteSelectedGame(gameName: String): Boolean {
        val gameDir = File(appContext.getExternalFilesDir(null), "game")
        val gameFolder = File(gameDir, gameName)
        if (!gameFolder.exists()) {
            return false
        }

        val deleted = gameFolder.deleteRecursively()
        if (!deleted) {
            return false
        }

        clearGameConfig(gameName)
        GameProfileStore.delete(appContext, gameName)
        refreshGamesFromStorage(force = true)
        return true
    }

    private fun clearGameConfig(gameName: String) {
        runCatching {
            val configUtilsClass = Class.forName("com.dosbox.emu.ConfigUtils")
            val clearMethod = configUtilsClass.getMethod(
                "clearGameConfig",
                Context::class.java,
                String::class.java
            )
            clearMethod.invoke(null, appContext, gameName)
            Log.d(TAG, "Cleared config for game: $gameName")
        }.onFailure {
            Log.e(TAG, "Failed to clear game config for $gameName", it)
        }
    }

    private data class ChoiceItem(
        val label: String,
        val selected: Boolean,
        val onClick: () -> Unit
    )

    private fun buildChoiceRow(width: Int, items: List<ChoiceItem>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        items.forEachIndexed { index, item ->
            row.addView(buildChoiceChip(item).apply {
                if (index > 0) {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
                }
            })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(row, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(width, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }

    private fun buildChoiceChip(item: ChoiceItem): TextView {
        val palette = palette()
        return TextView(context).apply {
            text = item.label
            setTextColor(if (item.selected) palette.buttonText else palette.primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(13f))
            typeface = themedTypeface(preferBold = item.selected)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(if (item.selected) palette.buttonBackground else palette.surface)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), palette.outline)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { item.onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
    }

    private fun wrapInScroll(content: View): View {
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun centeredContentColumn(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(20))
        }
    }

    private fun emojiLabel(): TextView {
        return TextView(context).apply {
            text = "🎮"
            setTextColor(palette().primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(44f))
            gravity = Gravity.CENTER
        }
    }

    private fun titleLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(palette().primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(28f))
            typeface = themedTypeface(preferBold = true)
            gravity = Gravity.CENTER
        }
    }

    private fun sectionHeader(text: String, width: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(palette().primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(15f))
            typeface = themedTypeface(preferBold = true)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(width, WRAP_CONTENT)
        }
    }

    private fun bodyLabel(
        text: String,
        width: Int,
        textColor: Int = palette().secondaryText,
        background: Int? = null,
        strokeColor: Int? = null,
        monospace: Boolean = false
    ): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(14f))
            gravity = Gravity.CENTER
            typeface = themedTypeface(monospace = monospace)
            if (background != null) {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                this.background = GradientDrawable().apply {
                    setColor(background)
                    cornerRadius = dp(18).toFloat()
                    strokeColor?.let { setStroke(dp(1), it) }
                }
            }
            layoutParams = LinearLayout.LayoutParams(width, WRAP_CONTENT)
        }
    }

    private fun buildPrimaryButton(width: Int, label: String, onClick: () -> Unit): TextView {
        val palette = palette()
        return TextView(context).apply {
            text = label
            setTextColor(palette.buttonText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(17f))
            typeface = themedTypeface(preferBold = true)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(palette.buttonBackground)
                cornerRadius = dp(24).toFloat()
                if (palette.outline != Color.TRANSPARENT) {
                    setStroke(dp(1), palette.outline)
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(width, WRAP_CONTENT)
        }
    }

    private fun refreshGamesFromStorage(force: Boolean = false) {
        if (force || gameFolders.isEmpty()) {
            gameFolders = RetroDriveDosLaunchHelper.getGameFolders(appContext).map { it.name }
        }
        syncSelectedGameFolder()
    }

    private fun showMainBannerMessage(message: String, durationMs: Long = 5000L) {
        transientMainMessage = message
        mainHandler.removeCallbacks(clearTransientMainMessage)
        if (::root.isInitialized && currentScreen == Screen.MAIN) {
            renderCurrentScreen()
        }
        mainHandler.postDelayed(clearTransientMainMessage, durationMs)
    }

    private fun syncSelectedGameFolder() {
        selectedGameFolder = when {
            gameFolders.isEmpty() -> null
            selectedGameFolder in gameFolders -> selectedGameFolder
            else -> gameFolders.first()
        }
        if (selectedGameFolder == null) {
            gameSelectorExpanded = false
        }
        if (deleteConfirmationGame !in gameFolders) {
            deleteConfirmationGame = null
        }
    }

    private fun primaryButtonWidth(): Int {
        return (context.resources.displayMetrics.widthPixels * 0.60f).toInt().coerceAtLeast(dp(240))
    }

    private fun palette(): Palette {
        return when (currentThemeMode) {
            AppThemeMode.LIGHT -> Palette(
                background = Color.parseColor("#1B5E20"),
                surface = Color.parseColor("#245F29"),
                primaryText = Color.WHITE,
                secondaryText = Color.parseColor("#D0E6D2"),
                buttonBackground = Color.WHITE,
                buttonText = Color.BLACK,
                chipBackground = Color.parseColor("#2E7D32"),
                outline = Color.parseColor("#E8F5E9"),
                accent = Color.WHITE
            )
            AppThemeMode.DARK -> Palette(
                background = Color.parseColor("#0F172A"),
                surface = Color.parseColor("#1E293B"),
                primaryText = Color.parseColor("#F8FAFC"),
                secondaryText = Color.parseColor("#CBD5E1"),
                buttonBackground = Color.parseColor("#111827"),
                buttonText = Color.parseColor("#F8FAFC"),
                chipBackground = Color.parseColor("#1F2937"),
                outline = Color.parseColor("#CBD5E1"),
                accent = Color.parseColor("#93C5FD")
            )
            AppThemeMode.DARK_RETRO -> Palette(
                background = Color.parseColor("#081A08"),
                surface = Color.parseColor("#102710"),
                primaryText = Color.parseColor("#39FF14"),
                secondaryText = Color.parseColor("#A9FF96"),
                buttonBackground = Color.BLACK,
                buttonText = Color.parseColor("#39FF14"),
                chipBackground = Color.parseColor("#0E330E"),
                outline = Color.parseColor("#39FF14"),
                accent = Color.parseColor("#39FF14")
            )
        }
    }

    private fun loadUiFontScale(): Float {
        val prefs = appContext.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
        val steps = listOf(0.8f, 1.0f, 1.35f)
        return steps.minByOrNull { kotlin.math.abs(it - saved) } ?: 1.0f
    }

    private fun saveUiFontScale(scale: Float) {
        val steps = listOf(0.8f, 1.0f, 1.35f)
        val snapped = steps.minByOrNull { kotlin.math.abs(it - scale) } ?: 1.0f
        appContext.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FONT_SCALE, snapped)
            .apply()
    }

    private fun loadAppThemeMode(): AppThemeMode {
        val prefs = appContext.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME_MODE, AppThemeMode.LIGHT.name) ?: AppThemeMode.LIGHT.name
        return AppThemeMode.entries.firstOrNull { it.name == saved } ?: AppThemeMode.LIGHT
    }

    private fun saveAppThemeMode(themeMode: AppThemeMode) {
        appContext.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, themeMode.name)
            .apply()
    }

    private fun scaledSp(baseSp: Float): Float = baseSp * currentFontScale

    private fun themedTypeface(
        preferBold: Boolean = false,
        monospace: Boolean = false
    ): Typeface {
        if (monospace) {
            return Typeface.MONOSPACE
        }

        if (currentThemeMode == AppThemeMode.DARK_RETRO) {
            return retroTypeface ?: Typeface.DEFAULT
        }

        return if (preferBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun spacer(heightDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(heightDp))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private companion object {
        private const val TAG = "RetroDriveAAHome"
        private const val UI_PREFS_NAME = "retrodrive_ui_prefs"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}