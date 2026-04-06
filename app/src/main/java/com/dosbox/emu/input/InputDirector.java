package com.dosbox.emu.input;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Input Director - Manager for input strategies
 * 
 * Manages the active input control strategy and routes events to it.
 * Supports hot-swapping between different input modes at runtime.
 */
public class InputDirector {
    private static final String TAG = "InputDirector";
    
    /**
     * Available input modes
     */
    public enum InputMode {
        PHYSICAL_PASSTHROUGH,
        TOUCHPAD_EMULATION,
        AUTOMOTIVE_ROTARY
    }
    
    private InputControllerStrategy currentStrategy;
    private InputMode currentMode;
    
    /**
     * Create InputDirector with default mode
     */
    public InputDirector() {
        this(InputMode.TOUCHPAD_EMULATION); // Default to touchpad
    }
    
    /**
     * Create InputDirector with specific initial mode
     */
    public InputDirector(InputMode initialMode) {
        setMode(initialMode);
    }
    
    /**
     * Set the active input mode
     * @param mode The input mode to activate
     */
    public void setMode(InputMode mode) {
        // Deactivate current strategy
        if (currentStrategy != null) {
            currentStrategy.onDeactivate();
        }
        
        // Create and activate new strategy
        currentMode = mode;
        switch (mode) {
            case PHYSICAL_PASSTHROUGH:
                currentStrategy = new PhysicalPassthroughMode();
                break;
            case TOUCHPAD_EMULATION:
                currentStrategy = new TouchpadEmulationMode();
                break;
            case AUTOMOTIVE_ROTARY:
                currentStrategy = new AutomotiveRotaryMode();
                break;
            default:
                currentStrategy = new TouchpadEmulationMode();
                break;
        }
        
        currentStrategy.onActivate();
        Log.i(TAG, "Switched to mode: " + currentStrategy.getModeName());
    }
    
    /**
     * Get the current input mode
     */
    public InputMode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Get the current mode's name as a string
     */
    public String getCurrentModeName() {
        if (currentStrategy != null) {
            return currentStrategy.getModeName();
        }
        return "None";
    }
    
    /**
     * Get the current strategy instance
     */
    public InputControllerStrategy getCurrentStrategy() {
        return currentStrategy;
    }
    
    /**
     * Process a touch event through the current strategy
     * Call this from your Activity's onTouchEvent or dispatchTouchEvent
     */
    public boolean processTouchEvent(MotionEvent event) {
        if (currentStrategy != null) {
            return currentStrategy.processTouchEvent(event);
        }
        return false;
    }
    
    /**
     * Process a key event through the current strategy
     * Call this from your Activity's onKeyDown/onKeyUp or dispatchKeyEvent
     */
    public boolean processKeyEvent(KeyEvent event) {
        if (currentStrategy != null) {
            return currentStrategy.processKeyEvent(event);
        }
        return false;
    }
    
    /**
     * Process a generic motion event through the current strategy
     * Call this from your Activity's onGenericMotionEvent
     */
    public boolean processGenericEvent(MotionEvent event) {
        if (currentStrategy != null) {
            return currentStrategy.processGenericEvent(event);
        }
        return false;
    }
    
    /**
     * Auto-detect and switch to appropriate mode based on available input devices
     * Call this when configuration changes or input devices are connected/disconnected
     */
    public void autoDetectMode(android.content.Context context) {
        // Check for physical keyboard/mouse
        int[] deviceIds = android.view.InputDevice.getDeviceIds();
        boolean hasKeyboard = false;
        boolean hasMouse = false;
        
        for (int deviceId : deviceIds) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(deviceId);
            if (device != null) {
                int sources = device.getSources();
                if ((sources & android.view.InputDevice.SOURCE_KEYBOARD) != 0) {
                    hasKeyboard = true;
                }
                if ((sources & android.view.InputDevice.SOURCE_MOUSE) != 0) {
                    hasMouse = true;
                }
            }
        }
        
        // Decide mode based on available devices
        if (hasKeyboard && hasMouse) {
            Log.i(TAG, "Physical keyboard + mouse detected");
            setMode(InputMode.PHYSICAL_PASSTHROUGH);
        } else if (context.getPackageManager().hasSystemFeature("android.hardware.type.automotive")) {
            Log.i(TAG, "Android Automotive detected");
            setMode(InputMode.AUTOMOTIVE_ROTARY);
        } else {
            Log.i(TAG, "Touchscreen mode (default)");
            setMode(InputMode.TOUCHPAD_EMULATION);
        }
    }
}
