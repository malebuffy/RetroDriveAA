package com.dosbox.emu;

/**
 * JNI bridge for DOSBox-specific native functions.
 * These functions call DOSBox code directly, not just SDL.
 */
public class DOSBoxJNI {
    
    /**
     * Send joystick axis position directly to DOSBox emulated joystick.
     * This bypasses SDL joystick events and directly updates the virtual joystick state.
     * 
     * @param x X-axis position (-1.0 = full left, 0.0 = center, 1.0 = full right)
     * @param y Y-axis position (-1.0 = full up, 0.0 = center, 1.0 = full down)
     */
    public static native void nativeJoystickAxis(float x, float y);

    public static native void nativeSetSaveStateContext(String gameId, String statePath);

    public static native void nativeSetEmbeddedSessionMode(boolean embedded);

    public static native void nativeResetSdlAndroidSessionState();

    public static native void nativeRequestLoadState();

    public static native boolean nativeSaveStateAndWait(int timeoutMs);
    
    static {
        // Native library is already loaded by SDLActivity
        // Just need to ensure our methods are registered
    }
}
