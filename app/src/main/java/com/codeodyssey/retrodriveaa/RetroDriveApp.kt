package com.codeodyssey.retrodriveaa

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.lang.ref.WeakReference

class RetroDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                RetroDriveForegroundActivityTracker.markResumed(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                RetroDriveForegroundActivityTracker.markPaused(activity)
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                RetroDriveForegroundActivityTracker.clear(activity)
            }
        })
    }

    companion object {
        lateinit var instance: RetroDriveApp
            private set
    }
}

object RetroDriveForegroundActivityTracker {
    private const val TAG = "RetroDriveActivity"
    private val lock = Any()
    private val resumedActivities = mutableListOf<WeakReference<Activity>>()

    fun currentActivity(): Activity? = synchronized(lock) {
        pruneLocked()
        resumedActivities.asReversed().firstNotNullOfOrNull { it.get() }
    }

    fun findActivity(predicate: (Activity) -> Boolean): Activity? = synchronized(lock) {
        pruneLocked()
        resumedActivities.asReversed()
            .mapNotNull { it.get() }
            .firstOrNull(predicate)
    }

    fun markResumed(activity: Activity) = synchronized(lock) {
        pruneLocked()
        resumedActivities.removeAll { it.get() === activity }
        resumedActivities.add(WeakReference(activity))
        Log.d(TAG, "resumed ${activity.javaClass.simpleName} display=${activity.display?.displayId}")
    }

    fun markPaused(activity: Activity) = synchronized(lock) {
        val removed = resumedActivities.removeAll { it.get() === activity || it.get() == null }
        if (removed) {
            Log.d(TAG, "paused ${activity.javaClass.simpleName} display=${activity.display?.displayId}")
        }
    }

    fun clear(activity: Activity) = synchronized(lock) {
        val removed = resumedActivities.removeAll { it.get() === activity || it.get() == null }
        if (removed) {
            Log.d(TAG, "cleared ${activity.javaClass.simpleName} display=${activity.display?.displayId}")
        }
    }

    private fun pruneLocked() {
        resumedActivities.removeAll { it.get() == null }
    }
}