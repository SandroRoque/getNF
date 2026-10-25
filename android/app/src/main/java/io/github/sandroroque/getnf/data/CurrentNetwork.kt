package io.github.sandroroque.getnf.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager

/** Which network (≈ which public IP) SEFAZ would see right now. The key picks the [LookupBudget] bucket. */
data class CurrentNetwork(val key: String, val label: String) {
  companion object {
    fun of(context: Context): CurrentNetwork {
      val cm = context.getSystemService(ConnectivityManager::class.java)
      val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return CurrentNetwork("none", "sem conexão")
      return when {
        // Telling Wi-Fi networks apart needs the location permission; one bucket is good enough for a home network.
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> CurrentNetwork("wifi", "Wi-Fi")
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
          // Each SIM is a different carrier, so a different IP. Needs no permission on Android 11+.
          val sub = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) SubscriptionManager.getActiveDataSubscriptionId() else -1
          if (sub >= 0) CurrentNetwork("cellular:$sub", "dados móveis (chip $sub)") else CurrentNetwork("cellular", "dados móveis")
        }
        else -> CurrentNetwork("other", "esta rede")
      }
    }
  }
}
