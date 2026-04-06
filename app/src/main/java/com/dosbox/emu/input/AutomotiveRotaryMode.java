package com.dosbox.emu.input;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Strategy C: Automotive Rotary Mode
 * 
 * For Android Automotive OS rotary controllers and D-Pad.
 * Maps rotary/D-Pad input to DOS arrow keys and action buttons.
 */
public class AutomotiveRotaryMode implements InputControllerStrategy {
    private static final String TAG = "AutomotiveRotary";
    
    // Rotary scroll sensitivity
    private static final float SCROLL_THRESHOLD = 0.5f;
    private float accumulatedScroll = 0.0f;
    
    @Override
    public boolean processTouchEvent(MotionEvent event) {
        // In automotive mode, touchscreen is disabled while driving
        // Could show a safety warning here
        Log.w(TAG, "Touch input ignored in Automotive Rotary mode");
        return false;
    }
    
    @Override
    public boolean processKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean pressed = (event.getAction() == KeyEvent.ACTION_DOWN);
        
        // Map automotive-specific keys to DOS scancodes
        int dosStancode = automotiveKeyToDOSScancode(keyCode);
        
        if (dosStancode != -1) {
            NativeBridge.sendKey(dosStancode, pressed);
            Log.d(TAG, "Automotive key: " + keyCode + " -> DOS " + dosStancode);
            return true;
        }
        
        return false;
    }
    
    @Override
    public boolean processGenericEvent(MotionEvent event) {
        // Handle rotary controller scroll events
        if (event.getAction() == MotionEvent.ACTION_SCROLL) {
            float scrollY = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            float scrollX = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            
            // Accumulate scroll to handle smooth scrolling
            accumulatedScroll += scrollY;
            
            // Convert scroll to discrete arrow key presses
            if (Math.abs(accumulatedScroll) >= SCROLL_THRESHOLD) {
                int steps = (int)(accumulatedScroll / SCROLL_THRESHOLD);
                boolean isUp = steps < 0;
                int abssteps = Math.abs(steps);
                
                // Send arrow up/down based on scroll direction
                int scancode = isUp ? 0x48 : 0x50; // Up or Down arrow
                
                for (int i = 0; i < abssteps; i++) {
                    // Simulate key press and release
                    NativeBridge.sendKey(scancode, true);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                    NativeBridge.sendKey(scancode, false);
                }
                
                accumulatedScroll = 0.0f;
                Log.d(TAG, "Rotary scroll: " + steps + " steps " + (isUp ? "up" : "down"));
                return true;
            }
            
            // Handle horizontal scroll if present
            if (Math.abs(scrollX) > 0.1f) {
                boolean isLeft = scrollX < 0;
                int scancode = isLeft ? 0x4B : 0x4D; // Left or Right arrow
                
                NativeBridge.sendKey(scancode, true);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // Ignore
                }
                NativeBridge.sendKey(scancode, false);
                
                Log.d(TAG, "Horizontal scroll: " + (isLeft ? "left" : "right"));
                return true;
            }
        }
        
        return false;
    }
    
    @Override
    public void onActivate() {
        Log.i(TAG, "Automotive Rotary mode activated");
        accumulatedScroll = 0.0f;
    }
    
    @Override
    public void onDeactivate() {
        Log.i(TAG, "Automotive Rotary mode deactivated");
        accumulatedScroll = 0.0f;
    }
    
    @Override
    public String getModeName() {
        return "Automotive Rotary";
    }
    
    /**
     * Convert Android Automotive keycodes to DOS scancodes
     */
    private int automotiveKeyToDOSScancode(int androidKey) {
        switch (androidKey) {
            // D-Pad / Navigation keys
            case KeyEvent.KEYCODE_DPAD_UP: return 0x48;       // Arrow Up
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x50;     // Arrow Down
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x4B;     // Arrow Left
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x4D;    // Arrow Right
            case KeyEvent.KEYCODE_DPAD_CENTER: return 0x1C;   // Enter
            
            // System navigation (for rotary controller)
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP: return 0x48;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN: return 0x50;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT: return 0x4B;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT: return 0x4D;
            
            // Media/action buttons (can be customized)
            case KeyEvent.KEYCODE_BUTTON_A: return 0x1C;      // Enter
            case KeyEvent.KEYCODE_BUTTON_B: return 0x01;      // Escape
            case KeyEvent.KEYCODE_BUTTON_X: return 0x39;      // Space
            case KeyEvent.KEYCODE_BUTTON_Y: return 0x2A;      // Left Shift
            
            // Standard keys still work
            case KeyEvent.KEYCODE_ENTER: return 0x1C;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE: return 0x01;
            case KeyEvent.KEYCODE_SPACE: return 0x39;
            
            default: return -1; // Unknown key
        }
    }
}
