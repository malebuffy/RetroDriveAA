package com.dosbox.emu;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Configuration dialog for Virtual D-Pad key mappings
 * Allows user to assign any key to each direction
 */
public class DPadConfigDialog {
    private static final String TAG = "DPadConfigDialog";
    private static final String UI_PREFS_NAME = "retrodrive_ui_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";
    
    private Context context;
    private String gameFolder;
    private OnConfigChangedListener listener;
    
    // UI components
    private TextView upKeyLabel;
    private TextView downKeyLabel;
    private TextView leftKeyLabel;
    private TextView rightKeyLabel;
    private TextView aKeyLabel;
    private TextView bKeyLabel;
    private TextView xKeyLabel;
    private TextView yKeyLabel;

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
        String themeMode = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
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
    
    public interface OnConfigChangedListener {
        void onConfigChanged();
    }
    
    public DPadConfigDialog(Context context, String gameFolder) {
        this.context = context;
        this.gameFolder = gameFolder;
    }
    
    private String getKeyPrefix() {
        // Use game-specific key prefix if game folder is set
        return (gameFolder != null && !gameFolder.isEmpty()) ? "game_" + gameFolder + "_" : "";
    }
    
    public void setOnConfigChangedListener(OnConfigChangedListener listener) {
        this.listener = listener;
    }
    
    public void show() {
        final DialogThemeColors theme = getDialogThemeColors();

        // Outer card — static container with background, title at top, buttons at bottom
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(48, 40, 48, 32);

        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(theme.cardBackground);
        cardBackground.setCornerRadius(42f);
        card.setBackground(cardBackground);
        
        // Title (fixed at top of card)
        TextView title = new TextView(context);
        String titleText = "Configure Controls";
        if (gameFolder != null && !gameFolder.isEmpty()) {
            titleText += " - " + gameFolder;
        } else {
            titleText += " (Global)";
        }
        title.setText(titleText);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(theme.primaryText);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 32);
        card.addView(title);

        // Scrollable content area (only this part scrolls)
        ScrollView scrollView = new ScrollView(context);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollView.setLayoutParams(scrollParams);

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        
        // Load current mappings
        SharedPreferences dpadPrefs = context.getSharedPreferences("dpad_config", Context.MODE_PRIVATE);
        SharedPreferences buttonPrefs = context.getSharedPreferences("action_buttons_config", Context.MODE_PRIVATE);
        String prefix = getKeyPrefix();
        
