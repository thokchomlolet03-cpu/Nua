package org.nua.production.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

class NetworkGate(context: Context) {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Statically audits active network interfaces to guarantee a legitimate
     * transport route to the open internet exists before executing cloud sync routines.
     */
    fun isCloudReachable(): Boolean {
        try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            Log.e("NetworkGate", "Failed to resolve system network capability matrices safely", e)
            return false
        }
    }
}
