package com.example.child_safety_app_version1.utils.celltower

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Network connection quality levels
 */
enum class NetworkQuality {
    NO_CONNECTION,      // No internet at all
    POOR,              // 2G/EDGE - Very slow
    MODERATE,          // 3G - Moderate speed
    GOOD,              // 4G/LTE - Good speed
    EXCELLENT          // 5G/WiFi - Excellent speed
}

/**
 * Network type classification
 */
enum class NetworkType {
    NONE,
    MOBILE_2G,
    MOBILE_3G,
    MOBILE_4G,
    MOBILE_5G,
    WIFI,
    ETHERNET,
    UNKNOWN
}

/**
 * Monitors network connectivity and determines connection quality
 */
class NetworkConnectivityMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    /**
     * Check if device has ANY internet connection
     */
    fun isInternetAvailable(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            val hasInternet = capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            Log.d(TAG, if (hasInternet) "✅ Internet available" else "❌ No internet connection")
            hasInternet

        } catch (e: Exception) {
            Log.e(TAG, "Error checking internet availability", e)
            false
        }
    }

    /**
     * Get current network type
     */
    fun getNetworkType(): NetworkType {
        return try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            if (capabilities == null) {
                Log.d(TAG, "No network capabilities - device offline")
                return NetworkType.NONE
            }

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    Log.d(TAG, "📶 Network: WiFi")
                    NetworkType.WIFI
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    Log.d(TAG, "🔌 Network: Ethernet")
                    NetworkType.ETHERNET
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val mobileType = getMobileNetworkType()
                    Log.d(TAG, "📱 Network: $mobileType")
                    mobileType
                }
                else -> {
                    Log.d(TAG, "❓ Network: Unknown")
                    NetworkType.UNKNOWN
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting network type", e)
            NetworkType.UNKNOWN
        }
    }

    /**
     * Get mobile network generation (2G/3G/4G/5G)
     */
    private fun getMobileNetworkType(): NetworkType {
        return try {
            // Check permission before accessing telephony data
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Missing READ_PHONE_STATE permission, cannot determine mobile network type")
                    return NetworkType.UNKNOWN
                }
            }

            @Suppress("DEPRECATION")
            when (telephonyManager.dataNetworkType) {
                // 2G networks
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN -> NetworkType.MOBILE_2G

                // 3G networks
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP -> NetworkType.MOBILE_3G

                // 4G networks
                TelephonyManager.NETWORK_TYPE_LTE,
                TelephonyManager.NETWORK_TYPE_IWLAN -> NetworkType.MOBILE_4G

                // 5G networks
                TelephonyManager.NETWORK_TYPE_NR -> NetworkType.MOBILE_5G

                else -> NetworkType.UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining mobile network type", e)
            NetworkType.UNKNOWN
        }
    }

    /**
     * Get overall network quality rating
     */
    fun getNetworkQuality(): NetworkQuality {
        if (!isInternetAvailable()) {
            return NetworkQuality.NO_CONNECTION
        }

        return when (getNetworkType()) {
            NetworkType.WIFI,
            NetworkType.ETHERNET,
            NetworkType.MOBILE_5G -> NetworkQuality.EXCELLENT

            NetworkType.MOBILE_4G -> NetworkQuality.GOOD

            NetworkType.MOBILE_3G -> NetworkQuality.MODERATE

            NetworkType.MOBILE_2G -> NetworkQuality.POOR

            NetworkType.NONE -> NetworkQuality.NO_CONNECTION

            NetworkType.UNKNOWN -> NetworkQuality.MODERATE // Conservative guess
        }
    }

    /**
     * Check if connection is good enough for API calls
     * Returns true for 3G and above
     */
    fun isGoodEnoughForApiCalls(): Boolean {
        val quality = getNetworkQuality()
        val isGood = quality in listOf(
            NetworkQuality.MODERATE,
            NetworkQuality.GOOD,
            NetworkQuality.EXCELLENT
        )

        Log.d(TAG, "API call feasibility: $isGood (Quality: $quality)")
        return isGood
    }

    /**
     * Check if we should use SMS fallback
     * Returns true for 2G or no connection
     */
    fun shouldUseSmsFallback(): Boolean {
        val quality = getNetworkQuality()
        val useSms = quality in listOf(
            NetworkQuality.NO_CONNECTION,
            NetworkQuality.POOR
        )

        Log.d(TAG, "SMS fallback recommended: $useSms (Quality: $quality)")
        return useSms
    }

    /**
     * Get network speed estimate in Mbps
     */
    fun getEstimatedSpeedMbps(): Double {
        return when (getNetworkType()) {
            NetworkType.WIFI -> 50.0
            NetworkType.ETHERNET -> 100.0
            NetworkType.MOBILE_5G -> 100.0
            NetworkType.MOBILE_4G -> 20.0
            NetworkType.MOBILE_3G -> 3.0
            NetworkType.MOBILE_2G -> 0.1
            NetworkType.NONE -> 0.0
            NetworkType.UNKNOWN -> 1.0
        }
    }

    /**
     * Check if device is connected to WiFi
     */
    fun isWifiConnected(): Boolean {
        return getNetworkType() == NetworkType.WIFI
    }

    /**
     * Check if device is on mobile data
     */
    fun isMobileDataConnected(): Boolean {
        return getNetworkType() in listOf(
            NetworkType.MOBILE_2G,
            NetworkType.MOBILE_3G,
            NetworkType.MOBILE_4G,
            NetworkType.MOBILE_5G
        )
    }

    /**
     * Get human-readable connection status
     */
    fun getConnectionStatusText(): String {
        val type = getNetworkType()
        val quality = getNetworkQuality()

        return when {
            type == NetworkType.NONE -> "No Connection"
            type == NetworkType.WIFI -> "WiFi - Excellent"
            type == NetworkType.MOBILE_5G -> "5G - Excellent"
            type == NetworkType.MOBILE_4G -> "4G - Good"
            type == NetworkType.MOBILE_3G -> "3G - Moderate"
            type == NetworkType.MOBILE_2G -> "2G - Poor"
            else -> "Connected - ${quality.name}"
        }
    }

    /**
     * Get detailed network information for debugging
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Network Status ===")
            appendLine("Internet Available: ${isInternetAvailable()}")
            appendLine("Network Type: ${getNetworkType()}")
            appendLine("Network Quality: ${getNetworkQuality()}")
            appendLine("Status: ${getConnectionStatusText()}")
            appendLine("Estimated Speed: ${getEstimatedSpeedMbps()} Mbps")
            appendLine("Good for API: ${isGoodEnoughForApiCalls()}")
            appendLine("Use SMS Fallback: ${shouldUseSmsFallback()}")
            appendLine("WiFi Connected: ${isWifiConnected()}")
            appendLine("Mobile Data: ${isMobileDataConnected()}")
        }
    }

    /**
     * Monitor network changes and log them
     */
    fun logNetworkChange() {
        Log.d(TAG, "═══════════════════════════════════")
        Log.d(TAG, "NETWORK CHANGE DETECTED")
        Log.d(TAG, "═══════════════════════════════════")
        Log.d(TAG, getDebugInfo())
        Log.d(TAG, "═══════════════════════════════════")
    }
}