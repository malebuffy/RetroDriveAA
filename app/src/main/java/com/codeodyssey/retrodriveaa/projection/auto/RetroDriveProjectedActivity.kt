package com.codeodyssey.retrodriveaa.projection.auto

import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.codeodyssey.retrodriveaa.RetroDriveCarLauncher

class RetroDriveProjectedActivity : RetroDriveCarLauncher() {
	override val bypassSafetyGate: Boolean = true

	override fun onCreate(savedInstanceState: Bundle?) {
		Log.d(
			TAG,
			"onCreate display=${display?.displayId} projected=${intent?.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_PROJECTED_MODE, false)} section=${intent?.getStringExtra(RetroDriveProjectedNavigation.EXTRA_OPEN_SECTION)} autoDos=${intent?.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_AUTO_LAUNCH_DOS, false)} game=${intent?.getStringExtra(RetroDriveProjectedNavigation.EXTRA_AUTO_LAUNCH_GAME_ID)} finishAfterDos=${intent?.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_FINISH_AFTER_DOS_LAUNCH, false)}"
		)
		super.onCreate(savedInstanceState)
	}

	override fun onNewIntent(intent: Intent) {
		Log.d(
			TAG,
			"onNewIntent display=${display?.displayId} projected=${intent.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_PROJECTED_MODE, false)} section=${intent.getStringExtra(RetroDriveProjectedNavigation.EXTRA_OPEN_SECTION)} autoDos=${intent.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_AUTO_LAUNCH_DOS, false)} game=${intent.getStringExtra(RetroDriveProjectedNavigation.EXTRA_AUTO_LAUNCH_GAME_ID)} finishAfterDos=${intent.getBooleanExtra(RetroDriveProjectedNavigation.EXTRA_FINISH_AFTER_DOS_LAUNCH, false)}"
		)
		super.onNewIntent(intent)
	}

	private companion object {
		private const val TAG = "RetroDriveProjected"
	}
}