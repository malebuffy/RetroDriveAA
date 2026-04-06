package com.dosbox.emu;

import org.libsdl.app.SDLActivity;
import android.content.Context;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.*;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.content.res.AssetManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.Surface;
import android.app.Dialog;
import java.io.*;
import java.text.DateFormat;
import java.util.Date;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.codeodyssey.retrodriveaa.BuildConfig;
import com.codeodyssey.retrodriveaa.SaveStateRepository;

import com.dosbox.emu.input.InputDirector;

/* 
 * A sample wrapper class that just calls SDLActivity 
 */ 

public class DOSBoxActivity extends SDLActivity implements VirtualDPadView.OnDPadEventListener, VirtualButtonsView.OnButtonEventListener, WifiControllerServer.ControllerEventListener {
        // Reference to WifiTransferServer if available (public for easy access)
        public static Object wifiTransferServerInstance = null;

        // Call this before starting WifiControllerServer to stop WifiTransferServer if running
        private void stopWifiTransferServerIfRunning() {
            Log.d(TAG, "Checking if WifiTransferServer is running...");
            try {
                if (wifiTransferServerInstance != null) {
                    Log.i(TAG, "Found WifiTransferServer instance, attempting to stop it...");
                    // Use reflection to call stop() on WifiTransferServer
                    java.lang.reflect.Method stopMethod = wifiTransferServerInstance.getClass().getMethod("stop");
                    stopMethod.invoke(wifiTransferServerInstance);
                    wifiTransferServerInstance = null; // Clear reference
                    Log.i(TAG, "Successfully stopped WifiTransferServer before starting WifiControllerServer");
                } else {
                    Log.d(TAG, "No WifiTransferServer instance found (not running)");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop WifiTransferServer: " + e.getMessage(), e);
            }
        }
    private static final String TAG = "DOSBox";
    private LinearLayout floatingToolStack;
    private FrameLayout settingsButton;
    private FrameLayout saveStateButton;
    private FrameLayout dpadToggleButton;
    private FrameLayout keyboardButton;
    private FrameLayout wifiButton;
    private FrameLayout exitButton;
    private VirtualDPadView virtualDPad;
    private VirtualButtonsView virtualButtons;
    private InputDirector inputDirector;
    private WifiControllerServer wifiServer;
    private boolean isWifiServerRunning = false;
    private String currentGameFolder = null; // Track current game for per-game config
    private String currentSaveStateGameId = "__browse__";
    private String currentSaveStatePath = "";
    private FrameLayout saveStateProgressOverlay;
    private volatile boolean saveStateInProgress = false;

    private static final String UI_PREFS_NAME = "retrodrive_ui_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";

    private static final class DialogThemeColors {
        final int cardBackground;
        final int primaryText;
        final int secondaryText;
        final int primaryButtonBg;
        final int primaryButtonText;
        final int secondaryButtonBg;
        final int secondaryButtonText;
        final int buttonBorder;

        DialogThemeColors(int cardBackground, int primaryText, int secondaryText,
                          int primaryButtonBg, int primaryButtonText,
                          int secondaryButtonBg, int secondaryButtonText,
                          int buttonBorder) {
            this.cardBackground = cardBackground;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.primaryButtonBg = primaryButtonBg;
            this.primaryButtonText = primaryButtonText;
            this.secondaryButtonBg = secondaryButtonBg;
            this.secondaryButtonText = secondaryButtonText;
            this.buttonBorder = buttonBorder;
        }
    }

    private DialogThemeColors getDialogThemeColors() {
        String themeMode = getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_THEME_MODE, "DARK");

        if ("LIGHT".equals(themeMode)) {
            return new DialogThemeColors(
                    Color.parseColor("#ECEAF4"),
                    Color.parseColor("#000000"),
                    Color.parseColor("#000000"),
                    Color.parseColor("#4CAF50"),
                    Color.WHITE,
                    Color.parseColor("#2196F3"),
                    Color.WHITE,
                    Color.TRANSPARENT
            );
        }

        if ("DARK_RETRO".equals(themeMode)) {
            return new DialogThemeColors(
                    Color.parseColor("#101615"),
                    Color.parseColor("#39FF14"),
                    Color.parseColor("#39FF14"),
                    Color.BLACK,
                    Color.parseColor("#39FF14"),
                    Color.BLACK,
                    Color.parseColor("#39FF14"),
                    Color.parseColor("#39FF14")
            );
        }

        return new DialogThemeColors(
                Color.parseColor("#1A1F26"),
                Color.WHITE,
                Color.WHITE,
                Color.BLACK,
                Color.WHITE,
                Color.BLACK,
                Color.WHITE,
                Color.WHITE
        );
    }

