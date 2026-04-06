package com.dosbox.emu;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Virtual D-Pad overlay with configurable key mappings
 * Supports multi-touch and dynamic key assignment
 */
public class VirtualDPadView extends View {
    private static final String TAG = "VirtualDPadView";
    private static final String PREFS_NAME = "dpad_config";
    
    // Preference keys for each direction
    public static final String PREF_KEY_UP = "dpad_key_up";
    public static final String PREF_KEY_DOWN = "dpad_key_down";
    public static final String PREF_KEY_LEFT = "dpad_key_left";
    public static final String PREF_KEY_RIGHT = "dpad_key_right";
    
    // Direction constants (ordered by rotation: 0°=UP, 90°=RIGHT, 180°=DOWN, 270°=LEFT)
    public static final int DIRECTION_NONE = -1;
    public static final int DIRECTION_UP = 0;
    public static final int DIRECTION_RIGHT = 1;
    public static final int DIRECTION_DOWN = 2;
    public static final int DIRECTION_LEFT = 3;
    
    // Default key mappings (Arrow keys)
    private static final int DEFAULT_KEY_UP = KeyEvent.KEYCODE_DPAD_UP;
    private static final int DEFAULT_KEY_DOWN = KeyEvent.KEYCODE_DPAD_DOWN;
    private static final int DEFAULT_KEY_LEFT = KeyEvent.KEYCODE_DPAD_LEFT;
    private static final int DEFAULT_KEY_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;
    
    // Visual properties
    private Paint backgroundPaint;
    private Paint outerCirclePaint;
    private Paint borderPaint;
    private Paint arrowPaint;
    private Paint arrowPressedPaint;
    private float centerX, centerY;
    private float radius;
    private float innerRadius;
    
    // Touch tracking
    private SparseArray<Integer> activePointers = new SparseArray<>(); // pointerId -> direction
    private boolean[] directionPressed = new boolean[4];
    
    // Key mappings
    private int keyUp, keyDown, keyLeft, keyRight;
    
    // Per-game configuration
    private String gameFolder = null;
    
    // Event listener
    private OnDPadEventListener listener;
    
    public interface OnDPadEventListener {
        void onDPadPress(int direction, int keyCode);
        void onDPadRelease(int direction, int keyCode);
    }
    
    public VirtualDPadView(Context context) {
        super(context);
        init(context);
    }
    
    public VirtualDPadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    private void init(Context context) {
        // Load key mappings from preferences
        loadKeyMappings(context);
        
        // Setup paint objects for better visibility
        outerCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outerCirclePaint.setColor(0xAA1A1A1A); // Semi-transparent dark gray background
        outerCirclePaint.setStyle(Paint.Style.FILL);
        
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(0xFFFFFFFF); // White border
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4);
        
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(0xCC000000); // More opaque black for center
        backgroundPaint.setStyle(Paint.Style.FILL);
        
        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setColor(0xFFFFFFFF); // Fully opaque white arrows
        arrowPaint.setStyle(Paint.Style.FILL);
        
        arrowPressedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPressedPaint.setColor(0xFF00FF00); // Bright green when pressed
        arrowPressedPaint.setStyle(Paint.Style.FILL);
        
        // Enable touch events
        setClickable(true);
        setFocusable(true);
        
