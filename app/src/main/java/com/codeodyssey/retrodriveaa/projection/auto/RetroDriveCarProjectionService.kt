package com.codeodyssey.retrodriveaa.projection.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class RetroDriveCarProjectionService : CarAppService() {
    override fun onCreateSession(): Session = RetroDriveProjectionSession()

    override fun onCreateSession(sessionInfo: SessionInfo): Session = RetroDriveProjectionSession()

    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
}

private class RetroDriveProjectionSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = RetroDriveProjectionScreen(carContext)
}