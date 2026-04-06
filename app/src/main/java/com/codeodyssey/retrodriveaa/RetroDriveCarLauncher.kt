package com.codeodyssey.retrodriveaa

import android.Manifest
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Display
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.codeodyssey.retrodriveaa.BuildConfig
import com.codeodyssey.retrodriveaa.projection.auto.RetroDriveProjectedNavigation
import com.codeodyssey.retrodriveaa.ui.theme.AppThemeMode
import com.codeodyssey.retrodriveaa.ui.theme.RetroDriveTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * RetroDriveCarLauncher: Automotive Safety Gate
 * 
 * This activity serves as the entry point for the RetroDrive app on Android Automotive OS.
 * It enforces Google's safety requirements by checking the vehicle's driving state:
 * 
 * - IF PARKED: Launches the DOSBox emulator activity
 * - IF DRIVING: Shows a red safety lock screen
 * 
 * This approach keeps the legacy DOSBox code completely untouched while adding
 * a compliance layer on top.
 */
open class RetroDriveCarLauncher : ComponentActivity() {

    private data class AppButtonStyle(
        val containerColor: Color,
        val contentColor: Color,
        val border: BorderStroke?
    )

    @Composable
    private fun themedButtonStyle(): AppButtonStyle {
        val borderWidth = (1f / uiFontScale).coerceIn(0.6f, 1f).dp
        return when (appThemeMode) {
            AppThemeMode.LIGHT -> AppButtonStyle(
                containerColor = Color.White,
                contentColor = Color.Black,
                border = null
            )
            AppThemeMode.DARK -> AppButtonStyle(
                containerColor = Color.Black,
                contentColor = Color.White,
                border = BorderStroke(borderWidth, Color.White)
            )
            AppThemeMode.DARK_RETRO -> AppButtonStyle(
                containerColor = Color.Black,
                contentColor = Color(0xFF39FF14),
                border = BorderStroke(borderWidth, Color(0xFF39FF14))
            )
        }
    }

    private data class DialogColors(
        val containerColor: Color,
        val titleColor: Color,
        val textColor: Color,
        val secondaryTextColor: Color,
        val accentColor: Color,
        val destructiveColor: Color,
        val textButtonColor: Color
    )

    @Composable
    private fun themedDialogColors(): DialogColors {
        return when (appThemeMode) {
            AppThemeMode.LIGHT -> DialogColors(
                containerColor = Color.White,
                titleColor = Color(0xFF1F2937),
                textColor = Color(0xFF374151),
                secondaryTextColor = Color(0xFF6B7280),
                accentColor = Color(0xFF1B5E20),
                destructiveColor = Color(0xFFDC2626),
                textButtonColor = Color(0xFF1B5E20)
            )
            AppThemeMode.DARK -> DialogColors(
                containerColor = Color(0xFF1A212D),
                titleColor = Color(0xFFE5E7EB),
                textColor = Color(0xFFCDD0D5),
                secondaryTextColor = Color(0xFF9CA3AF),
                accentColor = Color(0xFF60A5FA),
                destructiveColor = Color(0xFFEF4444),
                textButtonColor = Color(0xFFE5E7EB)
            )
            AppThemeMode.DARK_RETRO -> DialogColors(
                containerColor = Color(0xFF11161C),
                titleColor = Color(0xFF39FF14),
                textColor = Color(0xFF39FF14),
                secondaryTextColor = Color(0xFF39FF14).copy(alpha = 0.7f),
                accentColor = Color(0xFF39FF14),
                destructiveColor = Color(0xFFFF3131),
                textButtonColor = Color(0xFF39FF14)
            )
        }
    }

    private val dialogShape = RoundedCornerShape(20.dp)