    /**
     * Constrains a card-style dialog to at most 85% of screen height.
     * Sets transparent background and, if the rendered dialog exceeds the
     * limit, caps the window height so that an inner ScrollView can scroll.
     */
    private void constrainDialogToScreen(Dialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        w.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        w.getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                        int maxH = (int) (dm.heightPixels * 0.85);
                        if (w.getDecorView().getHeight() > maxH) {
                            w.setLayout(w.getDecorView().getWidth(), maxH);
                        }
                    }
                }
        );
    }

    private static final class GamepadLayoutSpec {
        final int dpadSizePx;
        final int buttonAreaSizePx;
        final int leftMarginPx;
        final int rightMarginPx;
        final int bottomMarginPx;
        final int centerGapPx;

        GamepadLayoutSpec(int dpadSizePx, int buttonAreaSizePx, int leftMarginPx, int rightMarginPx, int bottomMarginPx, int centerGapPx) {
            this.dpadSizePx = dpadSizePx;
            this.buttonAreaSizePx = buttonAreaSizePx;
            this.leftMarginPx = leftMarginPx;
            this.rightMarginPx = rightMarginPx;
            this.bottomMarginPx = bottomMarginPx;
            this.centerGapPx = centerGapPx;
        }
    }

    private int dpToPx(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private int calculateFabStackReservedRightWidthPx() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenHeight = metrics.heightPixels;
        int screenWidth = metrics.widthPixels;
        float density = metrics.density;

        int minFabSize = (int) (36 * density);
        int maxFabSize = (int) (56 * density);
        int maxFabMargin = (int) (12 * density);
        int fabCount = 4;

        float scaleH = (screenHeight * 0.9f - (int) (32 * density)) / (fabCount * maxFabSize + (fabCount - 1) * maxFabMargin);
        float scaleW = (screenWidth * 0.18f) / maxFabSize;
        float scale = Math.min(Math.min(scaleH, scaleW), 1.0f);
        scale = Math.max(scale, 0.6f);

        int fabSize = Math.max(minFabSize, (int) (maxFabSize * scale));
        int stackRightMargin = (int) (16 * density);
        int extraClearance = (int) (10 * density);

        return stackRightMargin + fabSize + extraClearance;
    }

    private GamepadLayoutSpec calculateGamepadLayoutSpec() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int baseDpadSize = dpToPx(250f);
        int baseButtonAreaSize = dpToPx(220f);
        int minDpadSize = dpToPx(120f);
        int minButtonAreaSize = dpToPx(110f);
        int leftMarginMin = dpToPx(12f);
        int rightMarginMin = Math.max(dpToPx(12f), calculateFabStackReservedRightWidthPx());
        int bottomMargin = dpToPx(40f);

        int desiredGap = Math.max(dpToPx(48f), Math.round(screenWidth * 0.20f));
        int maxCombinedControlWidth = Math.max(
                minDpadSize + minButtonAreaSize,
            screenWidth - leftMarginMin - rightMarginMin - desiredGap
        );

        float baseCombined = (float) (baseDpadSize + baseButtonAreaSize);
        float scale = Math.min(1f, maxCombinedControlWidth / baseCombined);

        int dpadSize = Math.max(minDpadSize, Math.round(baseDpadSize * scale));
        int buttonAreaSize = Math.max(minButtonAreaSize, Math.round(baseButtonAreaSize * scale));

        int combined = dpadSize + buttonAreaSize;
        if (combined > maxCombinedControlWidth) {
            float adjust = maxCombinedControlWidth / (float) combined;
            dpadSize = Math.max(minDpadSize, Math.round(dpadSize * adjust));
            buttonAreaSize = Math.max(minButtonAreaSize, Math.round(buttonAreaSize * adjust));
            combined = dpadSize + buttonAreaSize;
        }

        int remainingAfterMinMargins = Math.max(0, screenWidth - desiredGap - combined - leftMarginMin - rightMarginMin);
        int leftMargin = leftMarginMin + (remainingAfterMinMargins / 2);
        int rightMargin = rightMarginMin + (remainingAfterMinMargins - (remainingAfterMinMargins / 2));
        int actualGap = Math.max(0, screenWidth - leftMargin - dpadSize - rightMargin - buttonAreaSize);

        if (actualGap < desiredGap) {
            int extraNeeded = desiredGap - actualGap;
            int shrinkDpad = Math.min(extraNeeded / 2, Math.max(0, dpadSize - minDpadSize));
            int shrinkButtons = Math.min(extraNeeded - shrinkDpad, Math.max(0, buttonAreaSize - minButtonAreaSize));
            dpadSize -= shrinkDpad;
            buttonAreaSize -= shrinkButtons;
            actualGap = Math.max(0, screenWidth - leftMargin - dpadSize - rightMargin - buttonAreaSize);
        }

        return new GamepadLayoutSpec(dpadSize, buttonAreaSize, leftMargin, rightMargin, bottomMargin, actualGap);
    }

    private void applyVirtualGamepadLayout(GamepadLayoutSpec spec) {
        if (virtualDPad != null) {
            FrameLayout.LayoutParams dpadParams = new FrameLayout.LayoutParams(spec.dpadSizePx, spec.dpadSizePx);
            dpadParams.gravity = Gravity.BOTTOM | Gravity.START;
            dpadParams.setMargins(spec.leftMarginPx, 0, 0, spec.bottomMarginPx);
            virtualDPad.setLayoutParams(dpadParams);
        }

        if (virtualButtons != null) {
            FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(spec.buttonAreaSizePx, spec.buttonAreaSizePx);
            buttonParams.gravity = Gravity.BOTTOM | Gravity.END;
            buttonParams.setMargins(0, 0, spec.rightMarginPx, spec.bottomMarginPx);
            virtualButtons.setLayoutParams(buttonParams);
        }

        Log.d(TAG, "Gamepad layout updated: dpad=" + spec.dpadSizePx + "px, buttons=" + spec.buttonAreaSizePx
                + "px, centerGap=" + spec.centerGapPx + "px (" + Math.round(spec.centerGapPx * 100f / getResources().getDisplayMetrics().widthPixels) + "%)");
    }

    private void updateVirtualGamepadLayout() {
        applyVirtualGamepadLayout(calculateGamepadLayoutSpec());
    }
    
    /**
     * Override to pass DOSBox arguments including config file path and mount commands
     */
    @Override
    protected String[] getArguments() {
        String configPath = getIntent().getStringExtra("SESSION_CONFIG_PATH");
        if (configPath == null || configPath.isEmpty()) {
            configPath = getExternalFilesDir(null) + File.separator + "dosbox.conf";
        }
        String gamePath = getExternalFilesDir(null) + File.separator + "game";
        
        // Check if a specific game folder was requested
        String gameFolder = getIntent().getStringExtra("GAME_FOLDER");
        currentGameFolder = gameFolder; // Store for per-game configuration
        
        Log.d(TAG, "DOSBox config path: " + configPath);
        Log.d(TAG, "DOSBox game path: " + gamePath);
        if (gameFolder != null) {
            Log.d(TAG, "Game folder requested: " + gameFolder);
        }
        
        // Build arguments list
        java.util.ArrayList<String> argsList = new java.util.ArrayList<>();
        argsList.add("-conf");
        argsList.add(configPath);
        argsList.add("-c");
        argsList.add("mount c " + gamePath);
        argsList.add("-c");
        argsList.add("c:");
        argsList.add("-c");
        argsList.add("cls");
        
        if (gameFolder != null && !gameFolder.isEmpty()) {
            String dosName = convertToDos83Name(gameFolder);
            Log.d(TAG, "DOS 8.3 name: " + dosName);
            
            // Navigate to specific game folder
            argsList.add("-c");
            argsList.add("echo Launching " + gameFolder + "...");
            argsList.add("-c");
            argsList.add("cd " + dosName);
            argsList.add("-c");
            argsList.add("echo.");
            argsList.add("-c");
            argsList.add("echo Current directory:");
            argsList.add("-c");
            argsList.add("dir");
        } else {
            // Just show welcome and directory listing
            argsList.add("-c");
            argsList.add("echo Welcome to RetroDrive DOSBox!");
            argsList.add("-c");
            argsList.add("echo.");
            argsList.add("-c");
            argsList.add("echo C: drive contains your games");
            argsList.add("-c");
            argsList.add("echo.");
            argsList.add("-c");
            argsList.add("dir /w");
        }
        
        return argsList.toArray(new String[0]);
    }

    private String convertToDos83Name(String longName) {
        if (longName == null || longName.isEmpty()) {
            return longName;
        }

        String cleaned = longName.replaceAll("\\s+", "").toUpperCase();
        if (cleaned.length() <= 8) {
            return cleaned;
        }

        String shortName = cleaned.substring(0, 6) + "~1";
        Log.d(TAG, "Converted '" + longName + "' to DOS 8.3 format: '" + shortName + "'");
        return shortName;
    }
    
    /* Based on volume keys related patch from bug report:
    http://bugzilla.libsdl.org/show_bug.cgi?id=1569     */

    // enable to intercept keys before SDL gets them
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        switch (event.getKeyCode()) {
        // forward volume keys to Android
        case KeyEvent.KEYCODE_VOLUME_UP:
        case KeyEvent.KEYCODE_VOLUME_DOWN:
            return false;
        // Show/Hide on-screen keyboard (but don't change to text input mode)
        case KeyEvent.KEYCODE_BACK:
            if (event.getAction() == KeyEvent.ACTION_UP)
                toggleOnScreenKeyboard();
            return true;
        }
        
        // Route to InputDirector for handling
        if (inputDirector != null && inputDirector.processKeyEvent(event)) {
            return true;
        }
        
        return super.dispatchKeyEvent(event);
    }

    // Hide keyboard when touching outside of it, and route touch events to InputDirector
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Route to InputDirector first
        if (inputDirector != null && inputDirector.processTouchEvent(event)) {
            // Event was handled by input strategy
            return true;
        }
        
        // Hide keyboard when touching outside of it
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            View currentFocus = getCurrentFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            
            // Check if keyboard is visible
            if (imm != null && currentFocus != null) {
                // Hide the keyboard
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                
                // Also hide mTextEdit if it's visible
                if (mTextEdit != null && mTextEdit.getVisibility() == View.VISIBLE) {
                    mTextEdit.setVisibility(View.GONE);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    // Fix the initial orientation
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // Initialize the rest (e.g. SDL)
        DOSBoxJNI.nativeSetEmbeddedSessionMode(false);
        String launchGameFolder = getIntent().getStringExtra("GAME_FOLDER");
        int launchDisplayId = getWindowManager().getDefaultDisplay().getDisplayId();
        Log.d(TAG, "onCreate display=" + launchDisplayId + " game='" + (launchGameFolder == null ? "" : launchGameFolder) + "'");
		copyAssetAll("dosbox.conf");
		copyAssetAll("dosbox_base.conf");
		
		// Initialize screen dimensions for mouse tracking
		android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
		com.dosbox.emu.input.NativeBridge.initScreenDimensions(metrics.widthPixels, metrics.heightPixels);
		
		// Ensure game directory exists for mounting
		ensureGameDirectoryExists();

        initializeSaveStateContext();
		
		// Initialize InputDirector with auto-detection
		inputDirector = new InputDirector();
		inputDirector.autoDetectMode(this);
		Log.d(TAG, "Input mode: " + inputDirector.getCurrentModeName());
		
		// Add virtual D-Pad, floating tool stack, and exit button after a short delay to ensure SDL surface is ready
		getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                addVirtualDPad();
                addVirtualActionButtons();
                addFloatingToolStack();
                addExitButton();
            }
        });
    }
    
    /**
     * Add virtual D-Pad overlay
     */
    private void addVirtualDPad() {
        try {
            ViewGroup rootView = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) {
                Log.e(TAG, "Could not find root view for virtual D-Pad");
                return;
            }
            
            // Create virtual D-Pad
            virtualDPad = new VirtualDPadView(this);
            virtualDPad.setGameFolder(currentGameFolder); // Set game name for per-game config
            
            GamepadLayoutSpec spec = calculateGamepadLayoutSpec();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(spec.dpadSizePx, spec.dpadSizePx);
            params.gravity = Gravity.BOTTOM | Gravity.START;
            params.setMargins(spec.leftMarginPx, 0, 0, spec.bottomMarginPx);
            virtualDPad.setLayoutParams(params);
            
            // Set event listener
            virtualDPad.setOnDPadEventListener(this);
            
            // Initially hidden - toggle with green FAB button
            virtualDPad.setVisibility(View.GONE);
            
            Log.d(TAG, "Virtual D-Pad size: " + spec.dpadSizePx + "px (" +
                (spec.dpadSizePx / getResources().getDisplayMetrics().density) + "dp)");
            Log.d(TAG, "D-Pad positioned with left margin: " + spec.leftMarginPx + "px, center gap=" + spec.centerGapPx + "px");
            
            // Add to root view
            rootView.addView(virtualDPad);
            
            Log.d(TAG, "Virtual D-Pad added successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding virtual D-Pad", e);
        }
    }
    
    /**
     * Add virtual action buttons (X, Y, A, B) overlay
     */
    private void addVirtualActionButtons() {
        try {
            ViewGroup rootView = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) {
                Log.e(TAG, "Could not find root view for virtual action buttons");
                return;
            }
            
            // Create virtual action buttons
            virtualButtons = new VirtualButtonsView(this);
            virtualButtons.setGameFolder(currentGameFolder); // Set game name for per-game config
            
            GamepadLayoutSpec spec = calculateGamepadLayoutSpec();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(spec.buttonAreaSizePx, spec.buttonAreaSizePx);
            params.gravity = Gravity.BOTTOM | Gravity.END;
            params.setMargins(0, 0, spec.rightMarginPx, spec.bottomMarginPx);
            virtualButtons.setLayoutParams(params);
            
            // Set event listener
            virtualButtons.setOnButtonEventListener(this);
            
            // Initially hidden - will be shown with D-Pad
            virtualButtons.setVisibility(View.GONE);
            
            Log.d(TAG, "Virtual action buttons size: " + spec.buttonAreaSizePx + "px (" +
                (spec.buttonAreaSizePx / getResources().getDisplayMetrics().density) + "dp)");
            Log.d(TAG, "Action buttons positioned with right margin: " + spec.rightMarginPx + "px, center gap=" + spec.centerGapPx + "px");
            
            // Add to root view
            rootView.addView(virtualButtons);
            
            Log.d(TAG, "Virtual action buttons added successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding virtual action buttons", e);
        }
    }
    
    /**
     * Add floating tool stack with three FABs
     */
    private void addFloatingToolStack() {
        try {
            ViewGroup rootView = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) {
                Log.e(TAG, "Could not find root view for tool stack");
                return;
            }
            
            // Create vertical stack container
            floatingToolStack = new LinearLayout(this);
            floatingToolStack.setOrientation(LinearLayout.VERTICAL);
            floatingToolStack.setGravity(Gravity.CENTER_HORIZONTAL); // Center all FABs horizontally
            
            // Dynamically shrink FABs if not enough height or width (for ultra-wide screens)
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            float density = getResources().getDisplayMetrics().density;
            int minFabSize = (int) (36 * density); // Minimum 36dp
            int minFabMargin = (int) (4 * density); // Minimum 4dp
            int maxFabSize = (int) (56 * density); // 56dp
            int maxFabMargin = (int) (12 * density); // 12dp
            int fabCount = 5; // save, settings, dpad, keyboard, wifi
            int neededHeight = fabCount * maxFabSize + (fabCount - 1) * maxFabMargin + (int)(32 * density);
            float scaleH = (screenHeight * 0.9f - (int)(32 * density)) / (fabCount * maxFabSize + (fabCount - 1) * maxFabMargin);
            float scaleW = (screenWidth * 0.18f) / maxFabSize; // Use 18% of width for FABs on ultra-wide
            float scale = Math.min(Math.min(scaleH, scaleW), 1.0f);
            scale = Math.max(scale, 0.6f); // Clamp to avoid too large/small
            int fabSize = Math.max(minFabSize, (int)(maxFabSize * scale));
            int fabMargin = Math.max(minFabMargin, (int)(maxFabMargin * scale));
            // Use same margin as exit button for alignment
            int rightMargin = (int) (16 * density); // 16dp - matches exit button
            int bottomMargin = (int) (16 * density); // 16dp

            // TOP FAB: Save/Load State
            saveStateButton = createFABWithIcon(0xCC607D8B, fabSize, "💾");
            saveStateButton.setOnClickListener(v -> openSaveLoadMenu());
            floatingToolStack.addView(saveStateButton);
            addFabSpacer(floatingToolStack, fabMargin);
            
            // FAB: Settings (D-Pad Config)
            settingsButton = createFABWithIcon(0xCCFF9800, fabSize, "⚙"); // Orange with gear icon
            settingsButton.setOnClickListener(v -> openDPadConfig());
            floatingToolStack.addView(settingsButton);
            addFabSpacer(floatingToolStack, fabMargin);
            
            // MIDDLE FAB: D-Pad Toggle
            dpadToggleButton = createFABWithIcon(0xCC4CAF50, fabSize, "🎮"); // Green with gamepad icon
            dpadToggleButton.setOnClickListener(v -> toggleVirtualDPad());
            floatingToolStack.addView(dpadToggleButton);
            addFabSpacer(floatingToolStack, fabMargin);
            
            // BOTTOM FAB: Keyboard
            keyboardButton = createFABWithIcon(0xCC2196F3, fabSize, "⌨"); // Blue with keyboard icon
            keyboardButton.setOnClickListener(v -> toggleOnScreenKeyboard());
            floatingToolStack.addView(keyboardButton);
            addFabSpacer(floatingToolStack, fabMargin);
            
            // REMOTE FAB: Remote Controller
            wifiButton = createFABWithIcon(0xCC9C27B0, fabSize, com.codeodyssey.retrodriveaa.R.drawable.ic_phone); // Purple with phone icon
            wifiButton.setOnClickListener(v -> toggleWifiController());
            floatingToolStack.addView(wifiButton);
            
            // Position stack in bottom-right corner
            FrameLayout.LayoutParams stackParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            stackParams.gravity = Gravity.BOTTOM | Gravity.END;
            stackParams.setMargins(
                0, 0,
                rightMargin,
                bottomMargin
            );
            floatingToolStack.setLayoutParams(stackParams);
            
            // Add to root view
            rootView.addView(floatingToolStack);
            
            Log.d(TAG, "Floating tool stack added successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding floating tool stack", e);
        }
    }
    
    /**
     * Create a Floating Action Button with specified color and icon drawable
     */
    private FrameLayout createFABWithIcon(int color, int size, int drawableResId) {
        // Use FrameLayout to hold button and icon
        FrameLayout fabContainer = new FrameLayout(this);
        fabContainer.setClickable(true);
        fabContainer.setFocusable(true);
        
        // Create circular button background
        ImageButton fab = new ImageButton(this);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        background.setStroke(3, Color.WHITE);
        fab.setBackground(background);
        fab.setImageResource(drawableResId);
        fab.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        fab.setColorFilter(Color.WHITE);
        // Add padding to keep icon from touching edges
        int padding = (int)(size * 0.25f);
        fab.setPadding(padding, padding, padding, padding);
        fab.setClickable(false); // Let container handle clicks
        fab.setFocusable(false);
        
        // Set size for button (centered in frame)
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(size, size);
        fabParams.gravity = Gravity.CENTER;
        fab.setLayoutParams(fabParams);
        fabContainer.addView(fab);
        
        // Set exact size for container to ensure alignment
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(size, size);
        containerParams.gravity = Gravity.CENTER_HORIZONTAL;
        fabContainer.setLayoutParams(containerParams);
        
        // Add touch feedback
        fabContainer.setAlpha(0.8f);
        fabContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    fabContainer.setAlpha(1.0f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    fabContainer.setAlpha(0.8f);
                    break;
            }
            return false;
        });
        
        return fabContainer;
    }
    
    /**
     * Create a Floating Action Button with specified color and icon text
     */
    private FrameLayout createFABWithIcon(int color, int size, String icon) {
        // Use FrameLayout to hold button and text
        FrameLayout fabContainer = new FrameLayout(this);
        fabContainer.setClickable(true);
        fabContainer.setFocusable(true);
        
        // Create circular button background
        ImageButton fab = new ImageButton(this);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        background.setStroke(3, Color.WHITE);
        fab.setBackground(background);
        fab.setPadding(0, 0, 0, 0);
        fab.setClickable(false); // Let container handle clicks
        fab.setFocusable(false);
        fab.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        
        // Set size for button (centered in frame)
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(size, size);
        fabParams.gravity = Gravity.CENTER;
        fab.setLayoutParams(fabParams);
        fabContainer.addView(fab);
        
        // Add icon text on top
        TextView iconText = new TextView(this);
        iconText.setText(icon);
        // Scale icon text size with FAB size - use simpler calculation
        // Base: 56dp FAB -> 20sp text
        float density = getResources().getDisplayMetrics().density;
        float iconTextSize = 20f * ((float)size / (56f * density));
        iconText.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconTextSize);
        iconText.setTextColor(Color.WHITE);
        iconText.setTypeface(null, Typeface.BOLD);
        iconText.setGravity(Gravity.CENTER);
        iconText.setClickable(false);
        iconText.setFocusable(false);
        
        // Center text in frame - no extra padding needed
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        textParams.gravity = Gravity.CENTER;
        iconText.setLayoutParams(textParams);
        fabContainer.addView(iconText);
        
        // Set exact size for container to ensure alignment
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(size, size);
        containerParams.gravity = Gravity.CENTER_HORIZONTAL;
        fabContainer.setLayoutParams(containerParams);
        
        // Add touch feedback
        fabContainer.setAlpha(0.8f);
        fabContainer.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    fabContainer.setAlpha(1.0f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    fabContainer.setAlpha(0.8f);
                    break;
            }
            return false;
        });
        
        return fabContainer;
    }
    
    /**
     * Add spacer between FABs
     */
    private void addFabSpacer(LinearLayout parent, int height) {
        View spacer = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height
        );
        spacer.setLayoutParams(params);
        parent.addView(spacer);
    }
    
    /**
     * Toggle virtual D-Pad visibility
     */
    private void toggleVirtualDPad() {
        if (virtualDPad != null) {
            if (virtualDPad.getVisibility() == View.VISIBLE) {
                virtualDPad.setVisibility(View.GONE);
                if (virtualButtons != null) {
                    virtualButtons.setVisibility(View.GONE);
                }
                Log.d(TAG, "Virtual D-Pad and action buttons hidden");
            } else {
                virtualDPad.setVisibility(View.VISIBLE);
                if (virtualButtons != null) {
                    virtualButtons.setVisibility(View.VISIBLE);
                }
                Log.d(TAG, "Virtual D-Pad and action buttons shown");
            }
        }
    }
    
    /**
     * Open D-Pad configuration dialog
     */
    private void openDPadConfig() {
        DPadConfigDialog dialog = new DPadConfigDialog(this, currentGameFolder);
        dialog.setOnConfigChangedListener(() -> {
            // Reload key mappings in virtual D-Pad and buttons
            if (virtualDPad != null) {
                virtualDPad.reloadKeyMappings();
            }
            if (virtualButtons != null) {
                virtualButtons.reloadKeyMappings();
            }
            Log.d(TAG, "D-Pad and action button configuration updated");
        });
        dialog.show();
    }

    private void initializeSaveStateContext() {
        String gameId = getIntent().getStringExtra("SAVESTATE_GAME_ID");
        String statePath = getIntent().getStringExtra("SAVESTATE_PATH");

        if (gameId == null || gameId.trim().isEmpty()) {
            gameId = (currentGameFolder == null || currentGameFolder.trim().isEmpty()) ? "__browse__" : currentGameFolder;
        }

        if (statePath == null || statePath.trim().isEmpty()) {
            statePath = SaveStateRepository.INSTANCE.getStateFile(this, gameId).getAbsolutePath();
        }

        currentSaveStateGameId = gameId;
        currentSaveStatePath = statePath;
        DOSBoxJNI.nativeSetSaveStateContext(currentSaveStateGameId, currentSaveStatePath);
    }

    private void openSaveLoadMenu() {
        final DialogThemeColors theme = getDialogThemeColors();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(theme.cardBackground);
        cardBackground.setCornerRadius(dpToPx(20));
        layout.setBackground(cardBackground);

        TextView title = new TextView(this);
        title.setText("Game state (Load game first)");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(theme.primaryText);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dpToPx(16));
        layout.addView(title);

        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER);

        Button saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setTextColor(theme.primaryButtonText);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(theme.primaryButtonBg);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            saveBg.setStroke(dpToPx(1), theme.buttonBorder);
        }
        saveBg.setCornerRadius(dpToPx(26));
        saveButton.setBackground(saveBg);

        Button loadButton = new Button(this);
        loadButton.setText("Load");
        loadButton.setTextColor(theme.secondaryButtonText);
        GradientDrawable loadBg = new GradientDrawable();
        loadBg.setColor(theme.secondaryButtonBg);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            loadBg.setStroke(dpToPx(1), theme.buttonBorder);
        }
        loadBg.setCornerRadius(dpToPx(26));
        loadButton.setBackground(loadBg);

        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        saveParams.setMargins(0, 0, dpToPx(8), 0);
        LinearLayout.LayoutParams loadParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        loadParams.setMargins(dpToPx(8), 0, 0, 0);
        saveButton.setLayoutParams(saveParams);
        loadButton.setLayoutParams(loadParams);

        actionsRow.addView(saveButton);
        actionsRow.addView(loadButton);
        layout.addView(actionsRow);

        ScrollView scrollWrapper = new ScrollView(this);
        scrollWrapper.addView(layout);

        Dialog dialog = new Dialog(this);
        dialog.setContentView(scrollWrapper);
        constrainDialogToScreen(dialog);

        saveButton.setOnClickListener(v -> {
            dialog.dismiss();
            performManualSaveState();
        });

        loadButton.setOnClickListener(v -> {
            dialog.dismiss();
            openLoadSlotsDialog();
        });

        dialog.show();
    }

    private void performManualSaveState() {
        if (saveStateInProgress) {
            android.widget.Toast.makeText(this, "Save already in progress", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        Integer nextSlot = SaveStateRepository.INSTANCE.findFirstEmptySlot(this, currentSaveStateGameId);
        if (nextSlot == null) {
            showSaveSlotsFullDialog();
            return;
        }

        final int targetSlot = nextSlot;
        final String targetStatePath = SaveStateRepository.INSTANCE
                .getStateFile(this, currentSaveStateGameId, targetSlot)
                .getAbsolutePath();
        DOSBoxJNI.nativeSetSaveStateContext(currentSaveStateGameId, targetStatePath);

        captureCurrentFrameBitmap(capturedBitmap -> startManualSaveState(targetSlot, targetStatePath, capturedBitmap));
    }

    private interface FrameCaptureCallback {
        void onCaptured(Bitmap capturedBitmap);
    }

    private void captureCurrentFrameBitmap(FrameCaptureCallback callback) {
        final View rootContent = getWindow().getDecorView().findViewById(android.R.id.content);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Surface sdlSurface = SDLActivity.getNativeSurface();
            final int width = (rootContent != null && rootContent.getWidth() > 0) ? rootContent.getWidth() : getResources().getDisplayMetrics().widthPixels;
            final int height = (rootContent != null && rootContent.getHeight() > 0) ? rootContent.getHeight() : getResources().getDisplayMetrics().heightPixels;
            if (sdlSurface != null && sdlSurface.isValid() && width > 0 && height > 0) {
                final Bitmap surfaceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                final HandlerThread handlerThread = new HandlerThread("savestate-pre-capture");
                handlerThread.start();
                PixelCopy.request(
                        sdlSurface,
                        surfaceBitmap,
                        result -> {
                            Bitmap captured = null;
                            if (result == PixelCopy.SUCCESS) {
                                captured = surfaceBitmap;
                            } else {
                                surfaceBitmap.recycle();
                            }

                            final Bitmap callbackBitmap = captured;
                            runOnUiThread(() -> callback.onCaptured(callbackBitmap));
                            handlerThread.quitSafely();
                        },
                        new Handler(handlerThread.getLooper()));
                return;
            }

            if (rootContent == null || rootContent.getWidth() <= 0 || rootContent.getHeight() <= 0) {
                callback.onCaptured(null);
                return;
            }

            final Bitmap windowBitmap = Bitmap.createBitmap(rootContent.getWidth(), rootContent.getHeight(), Bitmap.Config.ARGB_8888);
            final HandlerThread handlerThread = new HandlerThread("savestate-pre-capture");
            handlerThread.start();
            PixelCopy.request(
                    getWindow(),
                    windowBitmap,
                    result -> {
                        Bitmap captured = null;
                        if (result == PixelCopy.SUCCESS) {
                            captured = windowBitmap;
                        } else {
                            windowBitmap.recycle();
                        }

                        final Bitmap callbackBitmap = captured;
                        runOnUiThread(() -> callback.onCaptured(callbackBitmap));
                        handlerThread.quitSafely();
                    },
                    new Handler(handlerThread.getLooper()));
            return;
        }

        try {
            Bitmap fallbackBitmap = Bitmap.createBitmap(rootContent.getWidth(), rootContent.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(fallbackBitmap);
            rootContent.draw(canvas);
            callback.onCaptured(fallbackBitmap);
        } catch (Throwable t) {
            Log.e(TAG, "Fallback pre-save capture failed", t);
            callback.onCaptured(null);
        }
    }

    private void startManualSaveState(int targetSlot, String targetStatePath, Bitmap capturedThumbnail) {
        final Bitmap slotThumbnail = capturedThumbnail;

        saveStateInProgress = true;
        showSaveStateProgressDialog();

        new Thread(() -> {
            boolean ok = false;
            try {
                java.io.File stateFile = new java.io.File(targetStatePath);
                long beforeModified = stateFile.exists() ? stateFile.lastModified() : 0L;
                ok = DOSBoxJNI.nativeSaveStateAndWait(12000);
                if (!ok && stateFile.exists() && stateFile.lastModified() > beforeModified) {
                    ok = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Manual save failed", t);
            }

            final boolean saveOk = ok;
            runOnUiThread(() -> {
                hideSaveStateProgressDialog();
                saveStateInProgress = false;
                if (saveOk) {
                    if (slotThumbnail != null) {
                        File thumbFile = SaveStateRepository.INSTANCE.getThumbnailFile(this, currentSaveStateGameId, targetSlot);
                        persistThumbnail(slotThumbnail, thumbFile);
                        slotThumbnail.recycle();
                    } else {
                        saveSlotThumbnailAsync(targetSlot);
                    }
                    android.widget.Toast.makeText(this, "State saved", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    if (slotThumbnail != null && !slotThumbnail.isRecycled()) {
                        slotThumbnail.recycle();
                    }
                    android.widget.Toast.makeText(this, "Save failed", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }, "savestate-save-thread").start();
    }

    private void showSaveStateProgressDialog() {
        if (saveStateProgressOverlay != null) {
            return;
        }

        ViewGroup rootView = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView == null) {
            Log.e(TAG, "showSaveStateProgressDialog: root view not found");
            return;
        }

        FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setBackgroundColor(0x66000000);

        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        overlay.setLayoutParams(overlayParams);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(24);
        content.setPadding(padding, padding, padding, padding);
        content.setGravity(Gravity.CENTER);
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(0xCC1F1F1F);
        cardBackground.setCornerRadius(dpToPx(12));
        content.setBackground(cardBackground);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = Gravity.CENTER;
        content.setLayoutParams(contentParams);

        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        spinner.setIndeterminate(true);
        content.addView(spinner);

        TextView message = new TextView(this);
        message.setText("Saving state...");
        message.setTextColor(Color.WHITE);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dpToPx(12);
        message.setLayoutParams(messageParams);
        content.addView(message);

        overlay.addView(content);
        if (!isFinishing() && !isDestroyed()) {
            rootView.addView(overlay);
            saveStateProgressOverlay = overlay;
        }
    }

    private void hideSaveStateProgressDialog() {
        if (saveStateProgressOverlay == null) {
            return;
        }

        ViewGroup rootView = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.removeView(saveStateProgressOverlay);
        }
        saveStateProgressOverlay = null;
    }

    private void saveSlotThumbnailAsync(int slot) {
        final File thumbFile = SaveStateRepository.INSTANCE.getThumbnailFile(this, currentSaveStateGameId, slot);
        final View rootContent = getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootContent == null || rootContent.getWidth() <= 0 || rootContent.getHeight() <= 0) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final Bitmap windowBitmap = Bitmap.createBitmap(rootContent.getWidth(), rootContent.getHeight(), Bitmap.Config.ARGB_8888);
            final HandlerThread handlerThread = new HandlerThread("savestate-thumb-copy");
            handlerThread.start();
            PixelCopy.request(
                    getWindow(),
                    windowBitmap,
                    result -> {
                        try {
                            if (result == PixelCopy.SUCCESS) {
                                persistThumbnail(windowBitmap, thumbFile);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "Failed to persist slot thumbnail", t);
                        } finally {
                            windowBitmap.recycle();
                            handlerThread.quitSafely();
                        }
                    },
                    new Handler(handlerThread.getLooper()));
            return;
        }

        try {
            Bitmap fallbackBitmap = Bitmap.createBitmap(rootContent.getWidth(), rootContent.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(fallbackBitmap);
            rootContent.draw(canvas);
            persistThumbnail(fallbackBitmap, thumbFile);
            fallbackBitmap.recycle();
        } catch (Throwable t) {
            Log.e(TAG, "Fallback thumbnail capture failed", t);
        }
    }

    private void persistThumbnail(Bitmap source, File targetFile) {
        if (source == null || targetFile == null) {
            return;
        }

        int targetWidth = 240;
        int targetHeight = 135;
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileOutputStream out = new FileOutputStream(targetFile)) {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
        } catch (Throwable t) {
            Log.e(TAG, "Failed writing thumbnail file: " + targetFile.getAbsolutePath(), t);
        } finally {
            scaled.recycle();
        }
    }

    private void openLoadSlotsDialog() {
        final DialogThemeColors theme = getDialogThemeColors();
        final java.util.List<com.codeodyssey.retrodriveaa.SaveStateRepository.SaveSlotInfo> slots =
                SaveStateRepository.INSTANCE.getSlots(this, currentSaveStateGameId);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(theme.cardBackground);
        cardBackground.setCornerRadius(dpToPx(20));
        card.setBackground(cardBackground);

        TextView title = new TextView(this);
        title.setText("Load State");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(theme.primaryText);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dpToPx(12));
        card.addView(title);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int listPadding = dpToPx(12);
        list.setPadding(listPadding, listPadding, listPadding, listPadding);

        scroll.addView(list);
        card.addView(scroll);

        ScrollView outerScroll = new ScrollView(this);
        outerScroll.addView(card);

        final Dialog dialog = new Dialog(this);
        dialog.setContentView(outerScroll);
        constrainDialogToScreen(dialog);

        for (com.codeodyssey.retrodriveaa.SaveStateRepository.SaveSlotInfo slotInfo : slots) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
                GradientDrawable rowBackground = new GradientDrawable();
                rowBackground.setColor(theme.cardBackground);
                rowBackground.setCornerRadius(dpToPx(14));
                if (theme.buttonBorder != Color.TRANSPARENT) {
                    rowBackground.setStroke(dpToPx(1), theme.buttonBorder);
                }
                row.setBackground(rowBackground);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = dpToPx(10);
                row.setLayoutParams(rowParams);

            ImageView thumb = new ImageView(this);
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dpToPx(72), dpToPx(40));
            thumb.setLayoutParams(thumbParams);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (slotInfo.getExists() && slotInfo.getThumbnailFile().exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(slotInfo.getThumbnailFile().getAbsolutePath());
                if (bmp != null) {
                    thumb.setImageBitmap(bmp);
                } else {
                    thumb.setImageResource(android.R.drawable.ic_menu_report_image);
                }
            } else {
                thumb.setImageResource(android.R.drawable.ic_menu_report_image);
                thumb.setAlpha(0.45f);
            }
            row.addView(thumb);

            TextView label = new TextView(this);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.leftMargin = dpToPx(10);
            label.setLayoutParams(labelParams);
            label.setTextColor(theme.primaryText);
            if (slotInfo.getExists()) {
                String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(slotInfo.getLastModified()));
                label.setText("Slot " + slotInfo.getSlot() + "\n" + when);
            } else {
                label.setText("Slot " + slotInfo.getSlot() + "\nEmpty");
                label.setAlpha(0.6f);
            }
            row.addView(label);

            View.OnClickListener loadSlotClick = v -> {
                requestManualLoadState(slotInfo.getStateFile().getAbsolutePath());
                dialog.dismiss();
            };

            if (slotInfo.getExists()) {
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(loadSlotClick);
                thumb.setOnClickListener(loadSlotClick);
                label.setOnClickListener(loadSlotClick);
            }

            ImageButton deleteButton = new ImageButton(this);
            deleteButton.setImageResource(android.R.drawable.ic_menu_delete);
            deleteButton.setBackgroundColor(Color.TRANSPARENT);
            deleteButton.setColorFilter(theme.primaryText);
            deleteButton.setContentDescription("Delete slot " + slotInfo.getSlot());
            deleteButton.setEnabled(slotInfo.getExists());
            deleteButton.setAlpha(slotInfo.getExists() ? 1.0f : 0.45f);
            deleteButton.setOnClickListener(v -> {
                boolean deleted = SaveStateRepository.INSTANCE.deleteSlot(this, currentSaveStateGameId, slotInfo.getSlot());
                if (deleted) {
                    android.widget.Toast.makeText(this, "Deleted slot " + slotInfo.getSlot(), android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    android.widget.Toast.makeText(this, "Delete failed", android.widget.Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
                openLoadSlotsDialog();
            });
            row.addView(deleteButton);

            list.addView(row);
        }

        dialog.show();
    }

    private void showSaveSlotsFullDialog() {
        final DialogThemeColors theme = getDialogThemeColors();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(theme.cardBackground);
        cardBackground.setCornerRadius(dpToPx(20));
        layout.setBackground(cardBackground);

        TextView title = new TextView(this);
        title.setText("Save slots full");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(theme.primaryText);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dpToPx(10));
        layout.addView(title);

        TextView message = new TextView(this);
        message.setText("All 5 save slots are used. Delete one slot from Load State to save again.");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setTextColor(theme.secondaryText);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, dpToPx(16));
        layout.addView(message);

        Button okButton = new Button(this);
        okButton.setText("OK");
        okButton.setTextColor(theme.primaryButtonText);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(theme.primaryButtonBg);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            okBg.setStroke(dpToPx(1), theme.buttonBorder);
        }
        okBg.setCornerRadius(dpToPx(26));
        okButton.setBackground(okBg);
        layout.addView(okButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scrollWrapper = new ScrollView(this);
        scrollWrapper.addView(layout);

        Dialog dialog = new Dialog(this);
        dialog.setContentView(scrollWrapper);
        constrainDialogToScreen(dialog);

        okButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void requestManualLoadState(String statePath) {
        File selectedState = new File(statePath);
        if (!selectedState.exists() || selectedState.length() <= 0L) {
            android.widget.Toast.makeText(this, "No saved state", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            DOSBoxJNI.nativeSetSaveStateContext(currentSaveStateGameId, statePath);
            DOSBoxJNI.nativeRequestLoadState();
            android.widget.Toast.makeText(this, "State load requested", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "Manual load request failed", t);
            android.widget.Toast.makeText(this, "Load failed", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Add exit button in top-right corner
     */
    private void addExitButton() {
        try {
            ViewGroup rootView = (ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) {
                Log.e(TAG, "Could not find root view for exit button");
                return;
            }
            
            // Dynamically shrink exit FAB if not enough height
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            float density = getResources().getDisplayMetrics().density;
            int minFabSize = (int) (40 * density); // Minimum 40dp
            int maxFabSize = (int) (56 * density); // 56dp
            int fabCount = 4;
            int neededHeight = fabCount * maxFabSize + (fabCount - 1) * (int)(12 * density) + (int)(32 * density);
            int fabSize = maxFabSize;
            if (neededHeight > screenHeight * 0.9) {
                float scale = (screenHeight * 0.9f - (int)(32 * density)) / (fabCount * maxFabSize + (fabCount - 1) * (int)(12 * density));
                fabSize = Math.max(minFabSize, (int)(maxFabSize * scale));
            }
            
            // Create exit button (RED) with X icon
            exitButton = createFABWithIcon(0xCCF44336, fabSize, "✕"); // Red with X icon
            exitButton.setOnClickListener(v -> exitDOSBox());
            
            // Position in top-right corner with dynamic top margin based on system insets
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.TOP | Gravity.END;
            
            // Get actual system insets (status bar height) dynamically
            View decorView = getWindow().getDecorView();
            int topInset = 0;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.view.WindowInsets insets = decorView.getRootWindowInsets();
                if (insets != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        topInset = insets.getInsets(android.view.WindowInsets.Type.systemBars()).top;
                    } else {
                        topInset = insets.getSystemWindowInsetTop();
                    }
                }
            }
            
            // Fallback if insets not available + small additional margin
            int topMargin = topInset > 0 ? topInset + (int)(8 * density) : (int)(40 * density);
            int rightMargin = (int) (16 * getResources().getDisplayMetrics().density); // 16dp right margin
            
            params.setMargins(0, topMargin, rightMargin, 0);
            exitButton.setLayoutParams(params);
            
            Log.d(TAG, "Exit button positioned with dynamic top margin: " + topMargin + "px (" +
                  (topMargin / getResources().getDisplayMetrics().density) + "dp), system inset: " + topInset + "px");
            
            // Add to root view
            rootView.addView(exitButton);
            
            Log.d(TAG, "Exit button added successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding exit button", e);
        }
    }
    
    /**
     * Exit DOSBox and return to main menu
     */
    private void exitDOSBox() {
        Log.d(TAG, "Exiting DOSBox...");
        stopWifiServer();
        try {
            // Request SDL shutdown
            SDLActivity.nativeQuit();
            // Wait briefly for SDL thread to exit
            new android.os.Handler().postDelayed(() -> {
                finish();
            }, 300);
        } catch (Exception e) {
            Log.e(TAG, "Error during SDL shutdown", e);
            finish();
        }
    }
    
    /**
     * OnDPadEventListener implementation - Inject keys into DOSBox
     */
    @Override
    public void onDPadPress(int direction, int keyCode) {
        Log.d(TAG, "D-Pad press: direction=" + direction + ", keyCode=" + keyCode);
        
        // Create key down event
        KeyEvent downEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        
        // Process through InputDirector first
        if (inputDirector != null && inputDirector.processKeyEvent(downEvent)) {
            return;
        }
        
        // Fallback to SDL's default handling
        super.dispatchKeyEvent(downEvent);
    }
    
    @Override
    public void onDPadRelease(int direction, int keyCode) {
        Log.d(TAG, "D-Pad release: direction=" + direction + ", keyCode=" + keyCode);
        
        // Create key up event
        KeyEvent upEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        
        // Process through InputDirector first
        if (inputDirector != null && inputDirector.processKeyEvent(upEvent)) {
            return;
        }
        
        // Fallback to SDL's default handling
        super.dispatchKeyEvent(upEvent);
    }

    /**
     * OnButtonEventListener implementation - Inject keys into DOSBox
     */
    @Override
    public void onButtonPress(int button, int keyCode) {
        Log.d(TAG, "Action button press: button=" + button + ", keyCode=" + keyCode);
        
        // Create key down event
        KeyEvent downEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        
        // Process through InputDirector first
        if (inputDirector != null && inputDirector.processKeyEvent(downEvent)) {
            return;
        }
        
        // Fallback to SDL's default handling
        super.dispatchKeyEvent(downEvent);
    }
    
    @Override
    public void onButtonRelease(int button, int keyCode) {
        Log.d(TAG, "Action button release: button=" + button + ", keyCode=" + keyCode);
        
        // Create key up event
        KeyEvent upEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        
        // Process through InputDirector first
        if (inputDirector != null && inputDirector.processKeyEvent(upEvent)) {
            return;
        }
        
        // Fallback to SDL's default handling
        super.dispatchKeyEvent(upEvent);
    }

    // Handle generic motion events (for rotary controllers and physical mice)
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (inputDirector != null && inputDirector.processGenericEvent(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
    
    // Re-detect input mode when configuration changes
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (inputDirector != null) {
            inputDirector.autoDetectMode(this);
            Log.d(TAG, "Configuration changed - Input mode: " + inputDirector.getCurrentModeName());
        }
        updateVirtualGamepadLayout();
    }

    public void toggleOnScreenKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        
        // Check if mTextEdit (SDL's keyboard input view) is currently visible
        if (mTextEdit != null && mTextEdit.getVisibility() == View.VISIBLE) {
            // Hide keyboard
            imm.hideSoftInputFromWindow(mTextEdit.getWindowToken(), 0);
            mTextEdit.setVisibility(View.GONE);
        } else {
            // Show keyboard using SDL's text input mechanism
            // This ensures keyboard events are properly captured and forwarded to DOSBox
            // Use screen dimensions for the input area (position doesn't matter for full-screen input)
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            
            // Call SDL's showTextInput to properly initialize the DummyEdit view
            // This view has the proper InputConnection to forward keyboard events to SDL/DOSBox
            showTextInput(0, screenHeight - 1, screenWidth, 1);
        }
    }

	public void copyAssetAll(String srcPath) {
		AssetManager assetMgr = this.getAssets();
		String assets[] = null;
		try {
			String destPath = getExternalFilesDir(null) + File.separator + srcPath;
			assets = assetMgr.list(srcPath);
			if (assets.length == 0) {
				copyFile(srcPath, destPath);
			} else {
				File dir = new File(destPath);
				if (!dir.exists())
					dir.mkdir();
				for (String element : assets) {
					copyAssetAll(srcPath + File.separator + element);
				}
			}
		} 
		catch (IOException e) {
		   e.printStackTrace();
		}
	}
	
	public void copyFile(String srcFile, String destFile) {
		AssetManager assetMgr = this.getAssets();	  
		InputStream is = null;
		OutputStream os = null;
		try {
			is = assetMgr.open(srcFile);
            File outputFile = new File(destFile);
            if (!outputFile.exists() || shouldOverwriteAsset(srcFile))
			{
                os = new FileOutputStream(outputFile, false);		  
				byte[] buffer = new byte[1024];
				int read;
				while ((read = is.read(buffer)) != -1) {
					os.write(buffer, 0, read);
				}
				is.close();
				os.flush();
				os.close();
				Log.v(TAG, "copy from Asset:" + destFile);
			}
		} 
		catch (IOException e) {
			e.printStackTrace();
		}
	}

    private boolean shouldOverwriteAsset(String srcFile) {
        return "dosbox.conf".equals(srcFile) || "dosbox_base.conf".equals(srcFile);
    }
	
	/**
	 * Ensure the game directory exists for DOSBox to mount
	 */
	private void ensureGameDirectoryExists() {
		try {
			File gameDir = new File(getExternalFilesDir(null), "game");
			if (!gameDir.exists()) {
				boolean created = gameDir.mkdirs();
				Log.d(TAG, "Game directory created: " + created + " at " + gameDir.getAbsolutePath());
			} else {
				Log.d(TAG, "Game directory exists at " + gameDir.getAbsolutePath());
			}
		} catch (Exception e) {
			Log.e(TAG, "Error ensuring game directory exists", e);
		}
	}
	
    // Remote Controller Methods
	
	public void toggleWifiController() {
		if (isWifiServerRunning) {
			stopWifiServer();
		} else {
			startWifiServer();
		}
	}
	
	private void startWifiServer() {
        // Always stop any running WifiControllerServer before starting a new one
        stopWifiServer();
        if (!hasExternalInternetConnection()) {
            showInternetRequiredDialog("Remote Controller");
            return;
        }
        try {
            // Start local HTTP page server + Cloudflare relay socket (role=car)
            wifiServer = new WifiControllerServer(this, BuildConfig.CONTROLLER_WS_BASE_URL);
            isWifiServerRunning = true;

            // Update button color to indicate server is running
            if (wifiButton != null) {
                wifiButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xCC4CAF50)); // Green
            }

            // Show QR code dialog (hosted controller.html, Cloudflare transport)
            String url = wifiServer.buildHostedControllerUrl(BuildConfig.CONTROLLER_WEB_BASE_URL);
            showQRCodeDialog(url);

            Log.d(TAG, "Remote controller started at " + url);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start remote controller server", e);
            showInfoCardDialog("Server Error", "Failed to start remote controller: " + e.getMessage());
        }
	}
	
	private void stopWifiServer() {
		if (!isWifiServerRunning) {
			return; // Already stopped
		}
		
		try {
			if (wifiServer != null) {
                Log.d(TAG, "Stopping remote controller server...");
				wifiServer.stop();
				wifiServer = null;
			}
			isWifiServerRunning = false;
			
			// Update button color to indicate server is stopped
			if (wifiButton != null && !isFinishing()) {
				try {
					wifiButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xCC9C27B0)); // Purple
				} catch (Exception e) {
                    Log.w(TAG, "Failed to update remote button color", e);
				}
			}
			
            Log.d(TAG, "Remote controller server stopped");
		} catch (Exception e) {
            Log.e(TAG, "Error stopping remote server", e);
		}
	}

    private boolean hasExternalInternetConnection() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }

        android.net.Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) {
            return false;
        }

        boolean hasRequiredTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);

        return hasRequiredTransport
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void showInternetRequiredDialog(String featureName) {
        showInfoCardDialog(
                "Internet Required",
                featureName + " needs internet access. Connect the car to mobile data or another internet-enabled network and try again.");
    }

    private void showInfoCardDialog(String titleText, String messageText) {
        final DialogThemeColors theme = getDialogThemeColors();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20));

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(theme.cardBackground);
        cardBackground.setCornerRadius(dpToPx(20));
        layout.setBackground(cardBackground);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(theme.primaryText);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dpToPx(10));
        layout.addView(title);

        TextView message = new TextView(this);
        message.setText(messageText);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        message.setTextColor(theme.secondaryText);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, dpToPx(16));
        layout.addView(message);

        Button okButton = new Button(this);
        okButton.setText("OK");
        okButton.setTextColor(theme.primaryButtonText);
        GradientDrawable okBg = new GradientDrawable();
        okBg.setColor(theme.primaryButtonBg);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            okBg.setStroke(dpToPx(1), theme.buttonBorder);
        }
        okBg.setCornerRadius(dpToPx(26));
        okButton.setBackground(okBg);
        layout.addView(okButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scrollWrapper = new ScrollView(this);
        scrollWrapper.addView(layout);

        Dialog dialog = new Dialog(this);
        dialog.setContentView(scrollWrapper);
        constrainDialogToScreen(dialog);
        okButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
	
	private Bitmap generateQRCode(String text, int width, int height) {
		try {
			QRCodeWriter writer = new QRCodeWriter();
			BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height);
			
			Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
			for (int x = 0; x < width; x++) {
				for (int y = 0; y < height; y++) {
					bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
				}
			}
			return bitmap;
		} catch (WriterException e) {
			Log.e(TAG, "Failed to generate QR code", e);
			return null;
		}
	}
	
    private void showQRCodeDialog(String url) {
		// Generate QR code
        Bitmap qrBitmap = generateQRCode(url, 300, 300);
		if (qrBitmap == null) {
			return;
		}

		DialogThemeColors theme = getDialogThemeColors();
		
		// Create scrollable dialog layout
		ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
		
		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 28, 36, 24);

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(theme.cardBackground);
        cardBackground.setCornerRadius(42f);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            cardBackground.setStroke(2, theme.buttonBorder);
        }
        layout.setBackground(cardBackground);
		
		// Add title
		TextView title = new TextView(this);
        title.setText("Remote Controller");
        title.setTextSize(21);
		title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(theme.primaryText);
		title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, 18);
		layout.addView(title);
		
		// Add instructions
		TextView instructions = new TextView(this);
        instructions.setText("Scan the QR code to open the cloud controller.");
		instructions.setTextSize(13);
        instructions.setTextColor(theme.secondaryText);
		instructions.setGravity(android.view.Gravity.CENTER);
        instructions.setPadding(0, 0, 0, 14);
		layout.addView(instructions);
		
		// Add QR code image
		ImageView qrImage = new ImageView(this);
		qrImage.setImageBitmap(qrBitmap);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(300, 300);
		qrParams.gravity = android.view.Gravity.CENTER;
        qrParams.setMargins(0, 8, 0, 14);
		qrImage.setLayoutParams(qrParams);
		layout.addView(qrImage);
        TextView statusText = new TextView(this);
        statusText.setText("Controller is ready.");
        statusText.setTextSize(12);
        statusText.setTextColor(theme.secondaryText);
        statusText.setGravity(android.view.Gravity.CENTER);
        statusText.setPadding(0, 4, 0, 14);
        layout.addView(statusText);

        // Add action buttons inside the card
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionsParams.setMargins(0, 4, 0, 0);
        actionsRow.setLayoutParams(actionsParams);

        Button keepRunningButton = new Button(this);
        keepRunningButton.setText("Keep Running");
        keepRunningButton.setTextColor(theme.secondaryButtonText);
        GradientDrawable keepBg = new GradientDrawable();
        keepBg.setColor(theme.secondaryButtonBg);
        keepBg.setCornerRadius(26f);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            keepBg.setStroke(2, theme.buttonBorder);
        }
        keepRunningButton.setBackground(keepBg);

        Button stopButton = new Button(this);
        stopButton.setText("Stop Server");
        stopButton.setTextColor(Color.WHITE);
        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setColor(Color.parseColor("#DC2626"));
        stopBg.setCornerRadius(26f);
        stopButton.setBackground(stopBg);

        LinearLayout.LayoutParams leftBtnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        leftBtnParams.setMargins(0, 0, 10, 0);
        LinearLayout.LayoutParams rightBtnParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rightBtnParams.setMargins(10, 0, 0, 0);
        keepRunningButton.setLayoutParams(leftBtnParams);
        stopButton.setLayoutParams(rightBtnParams);

        actionsRow.addView(keepRunningButton);
        actionsRow.addView(stopButton);
        layout.addView(actionsRow);
		
		// Add layout to scroll view
		scrollView.addView(layout);
		
        // Show dialog without default AlertDialog frame/chrome
        Dialog dialog = new Dialog(this);
        dialog.setContentView(scrollView);
        constrainDialogToScreen(dialog);
        dialog.show();

        keepRunningButton.setOnClickListener(v -> dialog.dismiss());
        stopButton.setOnClickListener(v -> {
            stopWifiServer();
            dialog.dismiss();
        });
	}
	
	@Override
	protected void onPause() {
        Log.d(TAG, "onPause() - stopping remote server");
		stopWifiServer();
		super.onPause();
	}
	
	@Override
	protected void onDestroy() {
		Log.d(TAG, "onDestroy()");
		stopWifiServer(); // Ensure server is stopped if not stopped in onPause
		super.onDestroy();
	}
	
	// WifiControllerServer.ControllerEventListener implementation
	
	@Override
	public void onControllerKeyEvent(int keyCode, boolean pressed) {
		Log.i(TAG, "========== onControllerKeyEvent CALLED ==========");
		Log.i(TAG, "KeyCode: " + keyCode + ", Pressed: " + pressed);
		Log.i(TAG, "InputDirector null? " + (inputDirector == null));
		
		// Route to InputDirector for handling
		if (inputDirector != null) {
			int action = pressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
			Log.i(TAG, "Creating KeyEvent with action: " + action);
			KeyEvent event = new KeyEvent(action, keyCode);
			Log.i(TAG, "Calling inputDirector.processKeyEvent()");
			inputDirector.processKeyEvent(event);
			Log.i(TAG, "processKeyEvent() completed");
		} else {
			Log.e(TAG, "ERROR: inputDirector is NULL! Cannot process key event!");
		}
		Log.i(TAG, "=================================================");
	}
	
	// Simple pass-through approach - just like on-device touchpad
	private long lastMouseEventTime = 0;
    private long lastJoystickMouseEventTime = 0;
	
	@Override
	public void onControllerMouseMove(int dx, int dy) {
		long now = System.currentTimeMillis();
		
		// Check for lag spikes (events arriving after long delay = stale queue)
		// But only after we've received at least one event
		if (lastMouseEventTime > 0) {
			long timeSinceLastEvent = now - lastMouseEventTime;
			if (timeSinceLastEvent > 100) {
				// Large gap detected - drop queued events until we get fresh ones
				Log.d(TAG, "Lag spike detected (" + timeSinceLastEvent + "ms), dropping event");
				lastMouseEventTime = now;
				return;
			}
		}
		
		lastMouseEventTime = now;
		
		// Ignore very tiny movements (noise)
		if (Math.abs(dx) < 3 && Math.abs(dy) < 3) {
			return;
		}
		
        // Simple pass-through with reduced sensitivity (half of previous value)
        final float multipliedDx = dx * 1.0f;
        final float multipliedDy = dy * 1.0f;
		
		Log.v(TAG, "Mouse: dx=" + (int)multipliedDx + ", dy=" + (int)multipliedDy);
		
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				// Use new relative mouse motion method
				try {
					org.libsdl.app.SDLActivity.onNativeMouseRelative(multipliedDx, multipliedDy);
				} catch (Exception e) {
					Log.e(TAG, "Error sending mouse movement", e);
				}
			}
		});
	}
	
	@Override
	public void onControllerMouseButton(int button, boolean pressed) {
		Log.i(TAG, "========== onControllerMouseButton CALLED ==========");
		Log.i(TAG, "Button: " + button + ", Pressed: " + pressed);
		Log.i(TAG, "Thread: " + Thread.currentThread().getName());
		
		// Map web button numbers to SDL button indices
		// Web: 1=left, 2=right -> SDL: 0=left, 1=right, 2=middle
		final int sdlButton = button - 1 < 0 ? 0 : button - 1;
		final boolean finalPressed = pressed;
		
		Log.i(TAG, "Calling NativeBridge.sendMouseButton: button=" + sdlButton + ", pressed=" + pressed);
		
		// CRITICAL: SDL mouse events must be sent from UI thread!
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				com.dosbox.emu.input.NativeBridge.sendMouseButton(sdlButton, finalPressed);
				Log.d(TAG, "sendMouseButton completed from UI thread");
			}
		});
		
		Log.i(TAG, "===================================================");
	}
	
	@Override
	public void onTrackpadEnd() {
		// Reset timing to force drop of any queued events
		lastMouseEventTime = 0;
		Log.d(TAG, "Trackpad end - will drop queued events");
	}
	
	@Override
	public void onControllerJoystick(float x, float y, long timestamp) {
        // Fallback mode: emulate trackpad-style relative mouse movement from joystick axis.
        // This reuses the proven mouse path when DOS joystick compatibility is unreliable.
        final long now = System.currentTimeMillis();
        if (lastJoystickMouseEventTime == 0) {
            lastJoystickMouseEventTime = now;
        }
        long dt = now - lastJoystickMouseEventTime;
        lastJoystickMouseEventTime = now;
        if (dt < 1) dt = 1;
        if (dt > 50) dt = 50;

        final float magnitude = (float) Math.sqrt(x * x + y * y);
        final float deadzone = 0.12f;
        if (magnitude < deadzone) {
            return;
        }

        // Reduced joystick-as-trackpad speed (half of previous value)
        final float speed = 11.0f;
        final float timeScale = dt / 16.0f;
        final float dx = x * speed * timeScale;
        final float dy = y * speed * timeScale;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    org.libsdl.app.SDLActivity.onNativeMouseRelative(dx, dy);
                } catch (Exception e) {
                    Log.e(TAG, "Error sending joystick-as-trackpad movement", e);
                }
            }
        });
	}

    @Override
    public void onControllerTextLine(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        final String lineToSend = text + "\n";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (inputDirector == null) {
                        Log.e(TAG, "inputDirector is NULL! Cannot process text line.");
                        return;
                    }
                    KeyCharacterMap characterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
                    KeyEvent[] events = characterMap.getEvents(lineToSend.toCharArray());
                    if (events != null) {
                        for (KeyEvent event : events) {
                            inputDirector.processKeyEvent(event);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to process text line input", e);
                }
            }
        });
    }
}

