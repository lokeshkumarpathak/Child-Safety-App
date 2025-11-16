package com.example.child_safety_app_version1.utils.celltower

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Location method enum
 */
enum class LocationMethod {
    GPS,
    CELL_TOWER,
    CACHED_GPS,
    UNKNOWN
}

/**
 * Result of hybrid location lookup
 */
sealed class HybridLocationResult {
    data class Success(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val method: LocationMethod,
        val timestamp: Long = System.currentTimeMillis()
    ) : HybridLocationResult()

    data class Error(val message: String) : HybridLocationResult()
}

/**
 * Smart location strategy that chooses between GPS and Cell Tower
 * based on availability and quality
 */
class HybridLocationStrategy(private val context: Context) {

    companion object {
        private const val TAG = "HybridLocationStrategy"
        private const val GPS_TIMEOUT_MS = 15000L // 15 seconds
        private const val GPS_FAILURE_THRESHOLD = 3 // Switch to cell tower after 3 failures
        private const val CACHED_GPS_MAX_AGE_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val cellTowerManager: CellTowerManager by lazy {
        CellTowerManager(context)
    }

    private val cellTowerLocationProvider: CellTowerLocationProvider by lazy {
        CellTowerLocationProvider(context)
    }

    private val networkMonitor: NetworkConnectivityMonitor by lazy {
        NetworkConnectivityMonitor(context)
    }

    // Track consecutive GPS failures
    private var consecutiveGpsFailures = 0
    private var lastGpsLocation: Location? = null
    private var lastGpsTime = 0L

    /**
     * Get current location using the best available method
     */
    suspend fun getCurrentLocation(): HybridLocationResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "HYBRID LOCATION REQUEST")
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "Consecutive GPS failures: $consecutiveGpsFailures")

        // Check if GPS is enabled
        val isGpsEnabled = isGpsEnabled()
        Log.d(TAG, "GPS Enabled: $isGpsEnabled")

        // Decision: Try GPS first if enabled and not too many failures
        if (isGpsEnabled && consecutiveGpsFailures < GPS_FAILURE_THRESHOLD) {
            Log.d(TAG, "📡 STRATEGY: Attempting GPS...")

            val gpsResult = tryGetGpsLocation()

            if (gpsResult is HybridLocationResult.Success) {
                consecutiveGpsFailures = 0
                lastGpsLocation = Location("gps").apply {
                    latitude = gpsResult.latitude
                    longitude = gpsResult.longitude
                    accuracy = gpsResult.accuracy
                    time = System.currentTimeMillis()
                }
                lastGpsTime = System.currentTimeMillis()

                Log.d(TAG, "✅ GPS SUCCESS")
                Log.d(TAG, "═══════════════════════════════════════════════════")
                return@withContext gpsResult
            } else {
                consecutiveGpsFailures++
                Log.w(TAG, "⚠️ GPS FAILED (Attempt $consecutiveGpsFailures/$GPS_FAILURE_THRESHOLD)")
            }
        } else if (!isGpsEnabled) {
            Log.w(TAG, "⚠️ GPS is disabled by user")
        } else {
            Log.w(TAG, "⚠️ Too many GPS failures - switching to cell tower mode")
        }

        // Try using cached GPS if recent enough
        if (lastGpsLocation != null) {
            val age = System.currentTimeMillis() - lastGpsTime
            if (age < CACHED_GPS_MAX_AGE_MS) {
                Log.d(TAG, "📦 Using cached GPS (${age/1000}s old)")
                Log.d(TAG, "═══════════════════════════════════════════════════")
                return@withContext HybridLocationResult.Success(
                    latitude = lastGpsLocation!!.latitude,
                    longitude = lastGpsLocation!!.longitude,
                    accuracy = lastGpsLocation!!.accuracy,
                    method = LocationMethod.CACHED_GPS
                )
            } else {
                Log.d(TAG, "⏰ Cached GPS too old (${age/1000}s)")
            }
        }

        // Fallback to Cell Tower
        Log.d(TAG, "🗼 STRATEGY: Falling back to Cell Tower...")
        val cellResult = tryGetCellTowerLocation()

        if (cellResult is HybridLocationResult.Success) {
            Log.d(TAG, "✅ CELL TOWER SUCCESS")
        } else {
            Log.e(TAG, "❌ CELL TOWER FAILED")
        }

        Log.d(TAG, "═══════════════════════════════════════════════════")
        return@withContext cellResult
    }

    /**
     * Try to get location from GPS
     */
    private suspend fun tryGetGpsLocation(): HybridLocationResult {
        if (!hasLocationPermission()) {
            return HybridLocationResult.Error("Missing location permission")
        }

        return try {
            Log.d(TAG, "  Requesting GPS location (timeout: ${GPS_TIMEOUT_MS}ms)...")

            val cancellationToken = CancellationTokenSource()

            val location = withTimeoutOrNull(GPS_TIMEOUT_MS) {
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationToken.token
                ).await()
            }

            if (location != null) {
                Log.d(TAG, "  📍 GPS Location: ${location.latitude}, ${location.longitude}")
                Log.d(TAG, "  📏 Accuracy: ±${location.accuracy.toInt()}m")

                HybridLocationResult.Success(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    method = LocationMethod.GPS
                )
            } else {
                Log.w(TAG, "  ⚠️ GPS timeout - no location received")
                HybridLocationResult.Error("GPS timeout")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "  ❌ Security exception", e)
            HybridLocationResult.Error("Permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ GPS exception", e)
            HybridLocationResult.Error("GPS error: ${e.message}")
        }
    }

    /**
     * Try to get location from cell towers
     */
    private suspend fun tryGetCellTowerLocation(): HybridLocationResult {
        try {
            Log.d(TAG, "  Getting cell tower information...")

            val towers = cellTowerManager.getAllCellTowers()

            if (towers.isEmpty()) {
                Log.e(TAG, "  ❌ No cell towers detected")
                return HybridLocationResult.Error("No cell towers available")
            }

            Log.d(TAG, "  📡 Found ${towers.size} tower(s)")

            // Check if we should use online API
            val useApi = networkMonitor.isGoodEnoughForApiCalls()
            Log.d(TAG, "  Use Online API: $useApi")

            // Try triangulation if multiple towers available
            val result = if (towers.size > 1) {
                Log.d(TAG, "  Using triangulation with ${towers.size} towers")
                cellTowerLocationProvider.triangulateFromMultipleTowers(towers, useApi)
            } else {
                Log.d(TAG, "  Using single tower lookup")
                cellTowerLocationProvider.getLocationFromCellTower(towers.first(), useApi)
            }

            return when (result) {
                is CellLocationResult.Success -> {
                    Log.d(TAG, "  📍 Cell Tower Location: ${result.latitude}, ${result.longitude}")
                    Log.d(TAG, "  📏 Accuracy: ±${result.accuracy.toInt()}m")
                    Log.d(TAG, "  📦 Source: ${result.source}")

                    HybridLocationResult.Success(
                        latitude = result.latitude,
                        longitude = result.longitude,
                        accuracy = result.accuracy,
                        method = LocationMethod.CELL_TOWER
                    )
                }
                is CellLocationResult.Error -> {
                    Log.e(TAG, "  ❌ Cell tower lookup failed: ${result.message}")
                    HybridLocationResult.Error(result.message)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Cell tower exception", e)
            return HybridLocationResult.Error("Cell tower error: ${e.message}")
        }
    }

    /**
     * Check if GPS is enabled
     */
    private fun isGpsEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if we have location permission
     */
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Reset GPS failure counter (call when GPS is re-enabled)
     */
    fun resetGpsFailureCounter() {
        consecutiveGpsFailures = 0
        Log.d(TAG, "🔄 GPS failure counter reset")
    }

    /**
     * Force cell tower mode (for testing)
     */
    suspend fun forceCellTowerMode(): HybridLocationResult {
        Log.d(TAG, "⚙️ FORCED CELL TOWER MODE")
        return tryGetCellTowerLocation()
    }

    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Hybrid Location Strategy ===")
            appendLine("GPS Enabled: ${isGpsEnabled()}")
            appendLine("GPS Failures: $consecutiveGpsFailures/$GPS_FAILURE_THRESHOLD")
            appendLine("Has Permission: ${hasLocationPermission()}")
            appendLine("Last GPS Time: ${if (lastGpsTime > 0) "${(System.currentTimeMillis() - lastGpsTime)/1000}s ago" else "Never"}")
            appendLine("\n${networkMonitor.getDebugInfo()}")
            appendLine("\n${cellTowerManager.getDebugInfo()}")
        }
    }
}