    @Composable
    fun GameDropdownSelector(
        gameFolders: List<File>,
        onGameSelected: (String) -> Unit,
        onGameConfigured: (String) -> Unit,
        onGameDeleted: () -> Unit,
        scale: Float = 1f
    ) {
        var expanded by remember { mutableStateOf(false) }
        var selectedGame by remember { mutableStateOf<String?>(null) }
        var showDeleteDialog by remember { mutableStateOf(false) }
        val buttonStyle = themedButtonStyle()
        val headerTextColor = if (appThemeMode == AppThemeMode.LIGHT) Color.White else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
        val compactFactor = (1f / uiFontScale).coerceIn(0.78f, 1f)
        val dropDownButtonMinHeight = ((56f * scale) * compactFactor).coerceAtLeast(40f * scale).dp
        val actionButtonMinHeight = ((48f * scale) * compactFactor).coerceAtLeast(36f * scale).dp
        val actionGap = ((12f * scale) * compactFactor).coerceAtLeast(1f).dp

        Column(
            modifier = Modifier.fillMaxWidth(0.5f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Quick Launch Game",
                fontSize = (16 * scale).sp,
                fontWeight = FontWeight.Bold,
                color = headerTextColor
            )
            Spacer(modifier = Modifier.height((8 * scale).dp))
            
            // Dropdown button
            Button(
                onClick = { expanded = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonStyle.containerColor,
                    contentColor = buttonStyle.contentColor
                ),
                border = buttonStyle.border,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dropDownButtonMinHeight)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedGame?.let { "🎮 $it" } ?: "Select a game...",
                        fontSize = (16 * scale).sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "▼",
                        fontSize = (16 * scale).sp
                    )
                }
            }
            
            // Dropdown menu
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                gameFolders.forEach { folder ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "🎮 ${folder.name}",
                                fontSize = (14 * scale).sp
                            )
                        },
                        onClick = {
                            selectedGame = folder.name
                            expanded = false
                        }
                    )
                }
            }
            
            // Action buttons (Start, Configure, Delete)
            if (selectedGame != null) {
                Spacer(modifier = Modifier.height(actionGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(actionGap)
                ) {
                    // Start button
                    Button(
                        onClick = { selectedGame?.let { onGameSelected(it) } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonStyle.containerColor,
                            contentColor = buttonStyle.contentColor
                        ),
                        border = buttonStyle.border,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = actionButtonMinHeight)
                    ) {
                        Text(
                            text = "▶ Start",
                            fontSize = (16 * scale).sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Configure button
                    Button(
                        onClick = { selectedGame?.let { onGameConfigured(it) } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonStyle.containerColor,
                            contentColor = buttonStyle.contentColor
                        ),
                        border = buttonStyle.border,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = actionButtonMinHeight)
                    ) {
                        Text(
                            text = "⚙ Config",
                            fontSize = (16 * scale).sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    // Delete button
                    Button(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = buttonStyle.containerColor,
                            contentColor = buttonStyle.contentColor
                        ),
                        border = buttonStyle.border,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = actionButtonMinHeight)
                    ) {
                        Text(
                            text = "🗑 Erase",
                            fontSize = (16 * scale).sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        
        // Delete confirmation dialog
        if (showDeleteDialog && selectedGame != null) {
            val dColors = themedDialogColors()
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                shape = dialogShape,
                containerColor = dColors.containerColor,
                titleContentColor = dColors.titleColor,
                textContentColor = dColors.textColor,
                title = {
                    Text(
                        text = "Delete Game?",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text("Are you sure you want to delete \"$selectedGame\"? This will permanently remove the game folder and all its files.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            selectedGame?.let { gameName ->
                                val gameDir = File(getExternalFilesDir(null), "game")
                                val gameFolder = File(gameDir, gameName)
                                if (gameFolder.exists()) {
                                    val deleted = gameFolder.deleteRecursively()
                                    if (deleted) {
                                        // Clear game-specific configuration
                                        try {
                                            val configUtilsClass = Class.forName("com.dosbox.emu.ConfigUtils")
                                            val clearMethod = configUtilsClass.getMethod("clearGameConfig", 
                                                android.content.Context::class.java, String::class.java)
                                            clearMethod.invoke(null, this@RetroDriveCarLauncher, gameName)
                                            android.util.Log.d("RetroDriveCarLauncher", "Cleared config for game: $gameName")
                                        } catch (e: Exception) {
                                            android.util.Log.e("RetroDriveCarLauncher", "Failed to clear game config: ${e.message}", e)
                                        }

                                        GameProfileStore.delete(this@RetroDriveCarLauncher, gameName)
                                        
                                        Toast.makeText(
                                            this@RetroDriveCarLauncher,
                                            "\"$gameName\" deleted successfully",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        selectedGame = null
                                        onGameDeleted()
                                    } else {
                                        Toast.makeText(
                                            this@RetroDriveCarLauncher,
                                            "Failed to delete \"$gameName\"",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            showDeleteDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dColors.destructiveColor,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = dColors.textButtonColor)
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    companion object {
        private const val TAG = "RetroDriveCarLauncher"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val DEVICE_UPLOAD_REQUEST_CODE = 101
        private const val PARKED_CHECK_INTERVAL_MS = 2000L // Check every 2 seconds
        private const val RELAY_POLL_INTERVAL_MS = 2500L
        private const val RELAY_HTTP_TIMEOUT_SECONDS = 300L
        private const val UI_PREFS_NAME = "retrodrive_ui_prefs"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_THEME_MODE = "theme_mode"

        const val EXTRA_OPEN_GAME_CONFIG_ID = "com.codeodyssey.retrodriveaa.extra.OPEN_GAME_CONFIG_ID"

        private fun createPhoneLauncherIntent(context: Context): Intent {
            return (context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    setClass(context, MainActivity::class.java)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }).apply {
                setClass(context, MainActivity::class.java)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        }

        fun createPhoneGameConfigIntent(context: Context, gameId: String): Intent {
            return createPhoneLauncherIntent(context).apply {
                putExtra(RetroDriveProjectedNavigation.EXTRA_PROJECTED_MODE, true)
                putExtra(EXTRA_OPEN_GAME_CONFIG_ID, gameId)
            }
        }

        fun createPhoneTransferIntent(context: Context): Intent {
            return createPhoneLauncherIntent(context)
        }

        fun createPhoneDosIntent(context: Context, gameId: String?): Intent {
            return createPhoneLauncherIntent(context).apply {
                putExtra(RetroDriveProjectedNavigation.EXTRA_PROJECTED_MODE, true)
                putExtra(RetroDriveProjectedNavigation.EXTRA_AUTO_LAUNCH_DOS, true)
                gameId?.let { putExtra(RetroDriveProjectedNavigation.EXTRA_AUTO_LAUNCH_GAME_ID, it) }
                putExtra(RetroDriveProjectedNavigation.EXTRA_FINISH_AFTER_DOS_LAUNCH, true)
            }
        }
    }

    private data class RelaySession(
        val transferId: String,
        val token: String,
        val initUrl: String,
        val uploadUrl: String,
        val statusUrl: String,
        val downloadUrl: String
    )

    private var isCheckingParkedState by mutableStateOf(false)
    private var isDriving by mutableStateOf(true) // Default to safe (locked) state
    private var wifiServer: WifiTransferServer? = null
    private var isServerRunning by mutableStateOf(false)
    private var isPurchased by mutableStateOf(true)
    private var uiFontScale by mutableStateOf(1.0f)
    private var appThemeMode by mutableStateOf(AppThemeMode.LIGHT)
    private var showTrialOverlay by mutableStateOf(false)
    private var showPaywallOverlay by mutableStateOf(false)
    private var projectedModeEnabled by mutableStateOf(false)
    private var pendingProjectedSection by mutableStateOf<String?>(null)
    private var pendingGameConfigId by mutableStateOf<String?>(null)
    private var pendingProjectedDosLaunch by mutableStateOf(false)
    private var pendingProjectedDosGameId by mutableStateOf<String?>(null)
    private var finishAfterProjectedDosLaunch by mutableStateOf(false)
    private var stripePurchaseLauncher: ActivityResultLauncher<Intent>? = null
    /** Incremented when the game library changes, triggering a list refresh. */
    private var libraryRefreshSignal by mutableStateOf(0)
    protected open val allowNonCarDevice: Boolean = true
    protected open val bypassSafetyGate: Boolean = false
    private val relayHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(RELAY_HTTP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(RELAY_HTTP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(RELAY_HTTP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun createRelaySession(baseUrl: String): RelaySession {
        val transferId = UUID.randomUUID().toString().replace("-", "")
        val token = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "")
        return RelaySession(
            transferId = transferId,
            token = token,
            initUrl = "$baseUrl/init.php?id=$transferId&token=$token",
            uploadUrl = "$baseUrl/upload.php?id=$transferId&token=$token",
            statusUrl = "$baseUrl/status.php?id=$transferId&token=$token",
            downloadUrl = "$baseUrl/download.php?id=$transferId&token=$token"
        )
    }
    
    /**
     * Get list of game folders from the game directory
     */
    private fun getGameFolders(): List<File> {
        return RetroDriveDosLaunchHelper.getGameFolders(this)
    }
    
    /**
     * Launch DOSBox with optional game folder to open
     */
    private fun launchDOSBox(gameFolder: String? = null) {
        launchDOSBox(gameFolder = gameFolder, useDisplayOptions = true, finishAfterLaunch = false)
    }

    private fun launchDOSBox(
        gameFolder: String? = null,
        useDisplayOptions: Boolean,
        finishAfterLaunch: Boolean
    ) {
        android.util.Log.d(
            TAG,
            "launchDOSBox game='${gameFolder ?: ""}' display=${display?.displayId} useDisplayOptions=$useDisplayOptions finishAfterLaunch=$finishAfterLaunch"
        )
        RetroDriveDosLaunchHelper.launchDosBox(
            context = this,
            gameFolder = gameFolder,
            useDisplayOptions = useDisplayOptions
        )
        if (finishAfterLaunch) {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchDeviceUpload() {
        val intent = DeviceUploadActivity.createIntent(this)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)

        runCatching {
            startActivityForResult(intent, DEVICE_UPLOAD_REQUEST_CODE, options.toBundle())
        }.onFailure {
            android.util.Log.w(TAG, "Failed to launch upload picker on default display, retrying without display override", it)
            runCatching {
                startActivityForResult(intent, DEVICE_UPLOAD_REQUEST_CODE)
            }.onFailure { fallbackError ->
                android.util.Log.e(TAG, "Failed to launch device upload picker", fallbackError)
            }
        }
    }

    private fun applyLaunchIntent(intent: Intent?) {
        val extraProjectedMode =
            intent?.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_PROJECTED_MODE, false) == true
        val extraSection = intent?.getStringExtra(RetroDriveProjectedNavigation.EXTRA_OPEN_SECTION)
        val extraGameConfigId = intent?.getStringExtra(EXTRA_OPEN_GAME_CONFIG_ID)
        val extraAutoLaunchDos =
            intent?.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_AUTO_LAUNCH_DOS, false) == true
        val extraAutoLaunchGameId =
            intent?.getStringExtra(RetroDriveProjectedNavigation.EXTRA_AUTO_LAUNCH_GAME_ID)
        val extraFinishAfterDosLaunch =
            intent?.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_FINISH_AFTER_DOS_LAUNCH, false) == true

        if (extraProjectedMode || extraSection != null || extraGameConfigId != null || extraAutoLaunchDos) {
            projectedModeEnabled = extraProjectedMode
            pendingProjectedSection = extraSection
            pendingGameConfigId = extraGameConfigId
            pendingProjectedDosLaunch = extraAutoLaunchDos
            pendingProjectedDosGameId = extraAutoLaunchGameId
            finishAfterProjectedDosLaunch = extraFinishAfterDosLaunch
            android.util.Log.d(
                TAG,
                "applyLaunchIntent extras projected=$projectedModeEnabled section=$pendingProjectedSection config=$pendingGameConfigId autoDos=$pendingProjectedDosLaunch game=$pendingProjectedDosGameId finishAfterDos=$finishAfterProjectedDosLaunch action=${intent?.action}"
            )
            return
        }

        val pendingLaunch = RetroDriveProjectedNavigation.consumePendingLaunch(this)
        projectedModeEnabled = pendingLaunch?.projectedMode == true
        pendingProjectedSection = pendingLaunch?.section
        pendingGameConfigId = pendingLaunch?.gameConfigId
        pendingProjectedDosLaunch = pendingLaunch?.autoLaunchDos == true
        pendingProjectedDosGameId = pendingLaunch?.gameId
        finishAfterProjectedDosLaunch = pendingLaunch?.finishAfterDosLaunch == true
        android.util.Log.d(
            TAG,
            "applyLaunchIntent pending projected=$projectedModeEnabled section=$pendingProjectedSection config=$pendingGameConfigId autoDos=$pendingProjectedDosLaunch game=$pendingProjectedDosGameId finishAfterDos=$finishAfterProjectedDosLaunch action=${intent?.action}"
        )
    }

    private fun shouldBypassSafetyGate(): Boolean {
        return bypassSafetyGate || projectedModeEnabled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLaunchIntent(intent)
        android.util.Log.d(
            TAG,
            "onCreate display=${display?.displayId} projected=$projectedModeEnabled section=$pendingProjectedSection config=$pendingGameConfigId autoDos=$pendingProjectedDosLaunch game=$pendingProjectedDosGameId finishAfterDos=$finishAfterProjectedDosLaunch action=${intent?.action}"
        )

        if (shouldBypassSafetyGate()) {
            isDriving = false
            isCheckingParkedState = false
        }

        TrialModeConfig.ensureTrialStateInitialized(this)
        isPurchased = TrialModeConfig.isPurchased(this)

        stripePurchaseLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                TrialModeConfig.setPurchased(this, true)
                isPurchased = true
                showPaywallOverlay = false
                Toast.makeText(this, "Purchase completed. Full version unlocked.", Toast.LENGTH_LONG).show()
            }
        }

        // Check and request permissions
        checkPermissions()

        // Initialize WiFi server
        wifiServer = WifiTransferServer(this, 8080)
        // Set static reference for DOSBoxActivity to allow stopping from anywhere
        try {
            val dosBoxActivityClass = Class.forName("com.dosbox.emu.DOSBoxActivity")
            val field = dosBoxActivityClass.getField("wifiTransferServerInstance")
            field.set(null, wifiServer)
            android.util.Log.i("RetroDriveCarLauncher", "Set wifiTransferServerInstance reference")
        } catch (e: Exception) {
            android.util.Log.e("RetroDriveCarLauncher", "Failed to set wifiTransferServerInstance: ${e.message}", e)
        }

        uiFontScale = loadUiFontScale()
        appThemeMode = loadAppThemeMode()

        setContent {
            val baseDensity = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * uiFontScale)
            ) {
                RetroDriveTheme(themeMode = appThemeMode) {
                    SafetyGateScreen()
                }
            }
        }

        initializeTrialGate()

        // Start monitoring parked state
        startParkedStateMonitoring()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
        android.util.Log.d(
            TAG,
            "onNewIntent display=${display?.displayId} projected=$projectedModeEnabled section=$pendingProjectedSection config=$pendingGameConfigId autoDos=$pendingProjectedDosLaunch game=$pendingProjectedDosGameId finishAfterDos=$finishAfterProjectedDosLaunch action=${intent.action}"
        )
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == DEVICE_UPLOAD_REQUEST_CODE && resultCode == RESULT_OK) {
            libraryRefreshSignal++
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop WiFi server when activity is destroyed
        wifiServer?.stop()
    }

    private fun initializeTrialGate() {
        if (!TrialModeConfig.TRIAL_MODE_ENABLED || isPurchased) {
            showTrialOverlay = false
            showPaywallOverlay = false
            return
        }
        val hasDismissedTrial = TrialModeConfig.hasDismissedTrial(this)

        showTrialOverlay = !hasDismissedTrial
        showPaywallOverlay = false
    }

    private fun processPurchase() {
        if (isNetworkAvailable()) {
            stripePurchaseLauncher?.launch(Intent(this, StripePaymentActivity::class.java))
        } else {
            Toast.makeText(this, "You need an active network to start the payment.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Composable
    fun SafetyGateScreen() {
        var showAbout by remember { mutableStateOf(false) }
        var showUiSettings by remember { mutableStateOf(false) }
        var showGameConfig by remember { mutableStateOf(false) }
        var selectedGameForConfig by remember { mutableStateOf<String?>(null) }
        var gameFolders by remember { mutableStateOf(getGameFolders()) }
        val isAutomotiveDevice = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val useAutoMessaging = isAutomotiveDevice || projectedModeEnabled
        val buttonStyle = themedButtonStyle()
        val mainMenuTextColor = if (appThemeMode == AppThemeMode.LIGHT) Color.White else MaterialTheme.colorScheme.onBackground

        // Refresh game list when demo install completes
        LaunchedEffect(libraryRefreshSignal) {
            if (libraryRefreshSignal > 0) gameFolders = getGameFolders()
        }

        LaunchedEffect(pendingProjectedSection) {
            when (pendingProjectedSection) {
                RetroDriveProjectedNavigation.SECTION_TRANSFER -> launchDeviceUpload()

                RetroDriveProjectedNavigation.SECTION_SETTINGS -> showUiSettings = true
                RetroDriveProjectedNavigation.SECTION_ABOUT -> showAbout = true
            }
            pendingProjectedSection = null
        }

        LaunchedEffect(pendingGameConfigId) {
            pendingGameConfigId?.let { gameId ->
                selectedGameForConfig = gameId
                showGameConfig = true
                pendingGameConfigId = null
            }
        }

        LaunchedEffect(pendingProjectedDosLaunch, pendingProjectedDosGameId, finishAfterProjectedDosLaunch) {
            if (pendingProjectedDosLaunch) {
                val gameId = pendingProjectedDosGameId?.takeUnless { it.isBlank() }
                val finishAfterLaunch = finishAfterProjectedDosLaunch
                android.util.Log.d(
                    TAG,
                    "Launching projected DOS game='${gameId ?: ""}' display=${display?.displayId} finishAfter=$finishAfterLaunch"
                )
                pendingProjectedDosLaunch = false
                pendingProjectedDosGameId = null
                finishAfterProjectedDosLaunch = false
                launchDOSBox(
                    gameFolder = gameId,
                    useDisplayOptions = false,
                    finishAfterLaunch = finishAfterLaunch
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (isDriving) {
                Color(0xFF8B0000)
            } else {
                if (appThemeMode == AppThemeMode.LIGHT) Color(0xFF1B5E20) else MaterialTheme.colorScheme.background
            }
        ) {
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                val maxH = maxHeight
                val maxW = maxWidth
                // Shrink content if height is limited
                val scaleRaw = (maxH.value / 700f).coerceAtMost(maxW.value / 400f)
                val scale = scaleRaw.coerceIn(0.6f, 1f) // Clamp to avoid too-large text on ultra-wide/short screens
                // Compact vertical chrome when font grows; don't enlarge spacing for small/normal
                val compactFactor = (1f / uiFontScale).coerceIn(0.78f, 1f)
                val gSmall  = ((12f * scale) * compactFactor).coerceAtLeast(1f).dp
                val gMid    = ((16f * scale) * compactFactor).coerceAtLeast(1f).dp
                val gLarge  = ((24f * scale) * compactFactor).coerceAtLeast(1f).dp
                val gXLarge = ((32f * scale) * compactFactor).coerceAtLeast(1f).dp
                val colPad  = ((24f * scale) * compactFactor).coerceIn(2f, 24f * scale).dp
                val mainButtonMinHeight = ((56f * scale) * compactFactor).coerceAtLeast(40f * scale).dp
                // Emoji uses dp-equivalent size so it doesn't compound with fontScale
                val emojiSizeSp = with(androidx.compose.ui.platform.LocalDensity.current) { (56 * scale).dp.toSp() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxH)
                        .verticalScroll(rememberScrollState())
                        .padding(colPad),
                    contentAlignment = Alignment.Center
                ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (isDriving) {
                        // DRIVING MODE: Safety Lock Screen
                        Text(
                            text = "🔒",
                            fontSize = emojiSizeSp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(gLarge))
                        Text(
                            text = "RetrodriveAA Locked",
                            fontSize = (32 * scale).sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(gMid))
                        Text(
                            text = "For your safety, DOS games are only available when parked.",
                            fontSize = (18 * scale).sp,
                            color = Color.White.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(gXLarge))
                        if (isCheckingParkedState) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(gMid))
                            Text(
                                text = "Checking vehicle status...",
                                fontSize = (14 * scale).sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        // PARKED MODE: Ready to launch
                        Text(
                            text = "🎮",
                            fontSize = emojiSizeSp,
                            color = mainMenuTextColor
                        )
                        Spacer(modifier = Modifier.height(gLarge))
                        Text(
                            text = if (useAutoMessaging) "RetrodriveAA Ready" else "RetrodriveAA",
                            fontSize = (32 * scale).sp,
                            fontWeight = FontWeight.Bold,
                            color = mainMenuTextColor,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(gMid))
                        Text(
                            text = if (useAutoMessaging) {
                                "Vehicle is parked. Safe to play!"
                            } else {
                                "Phone mode uses the same RetrodriveAA library and settings as Android Auto."
                            },
                            fontSize = (18 * scale).sp,
                            color = mainMenuTextColor.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(gXLarge))

                        // Game Selection Section
                        if (gameFolders.isNotEmpty()) {
                            // Game dropdown selector
                            GameDropdownSelector(
                                gameFolders = gameFolders,
                                onGameSelected = { gameFolder -> launchDOSBox(gameFolder) },
                                onGameConfigured = { gameFolder ->
                                    selectedGameForConfig = gameFolder
                                    showGameConfig = true
                                },
                                onGameDeleted = {
                                    // Refresh game folder list after deletion
                                    gameFolders = getGameFolders()
                                },
                                scale = scale
                            )
                            Spacer(modifier = Modifier.height(gMid))
                        }


                        // Launch DOSBox Button (browse mode)
                        Button(
                            onClick = { launchDOSBox() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonStyle.containerColor,
                                contentColor = buttonStyle.contentColor
                            ),
                            border = buttonStyle.border,
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .heightIn(min = mainButtonMinHeight)
                        ) {
                            Text(
                                text = if (gameFolders.isEmpty()) "Launch DOS Games" else "Enter DOS",
                                fontSize = (18 * scale).sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(gMid))

                        // WiFi Transfer Button (identical layout)
                        Button(
                            onClick = { launchDeviceUpload() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonStyle.containerColor,
                                contentColor = buttonStyle.contentColor
                            ),
                            border = buttonStyle.border,
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .heightIn(min = mainButtonMinHeight)
                        ) {
                            Text(
                                text = "Upload from Device",
                                fontSize = (18 * scale).sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(gMid))

                        Button(
                            onClick = { showUiSettings = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonStyle.containerColor,
                                contentColor = buttonStyle.contentColor
                            ),
                            border = buttonStyle.border,
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .heightIn(min = mainButtonMinHeight)
                        ) {
                            Text(
                                text = "UI Settings",
                                fontSize = (18 * scale).sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(gMid))

                        // About Button (always last)
                        Button(
                            onClick = { showAbout = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonStyle.containerColor,
                                contentColor = buttonStyle.contentColor
                            ),
                            border = buttonStyle.border,
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .heightIn(min = mainButtonMinHeight)
                        ) {
                            Text(
                                text = "About",
                                fontSize = (18 * scale).sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (gameFolders.isEmpty()) {
                            Spacer(modifier = Modifier.height(gSmall))
                            Text(
                                text = "No games found. Upload a ZIP from this device.",
                                fontSize = (14 * scale).sp,
                                color = mainMenuTextColor.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                        
                        // Legal Disclaimer
                        Spacer(modifier = Modifier.height(gLarge))
                        Text(
                            text = "LEGAL NOTICE: This application does not include any games. Users are solely responsible for ensuring they have the legal right to use any software they run. Only use games you own or have permission to play.",
                            fontSize = (12 * scale).sp,
                            color = mainMenuTextColor.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            lineHeight = (14 * scale).sp,
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                    }  // else
                }  // inner Column
                }  // Box
            }  // BoxWithConstraints

            // About Dialog (if requested)
            if (showAbout) {
                AboutDialog(
                    onDismiss = { showAbout = false }
                )
            }

            if (showUiSettings) {
                UiSettingsDialog(
                    onDismiss = { showUiSettings = false }
                )
            }

            // Game Config Dialog (if requested)
            if (showGameConfig && selectedGameForConfig != null) {
                GameConfigDialog(
                    gameId = selectedGameForConfig!!,
                    onDismiss = { showGameConfig = false },
                    onSaved = { showGameConfig = false }
                )
            }
        }
    }
    
    @Composable
    fun WifiServerDialog(onDismiss: () -> Unit) {
        val dColors = themedDialogColors()
        // Auto-start server when dialog opens
        LaunchedEffect(Unit) {
            if (!isServerRunning) {
                wifiServer?.start()
                isServerRunning = true
            }
        }

        val relayBaseUrl = remember {
            BuildConfig.WIFI_UPLOAD_BASE_URL
                .trim()
                .trimEnd('/')
                .ifBlank { BuildConfig.RELAY_BASE_URL.trim().trimEnd('/') }
        }
        val relayConfigured = remember(relayBaseUrl) {
            relayBaseUrl.isNotBlank() &&
                relayBaseUrl.startsWith("http") &&
                !relayBaseUrl.contains("your-free-php-host.example.com")
        }
        val relaySession = remember(relayConfigured, relayBaseUrl) {
            if (relayConfigured) createRelaySession(relayBaseUrl) else null
        }
        var relayStatusText by remember(relaySession?.transferId) {
            mutableStateOf(
                if (relaySession != null) {
                    "Waiting for upload on relay page..."
                } else {
                    "Relay is not configured. WIFI_UPLOAD_BASE_URL='$relayBaseUrl'"
                }
            )
        }
        var relayImported by remember(relaySession?.transferId) { mutableStateOf(false) }

        var reachableIps by remember { mutableStateOf(wifiServer?.getReachableIps() ?: listOf("0.0.0.0")) }

        LaunchedEffect(isServerRunning) {
            if (isServerRunning) {
                while (true) {
                    reachableIps = wifiServer?.getReachableIps() ?: listOf("0.0.0.0")
                    delay(1500)
                }
            }
        }

        LaunchedEffect(isServerRunning, relaySession?.transferId) {
            if (!isServerRunning || relaySession == null) return@LaunchedEffect

            val initialized = initializeRelaySession(relaySession.initUrl, BuildConfig.RELAY_INIT_KEY)
            if (!initialized) {
                relayStatusText = "Relay session initialization failed."
                return@LaunchedEffect
            }

            while (!relayImported) {
                val status = fetchRelayStatus(relaySession.statusUrl)
                if (status != null) {
                    val (state, filename) = status
                    when (state) {
                        "ready" -> {
                            relayStatusText = "File detected on relay. Downloading to car..."
                            val importResult = downloadAndImportFromRelay(
                                downloadUrl = relaySession.downloadUrl,
                                fallbackFilename = filename
                            )
                            relayStatusText = importResult.message
                            if (importResult.success) {
                                relayImported = true
                                Toast.makeText(
                                    this@RetroDriveCarLauncher,
                                    "Relay transfer complete",
                                    Toast.LENGTH_SHORT
                                ).show()
                                wifiServer?.stop()
                                isServerRunning = false
                                onDismiss()
                                return@LaunchedEffect
                            } else {
                                relayImported = true
                                Toast.makeText(
                                    this@RetroDriveCarLauncher,
                                    importResult.message,
                                    Toast.LENGTH_LONG
                                ).show()
                                wifiServer?.stop()
                                isServerRunning = false
                                onDismiss()
                                return@LaunchedEffect
                            }
                        }

                        "uploading" -> relayStatusText = "Upload in progress..."
                        "consumed" -> relayStatusText = "Transfer already consumed. Start a new session."
                        "waiting" -> relayStatusText = "Waiting for upload on relay page..."
                        else -> relayStatusText = "Relay status: $state"
                    }
                } else {
                    relayStatusText = "Unable to reach relay status endpoint."
                }
                delay(RELAY_POLL_INTERVAL_MS)
            }
        }
        
        val serverIp = reachableIps.firstOrNull() ?: "0.0.0.0"
        val serverPort = remember { wifiServer?.getPort() ?: 8080 }
        val isEmulator = serverIp == "emulator" || serverIp == "localhost"
        val isLikelyLanReachable = remember(serverIp) {
            wifiServer?.isLikelyLanReachableIp(serverIp) ?: false
        }
        val serverUrl = if (isEmulator) {
            "localhost:$serverPort (use adb forward)"
        } else {
            "http://$serverIp:$serverPort"
        }
        val relayUploadUrl = relaySession?.uploadUrl
        val qrTargetUrl = relayUploadUrl ?: if (isEmulator) "http://localhost:$serverPort" else serverUrl
        
        // Generate QR code bitmap
        val qrBitmap = remember(qrTargetUrl, isServerRunning) {
            if (isServerRunning) {
                generateQRCode(qrTargetUrl, 300, 300)
            } else {
                null
            }
        }
        
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = dialogShape,
            containerColor = dColors.containerColor,
            titleContentColor = dColors.titleColor,
            textContentColor = dColors.textColor,
            title = {
                Text(
                    text = "Remote File Transfer",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!isLikelyLanReachable) {
                        Text(
                            text = "Detected an isolated network IP ($serverIp). Try connecting the car and phone to the same hotspot/LAN.",
                            color = dColors.destructiveColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .fillMaxWidth()
                        )
                    }
                    
                    // Show instructions
                    Text(
                        text = if (relaySession != null) {
                            "Scan the QR code from another device to open the relay upload page. After upload, the car will pull the file automatically."
                        } else if (isEmulator) {
                            "Emulator Mode: Open a browser inside the emulator and visit:"
                        } else {
                            "Scan the QR code or visit the URL from any device on the same network. Use a hotspot connection when needed:"
                        },
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = dColors.secondaryTextColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )
                    
                    // Show QR code if available
                    qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(300.dp)
                                .padding(vertical = 12.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (relaySession != null) {
                            "Relay status: $relayStatusText"
                        } else {
                            "Upload .zip files containing DOS games.\nFiles will be saved to the games directory."
                        },
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = dColors.secondaryTextColor,
                        modifier = Modifier.fillMaxWidth()
                    )

                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        wifiServer?.stop()
                        isServerRunning = false
                        Toast.makeText(
                            this@RetroDriveCarLauncher,
                            "File transfer server stopped",
                            Toast.LENGTH_SHORT
                        ).show()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dColors.destructiveColor,
                        contentColor = Color.White
                    )
                ) {
                    Text("Stop Server")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Just close dialog, keep server running
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = dColors.textButtonColor)
                ) {
                    Text("Keep Running")
                }
            }
        )
    }

    private fun hasExternalInternetConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasRequiredTransport =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        return hasRequiredTransport &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun fetchRelayStatus(statusUrl: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(statusUrl).get().build()
            relayHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "Relay status failed: ${response.code}")
                    return@withContext null
                }

                val bodyText = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyText)
                val status = json.optString("status", "unknown")
                val filename = if (json.has("filename") && !json.isNull("filename")) {
                    json.optString("filename")
                } else {
                    null
                }
                return@withContext Pair(status, filename)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Relay status exception: ${e.message}")
            null
        }
    }

    private suspend fun initializeRelaySession(initUrl: String, initKey: String): Boolean = withContext(Dispatchers.IO) {
        if (initKey.isBlank() || initKey == "CHANGE_ME_RELAY_INIT_KEY") {
            android.util.Log.w(TAG, "Relay init key is not configured in BuildConfig")
            return@withContext false
        }

        return@withContext try {
            val request = Request.Builder()
                .url(initUrl)
                .post(okhttp3.FormBody.Builder().build())
                .header("X-RetroDrive-Init-Key", initKey)
                .header("Accept", "application/json")
                .build()

            relayHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w(TAG, "Relay init failed: ${response.code}")
                    return@withContext false
                }
                true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Relay init exception: ${e.message}")
            false
        }
    }

    private suspend fun downloadAndImportFromRelay(
        downloadUrl: String,
        fallbackFilename: String?
    ): GameImportManager.ImportResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(downloadUrl).get().build()
            relayHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext GameImportManager.ImportResult(
                        success = false,
                        message = "Download failed (${response.code})"
                    )
                }

                val body = response.body
                    ?: return@withContext GameImportManager.ImportResult(false, "Download had no body")

                val contentLength = body.contentLength()
                if (contentLength == 0L) {
                    return@withContext GameImportManager.ImportResult(false, "Downloaded file was empty")
                }

                val stream = body.byteStream()

                val contentDisposition = response.header("Content-Disposition")
                val filenameFromHeader = contentDisposition
                    ?.substringAfter("filename=", "")
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() }

                val finalFilename = filenameFromHeader ?: fallbackFilename ?: "relay_upload.zip"
                stream.use {
                    return@withContext GameImportManager.importUploadedStream(
                        context = this@RetroDriveCarLauncher,
                        filename = finalFilename,
                        inputStream = it
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Relay download/import failed", e)
            GameImportManager.ImportResult(false, "Relay import failed: ${e.message}")
        }
    }

    @Composable
    fun TrialInfoDialog(onDismiss: () -> Unit) {
        val dColors = themedDialogColors()
        val btnStyle = themedButtonStyle()
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = dialogShape,
            containerColor = dColors.containerColor,
            titleContentColor = dColors.titleColor,
            textContentColor = dColors.textColor,
            title = {
                Text(
                    text = "Trial Mode",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "Welcome to RetrodriveAA trial mode. You get one successful remote upload. After that, purchase full access to continue uploading.",
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = btnStyle.containerColor,
                        contentColor = btnStyle.contentColor
                    ),
                    border = btnStyle.border
                ) {
                    Text("OK")
                }
            }
        )
    }

    @Composable
    fun PurchaseRequiredDialog(onPurchase: () -> Unit, onExit: () -> Unit) {
        val dColors = themedDialogColors()
        val btnStyle = themedButtonStyle()
        AlertDialog(
            onDismissRequest = {},
            shape = dialogShape,
            containerColor = dColors.containerColor,
            titleContentColor = dColors.titleColor,
            textContentColor = dColors.textColor,
            title = {
                Text(
                    text = "Trial Expired",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = "Purchase the full version (€9.99) to continue and unlock remote file transfer.",
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = onPurchase,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = btnStyle.containerColor,
                        contentColor = btnStyle.contentColor
                    ),
                    border = btnStyle.border
                ) {
                    Text("Purchase")
                }
            },
            dismissButton = {
                Button(
                    onClick = onExit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = btnStyle.containerColor,
                        contentColor = btnStyle.contentColor
                    ),
                    border = btnStyle.border
                ) {
                    Text("Exit")
                }
            }
        )
    }
    
    /**
     * Generate QR code bitmap from text
     */
    private fun generateQRCode(text: String, width: Int, height: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height)
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: WriterException) {
            android.util.Log.e("RetroDriveCarLauncher", "Failed to generate QR code", e)
            null
        }
    }
    
    @Composable
    fun AboutDialog(onDismiss: () -> Unit) {
        val dColors = themedDialogColors()
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = dialogShape,
            containerColor = dColors.containerColor,
            titleContentColor = dColors.titleColor,
            textContentColor = dColors.textColor,
            title = {
                Text(
                    text = "About RetrodriveAA",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // App version
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = dColors.accentColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "DOSBox Emulator for Android Automotive",
                        fontSize = 14.sp,
                        color = dColors.secondaryTextColor,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    // Open Source Attributions
                    Text(
                        text = "Open Source Components",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = dColors.titleColor,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // DOSBox
                    AttributionItem(
                        name = "DOSBox",
                        description = "Original DOSBox SVN 0.74",
                        url = "https://sourceforge.net/p/dosbox/code-0/HEAD/tree/"
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // SDL
                    AttributionItem(
                        name = "SDL2",
                        description = "Simple DirectMedia Layer",
                        url = "https://github.com/libsdl-org/SDL"
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "No bundled games are included. Import the DOS software you legally own or are allowed to use.",
                        fontSize = 13.sp,
                        color = dColors.secondaryTextColor,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "No affiliation or ownership is claimed.",
                        fontSize = 12.sp,
                        color = dColors.secondaryTextColor,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = dColors.textButtonColor)
                ) {
                    Text("Close")
                }
            }
        )
    }

    @Composable
    fun UiSettingsDialog(onDismiss: () -> Unit) {
        val dColors = themedDialogColors()
        var expandedThemeMenu by remember { mutableStateOf(false) }
        var selectedTheme by remember { mutableStateOf(appThemeMode) }
        val fontSteps = listOf(0.8f to "Small", 1.0f to "Normal", 1.35f to "Large")
        fun closestStep(v: Float) = fontSteps.minByOrNull { kotlin.math.abs(it.first - v) }!!.first
        var selectedScale by remember { mutableStateOf(closestStep(uiFontScale)) }

        val themeOptions = remember {
            listOf(
                AppThemeMode.LIGHT to "Light",
                AppThemeMode.DARK to "Dark",
                AppThemeMode.DARK_RETRO to "Dark Retro"
            )
        }

        fun themeLabel(mode: AppThemeMode): String {
            return themeOptions.first { it.first == mode }.second
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            shape = dialogShape,
            containerColor = dColors.containerColor,
            titleContentColor = dColors.titleColor,
            textContentColor = dColors.textColor,
            title = {
                Text(
                    text = "UI Settings",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Text Size",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = dColors.titleColor
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        fontSteps.forEach { (stepScale, label) ->
                            val isSelected = selectedScale == stepScale
                            Button(
                                onClick = {
                                    selectedScale = stepScale
                                    applyUiFontScale(stepScale)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) dColors.accentColor else dColors.containerColor,
                                    contentColor = if (isSelected) dColors.containerColor else dColors.textColor
                                ),
                                border = BorderStroke(1.dp, dColors.accentColor)
                            ) {
                                Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

                    Text(
                        text = "Theme",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = dColors.titleColor
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expandedThemeMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = dColors.textColor
                            ),
                            border = BorderStroke(1.dp, dColors.textColor)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(themeLabel(selectedTheme))
                                Text("▼")
                            }
                        }

                        DropdownMenu(
                            expanded = expandedThemeMenu,
                            onDismissRequest = { expandedThemeMenu = false }
                        ) {
                            themeOptions.forEach { (themeMode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectedTheme = themeMode
                                        expandedThemeMenu = false
                                        applyAppThemeMode(themeMode)
                                    }
                                )
                            }
                        }
                    }


                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = dColors.textButtonColor)
                ) {
                    Text("Close")
                }
            }
        )
    }
    
    @Composable
    private fun AttributionItem(name: String, description: String, url: String) {
        val dColors = themedDialogColors()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "• $name",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = dColors.accentColor
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = dColors.secondaryTextColor,
                modifier = Modifier.padding(start = 12.dp)
            )
            Text(
                text = url,
                fontSize = 11.sp,
                color = dColors.accentColor.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }

    @Composable
    fun GameConfigDialog(
        gameId: String,
        onDismiss: () -> Unit,
        onSaved: () -> Unit
    ) {
        val dColors = themedDialogColors()
        val btnStyle = themedButtonStyle()
        val schema = remember { ConfigManager.getSchema() }
        val masterDefaults = remember { ConfigManager.getMasterDefaults(this@RetroDriveCarLauncher) }
        val profile = remember(gameId) { GameProfileStore.load(this@RetroDriveCarLauncher, gameId) }

        val formValues = remember(gameId) {
            mutableStateMapOf<String, String>().apply {
                schema.forEach { (section, keys) ->
                    keys.forEach { (key, fallbackDefault) ->
                        val compoundKey = "$section.$key"
                        this[compoundKey] = profile.configOverrides[compoundKey]
                            ?: masterDefaults[compoundKey]
                            ?: fallbackDefault
                    }
                }
            }
        }

        fun collectOverridesFromForm(): Map<String, String> {
            val overrides = linkedMapOf<String, String>()
            schema.forEach { (section, keys) ->
                keys.forEach { (key, fallbackDefault) ->
                    val compoundKey = "$section.$key"
                    val masterValue = masterDefaults[compoundKey] ?: fallbackDefault
                    val currentValue = formValues[compoundKey] ?: ""
                    if (currentValue != masterValue) {
                        overrides[compoundKey] = currentValue
                    }
                }
            }
            return overrides
        }

        fun applyOverridesToForm(importedOverrides: Map<String, String>) {
            schema.forEach { (section, keys) ->
                keys.forEach { (key, fallbackDefault) ->
                    val compoundKey = "$section.$key"
                    formValues[compoundKey] = importedOverrides[compoundKey]
                        ?: masterDefaults[compoundKey]
                        ?: fallbackDefault
                }
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            shape = dialogShape,
            containerColor = dColors.containerColor,
            titleContentColor = dColors.titleColor,
            textContentColor = dColors.textColor,
            title = {
                Text(
                    text = "Configure: $gameId",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 380.dp, max = 620.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    schema.forEach { (section, keys) ->
                        Text(
                            text = "[$section]",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = dColors.accentColor,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        keys.forEach { (key, _) ->
                            val compoundKey = "$section.$key"
                            OutlinedTextField(
                                value = formValues[compoundKey] ?: "",
                                onValueChange = { formValues[compoundKey] = it },
                                label = { Text(key) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val overrides = collectOverridesFromForm()

                        GameProfileStore.save(
                            this@RetroDriveCarLauncher,
                            GameProfile(gameId = gameId, configOverrides = overrides)
                        )
                        Toast.makeText(
                            this@RetroDriveCarLauncher,
                            "Saved ${overrides.size} overrides for $gameId",
                            Toast.LENGTH_SHORT
                        ).show()
                        onSaved()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = btnStyle.containerColor,
                        contentColor = btnStyle.contentColor
                    ),
                    border = btnStyle.border
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = dColors.textButtonColor)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    private fun getOverridesExportFile(gameId: String): File {
        val safeName = gameId.replace("[^A-Za-z0-9._-]".toRegex(), "_")
        val dir = File(getExternalFilesDir(null), "config_overrides")
        dir.mkdirs()
        return File(dir, "$safeName.json")
    }

    private fun exportGameOverrides(gameId: String, overrides: Map<String, String>): File {
        val file = getOverridesExportFile(gameId)
        val json = JSONObject()
        overrides.forEach { (key, value) ->
            json.put(key, value)
        }
        file.writeText(json.toString(2))
        return file
    }

    private fun importGameOverrides(gameId: String): Map<String, String>? {
        val file = getOverridesExportFile(gameId)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val result = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = json.optString(key, "")
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Monitor the parked state continuously
     * Uses Car UX restrictions when available and defaults to locked for safety.
     */
    private fun startParkedStateMonitoring() {
        if (shouldBypassSafetyGate()) {
            isDriving = false
            isCheckingParkedState = false
            return
        }

        if (allowNonCarDevice && !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            isDriving = false
            isCheckingParkedState = false
            return
        }

        isCheckingParkedState = true

        lifecycleScope.launch {
            while (true) {
                isDriving = !isVehicleParked()
                isCheckingParkedState = false
                delay(PARKED_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun loadUiFontScale(): Float {
        val prefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
        // Snap to nearest valid step
        val steps = listOf(0.8f, 1.0f, 1.35f)
        return steps.minByOrNull { kotlin.math.abs(it - saved) } ?: 1.0f
    }

    private fun loadAppThemeMode(): AppThemeMode {
        val prefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME_MODE, AppThemeMode.LIGHT.name) ?: AppThemeMode.LIGHT.name
        return AppThemeMode.entries.firstOrNull { it.name == saved } ?: AppThemeMode.LIGHT
    }

    private fun applyUiFontScale(scale: Float) {
        val steps = listOf(0.8f, 1.0f, 1.35f)
        uiFontScale = steps.minByOrNull { kotlin.math.abs(it - scale) } ?: 1.0f
        val prefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_FONT_SCALE, uiFontScale).apply()
    }

    private fun applyAppThemeMode(themeMode: AppThemeMode) {
        appThemeMode = themeMode
        val prefs = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, themeMode.name).apply()
    }

    private fun cycleUiFontScale() {
        val steps = listOf(0.8f, 1.0f, 1.35f)
        val current = uiFontScale
        val currentIndex = steps.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        val nextIndex = if (currentIndex == -1) 1 else (currentIndex + 1) % steps.size
        applyUiFontScale(steps[nextIndex])
    }

    /**
     * Check parked state using car UX restrictions.
     * Returns false (locked) when the service is unavailable.
     */
    private fun isVehicleParked(): Boolean {
        if (allowNonCarDevice && !packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            return true
        }

        return try {
            val uxService = getUxRestrictionsService() ?: return false
            val currentRestrictionsMethod = uxService.javaClass.getMethod("getCurrentCarUxRestrictions")
            val restrictions = currentRestrictionsMethod.invoke(uxService) ?: return false
            val activeRestrictionsMethod = restrictions.javaClass.getMethod("getActiveRestrictions")
            val activeRestrictions = (activeRestrictionsMethod.invoke(restrictions) as? Number)?.toInt() ?: return false
            activeRestrictions == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolve CarUxRestrictionsManager across different Android Automotive builds.
     */
    private fun getUxRestrictionsService(): Any? {
        // Path 1: Context#getSystemService(Class)
        try {
            val uxManagerClass = Class.forName("android.car.drivingstate.CarUxRestrictionsManager")
            val getSystemServiceByClass = this::class.java.getMethod("getSystemService", Class::class.java)
            val service = getSystemServiceByClass.invoke(this, uxManagerClass)
            if (service != null) return service
        } catch (_: Exception) {
        }

        // Path 2: direct service name used by some builds
        try {
            val service = getSystemService("car_ux_restriction")
            if (service != null) return service
        } catch (_: Exception) {
        }

        // Path 3: Car API manager lookup via reflection
        try {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", android.content.Context::class.java)
            val car = createCar.invoke(null, this) ?: return null

            try {
                val connect = carClass.getMethod("connect")
                connect.invoke(car)
            } catch (_: Exception) {
            }

            val serviceName = try {
                carClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as? String ?: "uxrestriction"
            } catch (_: Exception) {
                "uxrestriction"
            }

            val getCarManager = carClass.getMethod("getCarManager", String::class.java)
            val service = getCarManager.invoke(car, serviceName)
            if (service != null) return service
        } catch (_: Exception) {
        }

        return null
    }

    /**
     * Check and request necessary permissions
     */
    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        // Storage permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    // Note: onRequestPermissionsResult is deprecated in ComponentActivity
    // Permission handling should be done using ActivityResultContracts in production
    private fun onPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "Storage permissions are required to load DOS games",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
