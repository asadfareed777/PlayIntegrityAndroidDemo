package com.example.playintegrity

import android.app.Application
import android.os.Build
import com.example.playintegrity.cache.EncryptedConfigCache
import com.example.playintegrity.data.SecureConfigRepository
import com.example.playintegrity.integrity.PlayIntegrityTokenSource
import com.example.playintegrity.nativebridge.NativeBridge
import com.example.playintegrity.network.AndroidConnectivityMonitor
import com.example.playintegrity.network.OkHttpConfigApi

/** Manual DI: one repository for the whole process. */
class IntegrityDemoApp : Application() {

    lateinit var repository: SecureConfigRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val info = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

        repository = SecureConfigRepository(
            tokenSource = PlayIntegrityTokenSource(this, BuildConfig.CLOUD_PROJECT_NUMBER),
            api = OkHttpConfigApi(BuildConfig.BACKEND_URL),
            cache = EncryptedConfigCache(this),
            connectivity = AndroidConnectivityMonitor(this),
            nativeSink = NativeBridge,
            packageName = packageName,
            appVersionCode = versionCode,
        )
    }
}
