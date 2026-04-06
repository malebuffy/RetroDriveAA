package com.dosbox.emu.input;

import org.libsdl.app.SDLActivity;
import android.view.Display;
import android.view.WindowManager;

/**
 * Bridge to DOSBox native methods.
 * Wraps the low-level JNI calls for input handling.
 * 
 * NOTE: This class does NOT implement JNI - it uses existing SDL methods.
 */
public class NativeBridge {
    
    // Track cursor position for relative-to-absolute conversion
    // SDL's onNativeMouse expects absolute coordinates, not relative deltas
    private static float cursorX = 0;
    private static float cursorY = 0;
    private static float screenWidth = 1920;  // Default, will be updated
    private static float screenHeight = 1080; // Default, will be updated
    
    /**
     * Initialize screen dimensions for cursor tracking
     * Should be called when the surface is created
     */
    public static void initScreenDimensions(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        // Start cursor at screen center
        cursorX = width / 2.0f;
        cursorY = height / 2.0f;
        android.util.Log.d("NativeBridge", "Screen dimensions: " + width + "x" + height);
    }
    
    /**
     * Send a key event to DOSBox
     * @param keyCode Android keycode (NOT DOS scancode - SDL will convert internally)
     * @param pressed true for key down, false for key up
     */
    public static void sendKey(int keyCode, boolean pressed) {
        // Use SDL's native keyboard methods
        if (pressed) {
            SDLActivity.onNativeKeyDown(keyCode);
        } else {
            SDLActivity.onNativeKeyUp(keyCode);
        }
    }
    
    /**
     * Send absolute mouse position to DOSBox
     * @param x Absolute X coordinate
     * @param y Absolute Y coordinate
     * @param buttons Mouse button state (bitmask)
     */
    public static void sendMouseAbsolute(int x, int y, int buttons) {
        cursorX = x;
        cursorY = y;
        SDLActivity.onNativeMouse(0, buttons, x, y);
    }
    
    /**
     * Send relative mouse movement to DOSBox
     * Now uses proper SDL relative motion instead of converting to absolute coordinates
     * @param dx Delta X (relative movement)
     * @param dy Delta Y (relative movement)
     */
    public static void sendMouseRelative(float dx, float dy) {
        android.util.Log.d("NativeBridge", "sendMouseRelative: dx=" + dx + ", dy=" + dy);
        
        // Send relative motion directly to SDL - no need to track absolute position
        // SDL will handle this properly with relative mouse mode
        SDLActivity.onNativeMouseRelative(dx, dy);
    }
    
    /**
     * Send mouse button click to DOSBox
     * @param button Button index (0=left, 1=right, 2=middle)
     * @param pressed true for button down, false for button up
     */
    public static void sendMouseButton(int button, boolean pressed) {
        int state = pressed ? 1 : 0;
        SDLActivity.onNativeMouse(button, state, 0, 0);
    }
    
    /**
     * Send joystick axis position to DOSBox
     * @param x X-axis position (-1.0 = full left, 0.0 = center, 1.0 = full right)
     * @param y Y-axis position (-1.0 = full up, 0.0 = center, 1.0 = full down)
     */
    public static void sendJoystickAxis(float x, float y) {
        android.util.Log.d("NativeBridge", "sendJoystickAxis: x=" + x + ", y=" + y);
        com.dosbox.emu.DOSBoxJNI.nativeJoystickAxis(x, y);
    }
}
