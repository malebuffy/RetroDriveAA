package com.dosbox.emu;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Utility class for managing per-game configuration
 */
public class ConfigUtils {
    private static final String TAG = "ConfigUtils";
    
    /**
     * Clear all configuration for a specific game
     * This should be called when a game is deleted
     */
    public static void clearGameConfig(Context context, String gameFolder) {
        if (gameFolder == null || gameFolder.isEmpty()) {
            Log.w(TAG, "Cannot clear config for null/empty game folder");
            return;
        }
        
        String prefix = "game_" + gameFolder + "_";
        Log.d(TAG, "Clearing configuration for game: " + gameFolder + " (prefix: " + prefix + ")");
        
        // Clear D-Pad configuration
        clearGameConfigFromPrefs(context, "dpad_config", prefix);
        
        // Clear Action Buttons configuration
        clearGameConfigFromPrefs(context, "action_buttons_config", prefix);
        
        Log.d(TAG, "Configuration cleared for game: " + gameFolder);
    }
    
    private static void clearGameConfigFromPrefs(Context context, String prefsName, String prefix) {
        SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        // Get all keys and remove those that start with the game prefix
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
                Log.d(TAG, "Removed config key: " + key + " from " + prefsName);
            }
        }
        
        editor.apply();
    }
}