        Log.d(TAG, "VirtualDPadView initialized with mappings: Up=" + keyUp + 
              ", Down=" + keyDown + ", Left=" + keyLeft + ", Right=" + keyRight);
    }
    
    private void loadKeyMappings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String prefix = getKeyPrefix();
        keyUp = prefs.getInt(prefix + PREF_KEY_UP, DEFAULT_KEY_UP);
        keyDown = prefs.getInt(prefix + PREF_KEY_DOWN, DEFAULT_KEY_DOWN);
        keyLeft = prefs.getInt(prefix + PREF_KEY_LEFT, DEFAULT_KEY_LEFT);
        keyRight = prefs.getInt(prefix + PREF_KEY_RIGHT, DEFAULT_KEY_RIGHT);
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
    
    public void setOnDPadEventListener(OnDPadEventListener listener) {
        this.listener = listener;
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // Calculate D-Pad dimensions
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) / 2f * 0.8f;
        innerRadius = radius * 0.3f;
        
        Log.d(TAG, "D-Pad size changed: w=" + w + ", h=" + h + 
              ", radius=" + radius + ", innerRadius=" + innerRadius);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (radius <= 0) return;
        
        // Draw outer circle background (full D-Pad area)
        canvas.drawCircle(centerX, centerY, radius, outerCirclePaint);
        
        // Draw border around outer circle
        canvas.drawCircle(centerX, centerY, radius, borderPaint);
        
        // Draw center circle
        canvas.drawCircle(centerX, centerY, innerRadius, backgroundPaint);
        
        // Draw four directional arrows
        drawArrow(canvas, DIRECTION_UP, directionPressed[DIRECTION_UP]);
        drawArrow(canvas, DIRECTION_DOWN, directionPressed[DIRECTION_DOWN]);
        drawArrow(canvas, DIRECTION_LEFT, directionPressed[DIRECTION_LEFT]);
        drawArrow(canvas, DIRECTION_RIGHT, directionPressed[DIRECTION_RIGHT]);
    }
    
    private void drawArrow(Canvas canvas, int direction, boolean pressed) {
        Paint paint = pressed ? arrowPressedPaint : arrowPaint;
        
        canvas.save();
        canvas.rotate(direction * 90, centerX, centerY);
        
        // Draw arrow pointing up (will be rotated)
        Path arrow = new Path();
        float arrowTop = centerY - radius + innerRadius;
        float arrowBottom = centerY - innerRadius;
        float arrowWidth = innerRadius * 0.8f;
        
        arrow.moveTo(centerX, arrowTop); // Tip
        arrow.lineTo(centerX - arrowWidth, arrowBottom); // Left base
        arrow.lineTo(centerX - arrowWidth * 0.5f, arrowBottom); // Left inner
        arrow.lineTo(centerX - arrowWidth * 0.5f, arrowBottom + innerRadius * 0.5f); // Left stem
        arrow.lineTo(centerX + arrowWidth * 0.5f, arrowBottom + innerRadius * 0.5f); // Right stem
        arrow.lineTo(centerX + arrowWidth * 0.5f, arrowBottom); // Right inner
        arrow.lineTo(centerX + arrowWidth, arrowBottom); // Right base
        arrow.close();
        
        canvas.drawPath(arrow, paint);
        canvas.restore();
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
        int direction = getDirectionFromPoint(x, y);
        if (direction != DIRECTION_NONE) {
            activePointers.put(pointerId, direction);
            pressDirection(direction);
        }
    }
    
    private void updatePointer(int pointerId, float x, float y) {
        Integer oldDirection = activePointers.get(pointerId);
        int newDirection = getDirectionFromPoint(x, y);
        
        if (oldDirection != null && oldDirection != newDirection) {
            // Pointer moved to different direction
            releaseDirection(oldDirection);
            activePointers.remove(pointerId);
            
            if (newDirection != DIRECTION_NONE) {
                activePointers.put(pointerId, newDirection);
                pressDirection(newDirection);
            }
        }
    }
    
    private void handlePointerUp(int pointerId) {
        Integer direction = activePointers.get(pointerId);
        if (direction != null) {
            activePointers.remove(pointerId);
            releaseDirection(direction);
        }
    }
    
    private void releaseAllPointers() {
        for (int i = 0; i < activePointers.size(); i++) {
            int direction = activePointers.valueAt(i);
            releaseDirection(direction);
        }
        activePointers.clear();
    }
    
    private void pressDirection(int direction) {
        if (!directionPressed[direction]) {
            directionPressed[direction] = true;
            if (listener != null) {
                int keyCode = getKeyCodeForDirection(direction);
                listener.onDPadPress(direction, keyCode);
                Log.d(TAG, "Direction pressed: " + direction + " (KeyCode: " + keyCode + ")");
            }
        }
    }
    
    private void releaseDirection(int direction) {
        if (directionPressed[direction]) {
            directionPressed[direction] = false;
            if (listener != null) {
                int keyCode = getKeyCodeForDirection(direction);
                listener.onDPadRelease(direction, keyCode);
                Log.d(TAG, "Direction released: " + direction + " (KeyCode: " + keyCode + ")");
            }
        }
    }
    
    private int getDirectionFromPoint(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        
        // Check if touch is within D-Pad area
        if (distance < innerRadius || distance > radius) {
            return DIRECTION_NONE;
        }
        
        // Calculate angle (0 = right, 90 = down, 180 = left, 270 = up)
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) angle += 360;
        
        // Map angle to direction (with 45-degree sectors)
        if (angle >= 315 || angle < 45) {
            return DIRECTION_RIGHT;
        } else if (angle >= 45 && angle < 135) {
            return DIRECTION_DOWN;
        } else if (angle >= 135 && angle < 225) {
            return DIRECTION_LEFT;
        } else {
            return DIRECTION_UP;
        }
    }
    
    private int getKeyCodeForDirection(int direction) {
        switch (direction) {
            case DIRECTION_UP: return keyUp;
            case DIRECTION_DOWN: return keyDown;
            case DIRECTION_LEFT: return keyLeft;
            case DIRECTION_RIGHT: return keyRight;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}
