package com.example.child_safety_app_version1.utils.celltower

import android.content.Context
import android.util.Log
import com.example.child_safety_app_version1.database.celltower.CellTowerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Result of location lookup from cell tower
 */
sealed class CellLocationResult {
    data class Success(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val source: String // "API" or "DATABASE"
    ) : CellLocationResult()

    data class Error(val message: String) : CellLocationResult()
}

/**
 * Provides location lookup from cell tower information
 */
class CellTowerLocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "CellLocationProvider"

        // OpenCelliD API Configuration
        private const val OPENCELLID_API_KEY = "pk.ff38226425bd439d4fc2277f6c5677e8" // TODO: Replace with actual key
        private const val OPENCELLID_BASE_URL = "https://opencellid.org/cell/get"

        // Unwired Labs (Alternative API)
        private const val UNWIRED_API_KEY = "YOUR_UNWIRED_KEY_HERE" // TODO: Replace with actual key
        private const val UNWIRED_BASE_URL = "https://us1.unwiredlabs.com/v2/process.php"
    }

    private val database: CellTowerDatabase by lazy {
        CellTowerDatabase.getInstance(context)
    }

    /**
     * Get location from cell tower info
     * Tries online API first, falls back to local database
     */
    suspend fun getLocationFromCellTower(
        tower: CellTowerInfo,
        useOnlineApi: Boolean = true
    ): CellLocationResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "═══════════════════════════════════")
        Log.d(TAG, "CELL TOWER LOCATION LOOKUP")
        Log.d(TAG, "═══════════════════════════════════")
        Log.d(TAG, "Tower Details:")
        Log.d(TAG, "  Type: ${tower.networkType}")
        Log.d(TAG, "  MCC: ${tower.mcc}")
        Log.d(TAG, "  MNC: ${tower.mnc}")
        Log.d(TAG, "  CID: ${tower.cellId}")
        Log.d(TAG, "  LAC: ${tower.lac}")
        Log.d(TAG, "  Signal: ${tower.signalStrength}/4")
        Log.d(TAG, "═══════════════════════════════════")

        // Try online API if enabled and we have internet
        if (useOnlineApi) {
            Log.d(TAG, "🌐 Attempting online API lookup...")

            val apiResult = lookupViaOpenCelliD(tower)
            if (apiResult is CellLocationResult.Success) {
                Log.d(TAG, "✅ API lookup successful!")
                return@withContext apiResult
            } else {
                Log.w(TAG, "⚠️ API lookup failed, trying database...")
            }
        } else {
            Log.d(TAG, "📴 Skipping API (offline mode)")
        }

        // Fallback to local database
        Log.d(TAG, "💾 Attempting database lookup...")
        val dbResult = lookupViaLocalDatabase(tower)

        if (dbResult is CellLocationResult.Success) {
            Log.d(TAG, "✅ Database lookup successful!")
        } else {
            Log.e(TAG, "❌ Database lookup failed")
        }

        return@withContext dbResult
    }

    /**
     * Lookup location using OpenCelliD API
     */
    private suspend fun lookupViaOpenCelliD(tower: CellTowerInfo): CellLocationResult {
        return try {
            // Build URL
            val urlString = buildString {
                append(OPENCELLID_BASE_URL)
                append("?key=$OPENCELLID_API_KEY")
                append("&mcc=${tower.mcc}")
                append("&mnc=${tower.mnc}")
                append("&lac=${tower.lac}")
                append("&cellid=${tower.cellId}")
                append("&format=json")
            }

            Log.d(TAG, "  API URL: ${urlString.replace(OPENCELLID_API_KEY, "***")}")

            // Make HTTP request
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000 // 10 seconds
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            Log.d(TAG, "  Response Code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                Log.d(TAG, "  Response: ${response.take(200)}...")

                // Parse JSON response
                val json = JSONObject(response)

                if (json.has("lat") && json.has("lon")) {
                    val lat = json.getDouble("lat")
                    val lon = json.getDouble("lon")
                    val range = json.optInt("range", 500) // Default 500m accuracy

                    Log.d(TAG, "  📍 Location: $lat, $lon")
                    Log.d(TAG, "  📏 Accuracy: ±${range}m")

                    CellLocationResult.Success(
                        latitude = lat,
                        longitude = lon,
                        accuracy = range.toFloat(),
                        source = "API"
                    )
                } else {
                    CellLocationResult.Error("API response missing lat/lon")
                }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "  API Error: $errorBody")
                CellLocationResult.Error("API returned code $responseCode")
            }

        } catch (e: Exception) {
            Log.e(TAG, "  Exception during API call", e)
            CellLocationResult.Error("API exception: ${e.message}")
        }
    }

    /**
     * Lookup location using local Room database
     */
    private suspend fun lookupViaLocalDatabase(tower: CellTowerInfo): CellLocationResult {
        return try {
            val dao = database.cellTowerDao()

            // Query database for matching tower
            val entity = dao.findCellTower(
                mcc = tower.mcc,
                mnc = tower.mnc,
                lac = tower.lac,
                cellId = tower.cellId
            )

            if (entity != null) {
                Log.d(TAG, "  📍 Location: ${entity.latitude}, ${entity.longitude}")
                Log.d(TAG, "  📏 Accuracy: ±${entity.range}m")
                Log.d(TAG, "  🗓️ Last Updated: ${entity.updated}")

                CellLocationResult.Success(
                    latitude = entity.latitude,
                    longitude = entity.longitude,
                    accuracy = entity.range.toFloat(),
                    source = "DATABASE"
                )
            } else {
                Log.w(TAG, "  ❌ Tower not found in database")
                Log.w(TAG, "  Searched for: MCC=${tower.mcc}, MNC=${tower.mnc}, LAC=${tower.lac}, CID=${tower.cellId}")
                CellLocationResult.Error("Tower not found in local database")
            }

        } catch (e: Exception) {
            Log.e(TAG, "  Exception during database query", e)
            CellLocationResult.Error("Database error: ${e.message}")
        }
    }

    /**
     * Triangulate location from multiple cell towers
     * Returns weighted average based on signal strength
     */
    suspend fun triangulateFromMultipleTowers(
        towers: List<CellTowerInfo>,
        useOnlineApi: Boolean = true
    ): CellLocationResult {

        if (towers.isEmpty()) {
            return CellLocationResult.Error("No towers provided")
        }

        Log.d(TAG, "🔺 Starting triangulation with ${towers.size} tower(s)")

        // Get locations for all towers
        val locations = mutableListOf<Pair<CellLocationResult.Success, Int>>()

        for (tower in towers) {
            val result = getLocationFromCellTower(tower, useOnlineApi)
            if (result is CellLocationResult.Success) {
                locations.add(result to tower.signalStrength)
            }
        }

        if (locations.isEmpty()) {
            return CellLocationResult.Error("No valid locations from towers")
        }

        if (locations.size == 1) {
            Log.d(TAG, "  Only 1 tower location available, using it directly")
            return locations.first().first
        }

        // Calculate weighted average
        var totalLat = 0.0
        var totalLon = 0.0
        var totalWeight = 0.0

        locations.forEach { (location, signal) ->
            val weight = signal.toDouble() // Higher signal = more weight
            totalLat += location.latitude * weight
            totalLon += location.longitude * weight
            totalWeight += weight
        }

        val avgLat = totalLat / totalWeight
        val avgLon = totalLon / totalWeight

        // Calculate average accuracy (pessimistic approach)
        val avgAccuracy = locations.map { it.first.accuracy }.average().toFloat()

        Log.d(TAG, "  ✅ Triangulation complete:")
        Log.d(TAG, "     Location: $avgLat, $avgLon")
        Log.d(TAG, "     Accuracy: ±${avgAccuracy.toInt()}m")
        Log.d(TAG, "     Sources: ${locations.map { it.first.source }.distinct()}")

        return CellLocationResult.Success(
            latitude = avgLat,
            longitude = avgLon,
            accuracy = avgAccuracy,
            source = "TRIANGULATION"
        )
    }

    /**
     * Check if we have API key configured
     */
    fun hasApiKeyConfigured(): Boolean {
        return OPENCELLID_API_KEY != "YOUR_API_KEY_HERE"
    }
}