        int keyUp = dpadPrefs.getInt(prefix + VirtualDPadView.PREF_KEY_UP, KeyEvent.KEYCODE_DPAD_UP);
        int keyDown = dpadPrefs.getInt(prefix + VirtualDPadView.PREF_KEY_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
        int keyLeft = dpadPrefs.getInt(prefix + VirtualDPadView.PREF_KEY_LEFT, KeyEvent.KEYCODE_DPAD_LEFT);
        int keyRight = dpadPrefs.getInt(prefix + VirtualDPadView.PREF_KEY_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT);
        int keyA = buttonPrefs.getInt(prefix + VirtualButtonsView.PREF_KEY_A, KeyEvent.KEYCODE_ENTER);
        int keyB = buttonPrefs.getInt(prefix + VirtualButtonsView.PREF_KEY_B, KeyEvent.KEYCODE_ESCAPE);
        int keyX = buttonPrefs.getInt(prefix + VirtualButtonsView.PREF_KEY_X, KeyEvent.KEYCODE_SPACE);
        int keyY = buttonPrefs.getInt(prefix + VirtualButtonsView.PREF_KEY_Y, KeyEvent.KEYCODE_SHIFT_LEFT);
        
        // D-Pad section header
        TextView dpadHeader = new TextView(context);
        dpadHeader.setText("D-Pad Arrows");
        dpadHeader.setTextSize(16);
        dpadHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        dpadHeader.setTextColor(theme.primaryText);
        dpadHeader.setPadding(0, 8, 0, 16);
        contentLayout.addView(dpadHeader);
        
        // UP configuration
        contentLayout.addView(createDirectionRow("UP", keyUp, "dpad_config", VirtualDPadView.PREF_KEY_UP, 
                                          v -> upKeyLabel = (TextView) v));
        addSpacer(contentLayout);
        
        // DOWN configuration
        contentLayout.addView(createDirectionRow("DOWN", keyDown, "dpad_config", VirtualDPadView.PREF_KEY_DOWN,
                                          v -> downKeyLabel = (TextView) v));
        addSpacer(contentLayout);
        
        // LEFT configuration
        contentLayout.addView(createDirectionRow("LEFT", keyLeft, "dpad_config", VirtualDPadView.PREF_KEY_LEFT,
                                          v -> leftKeyLabel = (TextView) v));
        addSpacer(contentLayout);
        
        // RIGHT configuration
        contentLayout.addView(createDirectionRow("RIGHT", keyRight, "dpad_config", VirtualDPadView.PREF_KEY_RIGHT,
                                          v -> rightKeyLabel = (TextView) v));
        
        addSpacer(contentLayout);
        addSpacer(contentLayout);
        
        // Action Buttons section header
        TextView buttonsHeader = new TextView(context);
        buttonsHeader.setText("Action Buttons");
        buttonsHeader.setTextSize(16);
        buttonsHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        buttonsHeader.setTextColor(theme.primaryText);
        buttonsHeader.setPadding(0, 8, 0, 16);
        contentLayout.addView(buttonsHeader);
        
        // A button configuration
        contentLayout.addView(createDirectionRow("A", keyA, "action_buttons_config", VirtualButtonsView.PREF_KEY_A,
                                          v -> aKeyLabel = (TextView) v));
        addSpacer(contentLayout);
        
        // B button configuration
        contentLayout.addView(createDirectionRow("B", keyB, "action_buttons_config", VirtualButtonsView.PREF_KEY_B,
                                          v -> bKeyLabel = (TextView) v));
        addSpacer(contentLayout);
        
        // X button configuration
        contentLayout.addView(createDirectionRow("X", keyX, "action_buttons_config", VirtualButtonsView.PREF_KEY_X,
                                          v -> xKeyLabel = (TextView) v));
        addSpacer(contentLayout);
        
        // Y button configuration
        contentLayout.addView(createDirectionRow("Y", keyY, "action_buttons_config", VirtualButtonsView.PREF_KEY_Y,
                                          v -> yKeyLabel = (TextView) v));
        
        scrollView.addView(contentLayout);
        card.addView(scrollView);

        // Action buttons (fixed at bottom of card)
        LinearLayout actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER);
        actionsRow.setPadding(0, 18, 0, 0);

