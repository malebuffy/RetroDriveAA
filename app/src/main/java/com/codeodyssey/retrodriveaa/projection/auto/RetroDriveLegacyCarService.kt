package com.codeodyssey.retrodriveaa.projection.auto

import android.util.Log
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

class RetroDriveLegacyCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> {
        Log.d(TAG, "getCarActivity -> RetroDriveLegacyCarActivity")
        return RetroDriveLegacyCarActivity::class.java
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "RetroDriveLegacySvc"
    }
}