package com.dosbox.emu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import org.libsdl.app.SDLActivity;
import java.util.HashMap;
import java.util.Map;

/**
 * Modern on-screen game controls overlay for DOSBox
 * Provides virtual D-pad, action buttons, and mouse emulation
 */
public class GameControlsOverlay extends View {
    private static final String TAG = "GameControls";
    
    // Control button definitions
    private class ControlButton {
        RectF bounds;
        String label;
        int keyCode;
        boolean isPressed = false;
        int color;
        
        ControlButton(float x, float y, float size, String label, int keyCode, int color) {
            this.bounds = new RectF(x - size/2, y - size/2, x + size/2, y + size/2);
            this.label = label;
            this.keyCode = keyCode;
            this.color = color;
        }
    }
    
    private Paint paint;
    private Paint textPaint;
    private Map<Integer, ControlButton> buttons = new HashMap<>();
    private Map<Integer, ControlButton> dpad = new HashMap<>();
    
    private boolean visible = true;
    private float density;
    
    // Mouse tracking
    private PointF lastTouchPos = new PointF();
    private PointF initialTouchPos = new PointF();
    private boolean trackingMouse = false;
    private long touchDownTime = 0;
    private static final long TAP_TIMEOUT = 200; // ms - quick tap = left click
    private static final long LONG_PRESS_TIMEOUT = 500; // ms - long press = right click
    private static final float TAP_THRESHOLD = 10; // pixels
    
    public GameControlsOverlay(Context context) {
        super(context);
        init(context);
    }
    
    public GameControlsOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(14 * density);
        
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        
        Log.d(TAG, "GameControlsOverlay initialized - mouse control enabled");
    }
    
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            setupControls(getWidth(), getHeight());
        }
    }
    
    private void setupControls(int width, int height) {
        buttons.clear();
        dpad.clear();
        
        Log.d(TAG, "Controls setup for " + width + "x" + height + " - mouse only mode");
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (!visible) return;
        
        // No buttons to draw - mouse control only
    }
    
    private void drawButton(Canvas canvas, ControlButton button, boolean isPressed) {
        if (button == null) return;
        
        // Draw button background
        paint.setColor(isPressed ? (button.color | 0xFF000000) : button.color);
        canvas.drawCircle(button.bounds.centerX(), button.bounds.centerY(), 
                         button.bounds.width() / 2, paint);
        
        // Draw button border
        paint.setColor(isPressed ? Color.WHITE : 0x80FFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * density);
        canvas.drawCircle(button.bounds.centerX(), button.bounds.centerY(), 
                         button.bounds.width() / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        
        // Draw button label
        canvas.drawText(button.label, button.bounds.centerX(), 
                       button.bounds.centerY() + textPaint.getTextSize() / 3, textPaint);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!visible) {
            Log.w(TAG, "Touch event received but overlay not visible");
            return false;
        }
        
        Log.d(TAG, "Touch event: action=" + event.getAction() + " at (" + (int)event.getX() + ", " + (int)event.getY() + ")");
        
        // All touches are mouse control (no buttons)
        return handleMouseMovement(event);
    }
    
    private boolean handleGameMode(MotionEvent event) {
        int action = event.getActionMasked();
        boolean touchedButton = false;
        
        for (int i = 0; i < event.getPointerCount(); i++) {
            float x = event.getX(i);
            float y = event.getY(i);
            
            // Check action buttons
            for (ControlButton btn : buttons.values()) {
                if (btn.bounds.contains(x, y)) {
                    touchedButton = true;
                    boolean shouldPress = (action == MotionEvent.ACTION_DOWN || 
                                         action == MotionEvent.ACTION_POINTER_DOWN ||
                                         action == MotionEvent.ACTION_MOVE);
                    if (btn.isPressed != shouldPress) {
                        btn.isPressed = shouldPress;
                        sendKeyEvent(btn.keyCode, shouldPress);
                        invalidate();
                    }
                }
            }
        }
        
        // Release buttons on up
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            for (ControlButton btn : buttons.values()) {
                if (btn.isPressed) {
                    btn.isPressed = false;
                    sendKeyEvent(btn.keyCode, false);
                }
            }
            invalidate();
        }
        
        return touchedButton;
    }
    
    private void sendKeyEvent(int keyCode, boolean pressed) {
        try {
            int action = pressed ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
            KeyEvent event = new KeyEvent(action, keyCode);
            ((SDLActivity) getContext()).dispatchKeyEvent(event);
            Log.d(TAG, "Key " + keyCode + " " + (pressed ? "pressed" : "released"));
        } catch (Exception e) {
            Log.e(TAG, "Error sending key event", e);
        }
    }
    
    private boolean handleMouseMovement(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();
        
        Log.d(TAG, "handleMouseMovement: action=" + action + " tracking=" + trackingMouse);
        
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // Start tracking
                trackingMouse = true;
                touchDownTime = System.currentTimeMillis();
                lastTouchPos.set(x, y);
                initialTouchPos.set(x, y);
                Log.d(TAG, "Mouse tracking started at (" + (int)x + ", " + (int)y + ")");
                return true;
                
            case MotionEvent.ACTION_MOVE:
                if (trackingMouse) {
                    // Calculate delta for mouse movement
                    float deltaX = x - lastTouchPos.x;
                    float deltaY = y - lastTouchPos.y;
                    
                    // Only send movement if delta is significant
                    if (Math.abs(deltaX) > 0.5f || Math.abs(deltaY) > 0.5f) {
                        // Send relative mouse movement with SDL_MOUSEMOTION
                        // Using button=-1, action=0 for pure motion without button press
                        try {
                            SDLActivity.onNativeMouse(-1, 0, deltaX * 2.0f, deltaY * 2.0f);
                            Log.v(TAG, "Mouse moved: " + (int)deltaX + ", " + (int)deltaY);
                        } catch (Exception e) {
                            Log.e(TAG, "Error sending mouse movement", e);
                        }
                        lastTouchPos.set(x, y);
                    }
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_UP:
                if (trackingMouse) {
                    // Check touch duration and movement
                    long touchDuration = System.currentTimeMillis() - touchDownTime;
                    float deltaX = Math.abs(x - initialTouchPos.x);
                    float deltaY = Math.abs(y - initialTouchPos.y);
                    float totalMovement = (float)Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    
                    // Determine action based on duration and movement
                    if (totalMovement < TAP_THRESHOLD) {
                        if (touchDuration < TAP_TIMEOUT) {
                            // Quick tap = left click
                            sendMouseClick(0);
                            Log.d(TAG, "Quick tap - left click");
                        } else if (touchDuration >= LONG_PRESS_TIMEOUT) {
                            // Long press = right click
                            sendMouseClick(1);
                            Log.d(TAG, "Long press - right click");
                        }
                    }
                    // If significant movement, it was just mouse movement (no click)
                    
                    trackingMouse = false;
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_CANCEL:
                trackingMouse = false;
                return true;
        }
        
        return false;
    }
    
    private void sendMouseClick(int button) {
        try {
            // Send button down
            SDLActivity.onNativeMouse(button, 1, 0, 0);
            // Small delay
            try { Thread.sleep(50); } catch (InterruptedException e) {}
            // Send button up
            SDLActivity.onNativeMouse(button, 0, 0, 0);
            Log.d(TAG, "Mouse click " + button);
        } catch (Exception e) {
            Log.e(TAG, "Error sending mouse click", e);
        }
    }
    
    public void setVisible(boolean visible) {
        this.visible = visible;
        invalidate();
    }
    
    public boolean isVisible() {
        return visible;
    }
    
    public void toggleVisibility() {
        setVisible(!visible);
    }
}
