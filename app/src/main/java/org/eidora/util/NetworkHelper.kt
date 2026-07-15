package org.eidora.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkHelper {

    enum class NetworkStatus { WIFI, MOBILE, NONE }

    fun currentStatus(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus.NONE
        val network = cm.activeNetwork ?: return NetworkStatus.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkStatus.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.MOBILE
            else -> NetworkStatus.NONE
        }
    }
}
