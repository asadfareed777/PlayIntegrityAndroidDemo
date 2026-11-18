package com.example.playintegrity.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

interface ConnectivityMonitor {
    fun isOnline(): Boolean

    /** Suspends until a validated internet connection is available. */
    suspend fun awaitOnline()
}

class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override suspend fun awaitOnline() {
        if (isOnline()) return
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && cont.isActive) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        cont.resume(Unit)
                    }
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }
}
