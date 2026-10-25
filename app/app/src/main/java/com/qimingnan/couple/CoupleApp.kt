package com.qimingnan.couple

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.qimingnan.couple.data.AppCoordinator
import com.qimingnan.couple.data.keepalive.KeepAliveController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CoupleApp : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var coordinator: AppCoordinator
    @Inject lateinit var keepAlive: KeepAliveController
    @Inject lateinit var imageLoader: ImageLoader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Coil 全局加载器：走 apiClient（BaseUrlInterceptor 直通绝对地址 + Bearer，媒体经后端代理）。 */
    override fun newImageLoader(): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        coordinator.start()
        keepAlive.startIfEnabled()
    }
}
