package com.dosbox.emu.input;

import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Abstract strategy interface for different input control schemes.
 * Each implementation handles input events differently and routes them
 * to the DOSBox native bridge.
 */
public interface InputControllerStrategy {
    
    /**
     * Process touch events (for touchscreen input)
     * @param event The touch event
     * @return true if event was handled, false otherwise
     */
    boolean processTouchEvent(MotionEvent event);
    
    /**
     * Process key events (for keyboard input)
     * @param event The key event
     * @return true if event was handled, false otherwise
     */
    boolean processKeyEvent(KeyEvent event);
    
    /**
     * Process generic motion events (for mouse, rotary, joystick)
     * @param event The generic motion event
     * @return true if event was handled, false otherwise
     */
    boolean processGenericEvent(MotionEvent event);
    
    /**
     * Called when this strategy becomes active
     */
    void onActivate();
    
    /**
     * Called when this strategy is being deactivated
     */
    void onDeactivate();
    
    /**
     * Get the name of this input mode for logging/debugging
     */
    String getModeName();
}
