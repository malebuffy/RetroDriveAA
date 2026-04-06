package com.dosbox.emu.input;

import android.graphics.PointF;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Strategy B: Touchpad Emulation Mode
 * 
 * Uses the touchscreen as a trackpad/mouse.
 * Drag to move cursor, tap for left click, long press for right click.
 */
public class TouchpadEmulationMode implements InputControllerStrategy {
    private static final String TAG = "TouchpadEmulation";
    
    private static final float SENSITIVITY = 2.0f;
    private static final long TAP_TIMEOUT = 200; // ms - quick tap = left click
    private static final long LONG_PRESS_TIMEOUT = 500; // ms - long press = right click
    private static final float TAP_THRESHOLD = 10; // pixels
    
    private PointF lastTouchPos = new PointF();
    private PointF initialTouchPos = new PointF();
    private boolean trackingTouch = false;
    private long touchDownTime = 0;
    
    @Override
    public boolean processTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();
        
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // Start tracking
                trackingTouch = true;
                touchDownTime = System.currentTimeMillis();
                lastTouchPos.set(x, y);
                initialTouchPos.set(x, y);
                Log.d(TAG, "Touch started at (" + (int)x + ", " + (int)y + ")");
                return true;
                
            case MotionEvent.ACTION_MOVE:
                if (trackingTouch) {
                    // Calculate delta for mouse movement
                    float deltaX = x - lastTouchPos.x;
                    float deltaY = y - lastTouchPos.y;
                    
                    // Only send movement if delta is significant
                    if (Math.abs(deltaX) > 0.5f || Math.abs(deltaY) > 0.5f) {
                        // Send relative mouse movement
                        NativeBridge.sendMouseRelative(deltaX * SENSITIVITY, deltaY * SENSITIVITY);
                        lastTouchPos.set(x, y);
                        Log.v(TAG, "Mouse moved: dx=" + (int)deltaX + " dy=" + (int)deltaY);
                    }
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_UP:
                if (trackingTouch) {
                    // Check touch duration and movement
                    long touchDuration = System.currentTimeMillis() - touchDownTime;
                    float deltaX = Math.abs(x - initialTouchPos.x);
                    float deltaY = Math.abs(y - initialTouchPos.y);
                    float totalMovement = (float)Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                    
                    // Determine action based on duration and movement
                    if (totalMovement < TAP_THRESHOLD) {
                        if (touchDuration < TAP_TIMEOUT) {
                            // Quick tap = left click
                            sendClick(0); // Left button
                            Log.d(TAG, "Quick tap -> Left click");
                        } else if (touchDuration >= LONG_PRESS_TIMEOUT) {
                            // Long press = right click
                            sendClick(1); // Right button
                            Log.d(TAG, "Long press -> Right click");
                        }
                    }
                    
                    trackingTouch = false;
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_CANCEL:
                trackingTouch = false;
                return true;
        }
        
        return false;
    }
    
    @Override
    public boolean processKeyEvent(KeyEvent event) {
        // In touchpad mode, keyboard still works normally
        // You can add keyboard shortcuts here if needed
        return false; // Let system handle keyboard
    }
    
    @Override
    public boolean processGenericEvent(MotionEvent event) {
        // Not used in touchpad mode
        return false;
    }
    
    @Override
    public void onActivate() {
        Log.i(TAG, "Touchpad emulation mode activated");
        trackingTouch = false;
    }
    
    @Override
    public void onDeactivate() {
        Log.i(TAG, "Touchpad emulation mode deactivated");
        trackingTouch = false;
    }
    
    @Override
    public String getModeName() {
        return "Touchpad Emulation";
    }
    
    /**
     * Send a complete mouse click (down + up)
     */
    private void sendClick(int button) {
        NativeBridge.sendMouseButton(button, true);
        try {
            Thread.sleep(50); // Brief delay between down/up
        } catch (InterruptedException e) {
            // Ignore
        }
        NativeBridge.sendMouseButton(button, false);
    }
}
