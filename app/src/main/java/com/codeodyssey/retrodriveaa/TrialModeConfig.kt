package com.codeodyssey.retrodriveaa

import android.content.Context

object TrialModeConfig {
    const val TRIAL_MODE_ENABLED = false

    const val STRIPE_PUBLISHABLE_KEY = "pk_live_51R8oEtFyYAX0cIaBBcInQ4WXAjGAcg5tyQlnMsOiMSlj2pV8GoD26ZpXgXgN0isObzBltA4e05zHDB3eAyLmrVNN00x3UCDIhd"
    const val STRIPE_BACKEND_URL = "https://www.code-odyssey.com/cart-session.php"
    const val STRIPE_AMOUNT_CENTS = 999
    const val STRIPE_MERCHANT_NAME = "RetrodriveAA"

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_PURCHASED = "purchased"
    private const val KEY_PURCHASED_FORCED_BY_TRIAL_DISABLED = "purchased_forced_by_trial_disabled"
    private const val KEY_HAS_DISMISSED_TRIAL = "has_dismissed_trial"
    private const val KEY_TRIAL_WIFI_UPLOAD_USED = "trial_wifi_upload_used"

    fun ensureTrialStateInitialized(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PURCHASED, true)
            .putBoolean(KEY_PURCHASED_FORCED_BY_TRIAL_DISABLED, true)
            .remove(KEY_HAS_DISMISSED_TRIAL)
            .remove(KEY_TRIAL_WIFI_UPLOAD_USED)
            .apply()
    }

    fun isPurchased(context: Context): Boolean {
        return true
    }

    fun setPurchased(context: Context, purchased: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PURCHASED, true)
            .apply()
    }

    fun hasDismissedTrial(context: Context): Boolean {
        return true
    }

    fun setHasDismissedTrial(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HAS_DISMISSED_TRIAL)
            .apply()
    }

    fun isWifiUploadAllowed(context: Context): Boolean {
        return true
    }

    fun markTrialWifiUploadSuccess(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TRIAL_WIFI_UPLOAD_USED)
            .apply()
    }
}