        Button resetButton = new Button(context);
        resetButton.setText("Reset to Defaults");
        resetButton.setTextColor(theme.secondaryButtonText);
        GradientDrawable resetBg = new GradientDrawable();
        resetBg.setColor(theme.secondaryButtonBg);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            resetBg.setStroke(2, theme.buttonBorder);
        }
        resetBg.setCornerRadius(26f);
        resetButton.setBackground(resetBg);

        Button doneButton = new Button(context);
        doneButton.setText("Done");
        doneButton.setTextColor(theme.primaryButtonText);
        GradientDrawable doneBg = new GradientDrawable();
        doneBg.setColor(theme.primaryButtonBg);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            doneBg.setStroke(2, theme.buttonBorder);
        }
        doneBg.setCornerRadius(26f);
        doneButton.setBackground(doneBg);

        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        resetParams.setMargins(0, 0, 10, 0);
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        doneParams.setMargins(10, 0, 0, 0);
        resetButton.setLayoutParams(resetParams);
        doneButton.setLayoutParams(doneParams);

        actionsRow.addView(resetButton);
        actionsRow.addView(doneButton);
        card.addView(actionsRow);

        Dialog dialog = new Dialog(context);
        dialog.setContentView(card);
        constrainDialogToScreen(dialog);

        resetButton.setOnClickListener(v -> {
            resetToDefaults();
            if (listener != null) {
                listener.onConfigChanged();
            }
        });

        doneButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onConfigChanged();
            }
            dialog.dismiss();
        });

        dialog.show();
    }
    
    private LinearLayout createDirectionRow(String directionName, int currentKeyCode, 
                                            String prefsName, String prefKey, KeyLabelSetter labelSetter) {
        final DialogThemeColors theme = getDialogThemeColors();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        row.setLayoutParams(rowParams);
        
        // Direction label
        TextView dirLabel = new TextView(context);
        dirLabel.setText(directionName + ":");
        dirLabel.setTextSize(16);
        dirLabel.setTextColor(theme.primaryText);
        dirLabel.setMinWidth(120);
        row.addView(dirLabel);
        
        // Current key label
        TextView keyLabel = new TextView(context);
        keyLabel.setText("[ " + getKeyName(currentKeyCode) + " ]");
        keyLabel.setTextSize(16);
        keyLabel.setTextColor(theme.secondaryText);
        keyLabel.setPadding(16, 0, 16, 0);
        LinearLayout.LayoutParams keyLabelParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
        );
        keyLabel.setLayoutParams(keyLabelParams);
        row.addView(keyLabel);
        labelSetter.setLabel(keyLabel);
        
        // Assign button
        Button assignButton = new Button(context);
        float density = context.getResources().getDisplayMetrics().density;
        int assignSizePx = Math.round(54f * density);
        int assignGapPx = Math.round(8f * density);
        assignButton.setText("?");
        assignButton.setTextSize(20f);
        assignButton.setTypeface(null, Typeface.BOLD);
        assignButton.setAllCaps(false);
        assignButton.setPadding(0, 0, 0, 0);
        assignButton.setGravity(Gravity.CENTER);
        assignButton.setTextColor(theme.secondaryButtonText);
        GradientDrawable assignBg = new GradientDrawable();
        assignBg.setColor(theme.secondaryButtonBg);
        assignBg.setShape(GradientDrawable.OVAL);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            assignBg.setStroke(2, theme.buttonBorder);
        }
        assignButton.setBackground(assignBg);
        LinearLayout.LayoutParams assignParams = new LinearLayout.LayoutParams(assignSizePx, assignSizePx);
        assignParams.setMargins(assignGapPx, 0, 0, 0);
        assignButton.setLayoutParams(assignParams);
        assignButton.setOnClickListener(v -> showKeyCapture(directionName, prefsName, prefKey, keyLabel));
        row.addView(assignButton);
        
        return row;
    }
    
    private void addSpacer(LinearLayout layout) {
        View spacer = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                16
        );
        spacer.setLayoutParams(params);
        layout.addView(spacer);
    }
    
    private void showKeyCapture(String directionName, String prefsName, String prefKey, TextView labelToUpdate) {
        final DialogThemeColors theme = getDialogThemeColors();
        // Create visible EditText to trigger keyboard
        EditText keyInput = new EditText(context);
        keyInput.setHint("Waiting for key press...");
        keyInput.setFocusable(false); // Make it non-editable
        keyInput.setFocusableInTouchMode(false);
        keyInput.setCursorVisible(false);
        
        // Create capture dialog
        LinearLayout captureLayout = new LinearLayout(context);
        captureLayout.setOrientation(LinearLayout.VERTICAL);
        captureLayout.setPadding(48, 48, 48, 48);
        captureLayout.setFocusableInTouchMode(true);
        captureLayout.requestFocus();
        GradientDrawable captureBg = new GradientDrawable();
        captureBg.setColor(theme.cardBackground);
        captureBg.setCornerRadius(30f);
        captureLayout.setBackground(captureBg);
        
        TextView instruction = new TextView(context);
        instruction.setText("Press any key for " + directionName + "...");
        instruction.setTextSize(18);
        instruction.setTextColor(theme.primaryText);
        instruction.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        captureLayout.addView(instruction);
        
        addSpacer(captureLayout);
        captureLayout.addView(keyInput);

        addSpacer(captureLayout);

        Button cancelButton = new Button(context);
        cancelButton.setText("Cancel");
        cancelButton.setTextColor(theme.secondaryButtonText);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(theme.secondaryButtonBg);
        if (theme.buttonBorder != Color.TRANSPARENT) {
            cancelBg.setStroke(2, theme.buttonBorder);
        }
        cancelBg.setCornerRadius(26f);
        cancelButton.setBackground(cancelBg);
        captureLayout.addView(cancelButton, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        
        Dialog captureDialog = new Dialog(context);
        captureDialog.setContentView(captureLayout);
        constrainDialogToScreen(captureDialog);
        captureDialog.setCancelable(true);
        cancelButton.setOnClickListener(v -> captureDialog.dismiss());
        
        // Capture key events on the dialog itself
        captureDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK) {
                // Capture the key!
                Log.d(TAG, "Captured key: " + keyCode + " (" + getKeyName(keyCode) + ")");
                
                // Save to preferences with game-specific prefix
                SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
                String fullKey = getKeyPrefix() + prefKey;
                prefs.edit().putInt(fullKey, keyCode).apply();
                
                // Update label
                labelToUpdate.setText("[ " + getKeyName(keyCode) + " ]");
                
                // Show feedback
                Toast.makeText(context, directionName + " mapped to " + getKeyName(keyCode), 
                              Toast.LENGTH_SHORT).show();
                
                // Dismiss capture dialog
                captureDialog.dismiss();
                
                return true;
            }
            return false;
        });
        
        captureDialog.show();
        
        // Hide keyboard when dialog is dismissed
        captureDialog.setOnDismissListener(dialog -> {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && captureDialog.getWindow() != null) {
                imm.hideSoftInputFromWindow(captureDialog.getWindow().getDecorView().getWindowToken(), 0);
            }
        });
        
        // Show keyboard after dialog is shown
        captureDialog.getWindow().getDecorView().postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        }, 300);
    }
    
    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Constrains a card-style dialog to at most 85% of screen height.
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
                        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
                        int maxH = (int) (dm.heightPixels * 0.85);
                        if (w.getDecorView().getHeight() > maxH) {
                            w.setLayout(w.getDecorView().getWidth(), maxH);
                        }
                    }
                }
        );
    }

    private void resetToDefaults() {
        String prefix = getKeyPrefix();
        
        // Reset D-Pad
        SharedPreferences dpadPrefs = context.getSharedPreferences("dpad_config", Context.MODE_PRIVATE);
        SharedPreferences.Editor dpadEditor = dpadPrefs.edit();
        dpadEditor.putInt(prefix + VirtualDPadView.PREF_KEY_UP, KeyEvent.KEYCODE_DPAD_UP);
        dpadEditor.putInt(prefix + VirtualDPadView.PREF_KEY_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
        dpadEditor.putInt(prefix + VirtualDPadView.PREF_KEY_LEFT, KeyEvent.KEYCODE_DPAD_LEFT);
        dpadEditor.putInt(prefix + VirtualDPadView.PREF_KEY_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT);
        dpadEditor.apply();
        
        // Reset Action Buttons
        SharedPreferences buttonPrefs = context.getSharedPreferences("action_buttons_config", Context.MODE_PRIVATE);
        SharedPreferences.Editor buttonEditor = buttonPrefs.edit();
        buttonEditor.putInt(prefix + VirtualButtonsView.PREF_KEY_A, KeyEvent.KEYCODE_ENTER);
        buttonEditor.putInt(prefix + VirtualButtonsView.PREF_KEY_B, KeyEvent.KEYCODE_ESCAPE);
        buttonEditor.putInt(prefix + VirtualButtonsView.PREF_KEY_X, KeyEvent.KEYCODE_SPACE);
        buttonEditor.putInt(prefix + VirtualButtonsView.PREF_KEY_Y, KeyEvent.KEYCODE_SHIFT_LEFT);
        buttonEditor.apply();

        // Update labels immediately so users see the change without reopening the dialog
        if (upKeyLabel != null) upKeyLabel.setText("[ " + getKeyName(KeyEvent.KEYCODE_DPAD_UP) + " ]");
        if (downKeyLabel != null) downKeyLabel.setText("[ " + getKeyName(KeyEvent.KEYCODE_DPAD_DOWN) + " ]");
        if (leftKeyLabel != null) leftKeyLabel.setText("[ " + getKeyName(KeyEvent.KEYCODE_DPAD_LEFT) + " ]");
        if (rightKeyLabel != null) rightKeyLabel.setText("[ " + getKeyName(KeyEvent.KEYCODE_DPAD_RIGHT) + " ]");
        if (aKeyLabel != null) aKeyLabel.setText("[ " + getKeyName(KeyEvent.KEYCODE_ENTER) + " ]");
        if (bKeyLabel != null) bKeyLabel.setText("[ " + getKeyName(KeyEvent.KEYCODE_ESCAPE) + " ]");
        if (xKeyLabel != null) xKeyLabel.setText("[ " + getKeyName(KeyEvent.KEYCODE_SPACE) + " ]");
        if (yKeyLabel != null) yKeyLabel.setText("[ " + getKeyName(KeyEvent.KEYCODE_SHIFT_LEFT) + " ]");

        Toast.makeText(context, "Reset to default keys", Toast.LENGTH_SHORT).show();
    }
    
    private String getKeyName(int keyCode) {
        String name = KeyEvent.keyCodeToString(keyCode);
        // Remove "KEYCODE_" prefix for cleaner display
        if (name.startsWith("KEYCODE_")) {
            name = name.substring(8);
        }
        return name;
    }
    
    // Functional interface for setting labels
    private interface KeyLabelSetter {
        void setLabel(TextView textView);
    }
}
