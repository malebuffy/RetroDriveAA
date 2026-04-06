package com.dosbox.emu;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Virtual action buttons (X, Y, A, B) with configurable key mappings
 * Arranged in a diamond pattern on the left side of the screen
 */
public class VirtualButtonsView extends View {
    private static final String TAG = "VirtualButtonsView";
    private static final String PREFS_NAME = "action_buttons_config";
    
    // Preference keys for each button
    public static final String PREF_KEY_A = "action_button_a";
    public static final String PREF_KEY_B = "action_button_b";
    public static final String PREF_KEY_X = "action_button_x";
    public static final String PREF_KEY_Y = "action_button_y";
    
    // Button constants
    public static final int BUTTON_NONE = -1;
    public static final int BUTTON_A = 0;
    public static final int BUTTON_B = 1;
    public static final int BUTTON_X = 2;
    public static final int BUTTON_Y = 3;
    
    // Default key mappings: A=Enter, B=ESC, X=Space, Y=Shift
    private static final int DEFAULT_KEY_A = KeyEvent.KEYCODE_ENTER;
    private static final int DEFAULT_KEY_B = KeyEvent.KEYCODE_ESCAPE;
    private static final int DEFAULT_KEY_X = KeyEvent.KEYCODE_SPACE;
    private static final int DEFAULT_KEY_Y = KeyEvent.KEYCODE_SHIFT_LEFT;
    
    // Visual properties
    private Paint buttonPaint;
    private Paint buttonPressedPaint;
    private Paint textPaint;
    private Paint textPressedPaint;
    private float centerX, centerY;
    private float buttonRadius;
    private float spacing;
    
    // Button positions (relative to center)
    private float[] buttonX = new float[4];
    private float[] buttonY = new float[4];
    
    // Touch tracking
    private SparseArray<Integer> activePointers = new SparseArray<>(); // pointerId -> button
    private boolean[] buttonPressed = new boolean[4];
    
    // Key mappings
    private int keyA, keyB, keyX, keyY;
    
    // Per-game configuration
    private String gameFolder = null;
    
    // Event listener
    private OnButtonEventListener listener;
    
    public interface OnButtonEventListener {
        void onButtonPress(int button, int keyCode);
        void onButtonRelease(int button, int keyCode);
    }
    
    public VirtualButtonsView(Context context) {
        super(context);
        init(context);
    }
    
    public VirtualButtonsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    private void init(Context context) {
        // Load key mappings from preferences
        loadKeyMappings(context);
        
        // Setup paint objects
        buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonPaint.setColor(0xAA1A1A1A); // Semi-transparent dark gray
        buttonPaint.setStyle(Paint.Style.FILL);
        
        buttonPressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonPressedPaint.setColor(0xFF00FF00); // Bright green when pressed
        buttonPressedPaint.setStyle(Paint.Style.FILL);
        
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF); // White text
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(40);
        textPaint.setFakeBoldText(true);
        
        textPressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPressedPaint.setColor(0xFF000000); // Black text when pressed
        textPressedPaint.setTextAlign(Paint.Align.CENTER);
        textPressedPaint.setTextSize(40);
        textPressedPaint.setFakeBoldText(true);
        
        // Enable touch events
        setClickable(true);
        setFocusable(true);
        
