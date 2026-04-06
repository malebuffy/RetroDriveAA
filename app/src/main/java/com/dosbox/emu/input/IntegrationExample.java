package com.dosbox.emu.input;

/**
 * EXAMPLE: How to integrate InputDirector into your DOSBoxActivity
 * 
 * This is a REFERENCE IMPLEMENTATION showing how to wire up the Strategy Pattern
 * into your existing Activity. Copy and adapt the relevant parts into your
 * actual DOSBoxActivity.java file.
 */
public class IntegrationExample {
    
    /*
     * ============================================================================
     * STEP 1: Add InputDirector as a field in your Activity
     * ============================================================================
     */
    
    // In DOSBoxActivity.java, add this field:
    // private InputDirector inputDirector;
    
    
    /*
     * ============================================================================
     * STEP 2: Initialize InputDirector in onCreate()
     * ============================================================================
     */
    
    /*
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // ... your existing onCreate code ...
        
        // Initialize input director with auto-detection
        inputDirector = new InputDirector();
        inputDirector.autoDetectMode(this);
        
        // OR manually set a specific mode:
        // inputDirector = new InputDirector(InputDirector.InputMode.TOUCHPAD_EMULATION);
    }
    */
    
    
    /*
     * ============================================================================
     * STEP 3: Route touch events to InputDirector
     * ============================================================================
     */
    
    /*
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Try to handle via input director first
        if (inputDirector.processTouchEvent(event)) {
            return true; // Event was handled by our strategy
        }
        
        // Fall back to default handling
        return super.dispatchTouchEvent(event);
    }
    
    // OR if you prefer using onTouchEvent:
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (inputDirector.processTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }
    */
    
    
    /*
     * ============================================================================
     * STEP 4: Route key events to InputDirector
     * ============================================================================
     */
    
    /*
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Try to handle via input director first
        if (inputDirector.processKeyEvent(event)) {
            return true; // Event was handled by our strategy
        }
        
        // Fall back to default handling
        return super.dispatchKeyEvent(event);
    }
    
    // OR if you prefer using onKeyDown/onKeyUp:
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (inputDirector.processKeyEvent(event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
    
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (inputDirector.processKeyEvent(event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    */
    
    
    /*
     * ============================================================================
     * STEP 5: Route generic motion events to InputDirector (for rotary, mouse)
     * ============================================================================
     */
    
    /*
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (inputDirector.processGenericEvent(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
    */
    
    
    /*
     * ============================================================================
     * STEP 6: Hot-swap modes at runtime (optional)
     * ============================================================================
     */
    
    /*
    // Example: Add a button to switch modes
    private void switchInputMode() {
        InputDirector.InputMode currentMode = inputDirector.getCurrentMode();
        
        switch (currentMode) {
            case TOUCHPAD_EMULATION:
                inputDirector.setMode(InputDirector.InputMode.PHYSICAL_PASSTHROUGH);
                Toast.makeText(this, "Switched to Physical mode", Toast.LENGTH_SHORT).show();
                break;
            case PHYSICAL_PASSTHROUGH:
                inputDirector.setMode(InputDirector.InputMode.AUTOMOTIVE_ROTARY);
                Toast.makeText(this, "Switched to Rotary mode", Toast.LENGTH_SHORT).show();
                break;
            case AUTOMOTIVE_ROTARY:
                inputDirector.setMode(InputDirector.InputMode.TOUCHPAD_EMULATION);
                Toast.makeText(this, "Switched to Touchpad mode", Toast.LENGTH_SHORT).show();
                break;
        }
    }
    */
    
    
    /*
     * ============================================================================
     * STEP 7: Handle configuration changes
     * ============================================================================
     */
    
    /*
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        // Re-detect input mode when configuration changes
        // (e.g., keyboard connected/disconnected)
        if (inputDirector != null) {
            inputDirector.autoDetectMode(this);
        }
    }
    */
    
    
    /*
     * ============================================================================
     * COMPLETE MINIMAL EXAMPLE:
     * ============================================================================
     */
    
    /*
    public class DOSBoxActivity extends SDLActivity {
        private InputDirector inputDirector;
        
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            
            // Initialize input system
            inputDirector = new InputDirector();
            inputDirector.autoDetectMode(this);
        }
        
        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (inputDirector.processTouchEvent(event)) {
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
        
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (inputDirector.processKeyEvent(event)) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        
        @Override
        public boolean onGenericMotionEvent(MotionEvent event) {
            if (inputDirector.processGenericEvent(event)) {
                return true;
            }
            return super.onGenericMotionEvent(event);
        }
    }
    */
}
