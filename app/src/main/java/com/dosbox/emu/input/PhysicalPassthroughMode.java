package com.dosbox.emu.input;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Strategy A: Physical Passthrough Mode
 * 
 * For use when a real keyboard and mouse are connected.
 * Passes input events directly to DOSBox with minimal processing.
 */
public class PhysicalPassthroughMode implements InputControllerStrategy {
    private static final String TAG = "PhysicalPassthrough";
    
    @Override
    public boolean processTouchEvent(MotionEvent event) {
        // In this mode, we ignore touch - only physical mouse matters
        return false;
    }
    
    @Override
    public boolean processKeyEvent(KeyEvent event) {
        // Pass keyboard events directly through
        int keyCode = event.getKeyCode();
        boolean pressed = (event.getAction() == KeyEvent.ACTION_DOWN);
        
        // Pass Android keycode directly to SDL - it will handle DOS conversion
        NativeBridge.sendKey(keyCode, pressed);
        Log.d(TAG, "Key: " + keyCode + " " + (pressed ? "DOWN" : "UP") + " -> SDL");
        return true;
    }
    
    @Override
    public boolean processGenericEvent(MotionEvent event) {
        // Handle physical mouse movement
        if (event.getSource() == MotionEvent.TOOL_TYPE_MOUSE || 
            (event.getSource() & android.view.InputDevice.SOURCE_MOUSE) != 0) {
            
            float x = event.getX();
            float y = event.getY();
            int buttons = 0;
            
            // Map button states
            if (event.getButtonState() == MotionEvent.BUTTON_PRIMARY) {
                buttons |= 1; // Left button
            }
            if (event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
                buttons |= 2; // Right button
            }
            
            NativeBridge.sendMouseAbsolute((int)x, (int)y, buttons);
            return true;
        }
        
        return false;
    }
    
    @Override
    public void onActivate() {
        Log.i(TAG, "Physical passthrough mode activated");
    }
    
    @Override
    public void onDeactivate() {
        Log.i(TAG, "Physical passthrough mode deactivated");
    }
    
    @Override
    public String getModeName() {
        return "Physical Passthrough";
    }
    
    /**
     * Convert Android keycode to DOS scancode
     * This is a simplified mapping - extend as needed
     */
    private int androidKeyToDOSScancode(int androidKey) {
        switch (androidKey) {
            case KeyEvent.KEYCODE_A: return 0x1E;
            case KeyEvent.KEYCODE_B: return 0x30;
            case KeyEvent.KEYCODE_C: return 0x2E;
            case KeyEvent.KEYCODE_D: return 0x20;
            case KeyEvent.KEYCODE_E: return 0x12;
            case KeyEvent.KEYCODE_F: return 0x21;
            case KeyEvent.KEYCODE_G: return 0x22;
            case KeyEvent.KEYCODE_H: return 0x23;
            case KeyEvent.KEYCODE_I: return 0x17;
            case KeyEvent.KEYCODE_J: return 0x24;
            case KeyEvent.KEYCODE_K: return 0x25;
            case KeyEvent.KEYCODE_L: return 0x26;
            case KeyEvent.KEYCODE_M: return 0x32;
            case KeyEvent.KEYCODE_N: return 0x31;
            case KeyEvent.KEYCODE_O: return 0x18;
            case KeyEvent.KEYCODE_P: return 0x19;
            case KeyEvent.KEYCODE_Q: return 0x10;
            case KeyEvent.KEYCODE_R: return 0x13;
            case KeyEvent.KEYCODE_S: return 0x1F;
            case KeyEvent.KEYCODE_T: return 0x14;
            case KeyEvent.KEYCODE_U: return 0x16;
            case KeyEvent.KEYCODE_V: return 0x2F;
            case KeyEvent.KEYCODE_W: return 0x11;
            case KeyEvent.KEYCODE_X: return 0x2D;
            case KeyEvent.KEYCODE_Y: return 0x15;
            case KeyEvent.KEYCODE_Z: return 0x2C;
            
            case KeyEvent.KEYCODE_ENTER: return 0x1C;
            case KeyEvent.KEYCODE_SPACE: return 0x39;
            case KeyEvent.KEYCODE_ESCAPE: return 0x01;
            case KeyEvent.KEYCODE_TAB: return 0x0F;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 0x2A;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0x36;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 0x1D;
            case KeyEvent.KEYCODE_ALT_LEFT: return 0x38;
            
            case KeyEvent.KEYCODE_DPAD_UP: return 0x48;    // Arrow Up
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0x50;  // Arrow Down
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0x4B;  // Arrow Left
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0x4D; // Arrow Right
            
            default: return -1; // Unknown key
        }
    }
}
