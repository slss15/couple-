package com.qimingnan.couple

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.qimingnan.couple.data.AppCoordinator
import com.qimingnan.couple.data.keepalive.KeepAliveController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CoupleApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var coordinator: AppCoordinator
    @Inject lateinit var keepAlive: KeepAliveController

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        coordinator.start()
        keepAlive.startIfEnabled()
    }
}