        Log.d(TAG, "VirtualButtonsView initialized with mappings: A=" + keyA + 
              ", B=" + keyB + ", X=" + keyX + ", Y=" + keyY);
    }
    
    private void loadKeyMappings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String prefix = getKeyPrefix();
        keyA = prefs.getInt(prefix + PREF_KEY_A, DEFAULT_KEY_A);
        keyB = prefs.getInt(prefix + PREF_KEY_B, DEFAULT_KEY_B);
        keyX = prefs.getInt(prefix + PREF_KEY_X, DEFAULT_KEY_X);
        keyY = prefs.getInt(prefix + PREF_KEY_Y, DEFAULT_KEY_Y);
    }
    
    public void setGameFolder(String gameFolder) {
        this.gameFolder = gameFolder;
        loadKeyMappings(getContext());
    }
    
    private String getKeyPrefix() {
        // Use game-specific key prefix if game folder is set
        return (gameFolder != null && !gameFolder.isEmpty()) ? "game_" + gameFolder + "_" : "";
    }
    
    public void reloadKeyMappings() {
        loadKeyMappings(getContext());
        Log.d(TAG, "Key mappings reloaded");
    }
    
    public void setOnButtonEventListener(OnButtonEventListener listener) {
        this.listener = listener;
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // Calculate button dimensions and positions
        centerX = w / 2f;
        centerY = h / 2f;
        buttonRadius = Math.min(w, h) / 6f;
        spacing = buttonRadius * 1.8f;
        
        // Diamond pattern positions
        // A - Right (primary action)
        buttonX[BUTTON_A] = centerX + spacing;
        buttonY[BUTTON_A] = centerY;
        
        // B - Bottom (secondary/back)
        buttonX[BUTTON_B] = centerX;
        buttonY[BUTTON_B] = centerY + spacing;
        
        // X - Top (tertiary action)
        buttonX[BUTTON_X] = centerX;
        buttonY[BUTTON_X] = centerY - spacing;
        
        // Y - Left (quaternary action)
        buttonX[BUTTON_Y] = centerX - spacing;
        buttonY[BUTTON_Y] = centerY;
        
        Log.d(TAG, "Buttons size changed: w=" + w + ", h=" + h + ", radius=" + buttonRadius);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (buttonRadius <= 0) return;
        
        // Draw all buttons
        drawButton(canvas, BUTTON_A, "A", buttonPressed[BUTTON_A]);
        drawButton(canvas, BUTTON_B, "B", buttonPressed[BUTTON_B]);
        drawButton(canvas, BUTTON_X, "X", buttonPressed[BUTTON_X]);
        drawButton(canvas, BUTTON_Y, "Y", buttonPressed[BUTTON_Y]);
    }
    
    private void drawButton(Canvas canvas, int button, String label, boolean pressed) {
        Paint circlePaint = pressed ? buttonPressedPaint : buttonPaint;
        Paint textPaint = pressed ? textPressedPaint : this.textPaint;
        
        // Draw circle
        canvas.drawCircle(buttonX[button], buttonY[button], buttonRadius, circlePaint);
        
        // Draw border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3);
        canvas.drawCircle(buttonX[button], buttonY[button], buttonRadius, borderPaint);
        
        // Draw label
        float textY = buttonY[button] + (textPaint.getTextSize() / 3f); // Center text vertically
        canvas.drawText(label, buttonX[button], textY, textPaint);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);
        
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                handlePointerDown(pointerId, event.getX(pointerIndex), event.getY(pointerIndex));
                break;
                
            case MotionEvent.ACTION_MOVE:
                // Update all active pointers
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    updatePointer(id, event.getX(i), event.getY(i));
                }
                break;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                handlePointerUp(pointerId);
                break;
                
            case MotionEvent.ACTION_CANCEL:
                releaseAllPointers();
                break;
        }
        
        invalidate();
        return true;
    }
    
    private void handlePointerDown(int pointerId, float x, float y) {
        int button = getButtonFromPoint(x, y);
        if (button != BUTTON_NONE) {
            activePointers.put(pointerId, button);
            pressButton(button);
        }
    }
    
    private void updatePointer(int pointerId, float x, float y) {
        Integer oldButton = activePointers.get(pointerId);
        int newButton = getButtonFromPoint(x, y);
        
        if (oldButton != null && oldButton != newButton) {
            // Pointer moved to different button or outside
            releaseButton(oldButton);
            activePointers.remove(pointerId);
            
            if (newButton != BUTTON_NONE) {
                activePointers.put(pointerId, newButton);
                pressButton(newButton);
            }
        }
    }
    
    private void handlePointerUp(int pointerId) {
        Integer button = activePointers.get(pointerId);
        if (button != null) {
            activePointers.remove(pointerId);
            releaseButton(button);
        }
    }
    
    private void releaseAllPointers() {
        for (int i = 0; i < activePointers.size(); i++) {
            int button = activePointers.valueAt(i);
            releaseButton(button);
        }
        activePointers.clear();
    }
    
    private void pressButton(int button) {
        if (!buttonPressed[button]) {
            buttonPressed[button] = true;
            if (listener != null) {
                int keyCode = getKeyCodeForButton(button);
                listener.onButtonPress(button, keyCode);
                Log.d(TAG, "Button pressed: " + button + " (KeyCode: " + keyCode + ")");
            }
        }
    }
    
    private void releaseButton(int button) {
        if (buttonPressed[button]) {
            buttonPressed[button] = false;
            if (listener != null) {
                int keyCode = getKeyCodeForButton(button);
                listener.onButtonRelease(button, keyCode);
                Log.d(TAG, "Button released: " + button + " (KeyCode: " + keyCode + ")");
            }
        }
    }
    
    private int getButtonFromPoint(float x, float y) {
        // Check each button
        for (int i = 0; i < 4; i++) {
            float dx = x - buttonX[i];
            float dy = y - buttonY[i];
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            
            if (distance <= buttonRadius) {
                return i;
            }
        }
        return BUTTON_NONE;
    }
    
    private int getKeyCodeForButton(int button) {
        switch (button) {
            case BUTTON_A: return keyA;
            case BUTTON_B: return keyB;
            case BUTTON_X: return keyX;
            case BUTTON_Y: return keyY;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}
