package com.example.child_safety_app_version1.utils

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2

data class SafeZone(
    val id: String,
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val boundingBox: List<Double>?,
    val radius: Double,
    val type: String
)

object SafeZoneChecker {
    private const val TAG = "SafeZoneChecker"
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Check if a location is inside any of the child's parent's safe zones
     */
    suspend fun isLocationInAnySafeZone(
        childUid: String,
        latitude: Double,
        longitude: Double
    ): Boolean {
        return try {
            val safeZones = getAllSafeZonesForChild(childUid)

            if (safeZones.isEmpty()) {
                Log.w(TAG, "No safe zones found for child $childUid")
                return true // If no safe zones defined, consider child safe
            }

            Log.d(TAG, "Checking ${safeZones.size} safe zone(s)")

            for (zone in safeZones) {
                val isInside = if (zone.boundingBox != null && zone.boundingBox.size >= 4) {
                    isInsideBoundingBox(latitude, longitude, zone.boundingBox)
                } else {
                    isInsideCircle(latitude, longitude, zone.centerLat, zone.centerLon, zone.radius)
                }

                if (isInside) {
                    Log.d(TAG, "Location IS inside safe zone: ${zone.name}")
                    return true
                }
            }

            Log.d(TAG, "Location is NOT inside any safe zone")
            false

        } catch (e: Exception) {
            Log.e(TAG, "Error checking safe zones", e)
            true // On error, assume safe to avoid false alarms
        }
    }

    /**
     * Get all safe zones for a child by querying all their parents' safe zones
     */
    private suspend fun getAllSafeZonesForChild(childUid: String): List<SafeZone> {
        val db = FirebaseFirestore.getInstance()
        val allSafeZones = mutableListOf<SafeZone>()

        try {
            // Get all parents from child's parents subcollection
            val parentsSnapshot = db.collection("users")
                .document(childUid)
                .collection("parents")
                .get()
                .await()

            Log.d(TAG, "Found ${parentsSnapshot.size()} parent(s)")

            // For each parent, get their safe zones
            for (parentDoc in parentsSnapshot.documents) {
                val parentId = parentDoc.getString("parentId")
                if (parentId != null) {
                    val parentSafeZones = getSafeZonesForParent(parentId)
                    allSafeZones.addAll(parentSafeZones)
                    Log.d(TAG, "Parent $parentId has ${parentSafeZones.size} safe zone(s)")
                }
            }

            Log.d(TAG, "Total safe zones collected: ${allSafeZones.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Error getting safe zones for child", e)
        }

        return allSafeZones
    }

    /**
     * Get safe zones for a specific parent
     */
    private suspend fun getSafeZonesForParent(parentUid: String): List<SafeZone> {
        val db = FirebaseFirestore.getInstance()
        val safeZones = mutableListOf<SafeZone>()

        try {
            val zonesSnapshot = db.collection("users")
                .document(parentUid)
                .collection("safeZones")
                .get()
                .await()

            for (zoneDoc in zonesSnapshot.documents) {
                try {
                    val zone = SafeZone(
                        id = zoneDoc.getString("id") ?: zoneDoc.id,
                        name = zoneDoc.getString("name") ?: "Unnamed Zone",
                        centerLat = (zoneDoc.get("centerLat") as? Number)?.toDouble() ?: 0.0,
                        centerLon = (zoneDoc.get("centerLon") as? Number)?.toDouble() ?: 0.0,
                        boundingBox = (zoneDoc.get("boundingBox") as? List<*>)?.mapNotNull {
                            (it as? Number)?.toDouble()
                        },
                        radius = (zoneDoc.get("radius") as? Number)?.toDouble() ?: 100.0,
                        type = zoneDoc.getString("type") ?: "custom"
                    )
                    safeZones.add(zone)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing safe zone: ${zoneDoc.id}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting safe zones for parent $parentUid", e)
        }

        return safeZones
    }

    /**
     * Check if a point is inside a bounding box
     * BoundingBox format: [minLat, maxLat, minLon, maxLon]
     */
    private fun isInsideBoundingBox(
        lat: Double,
        lon: Double,
        boundingBox: List<Double>
    ): Boolean {
        if (boundingBox.size < 4) return false

        val minLat = boundingBox[0]
        val maxLat = boundingBox[1]
        val minLon = boundingBox[2]
        val maxLon = boundingBox[3]

        return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon
    }

    /**
     * Check if a point is inside a circular zone
     */
    private fun isInsideCircle(
        lat: Double,
        lon: Double,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): Boolean {
        val distance = calculateDistance(lat, lon, centerLat, centerLon)
        return distance <= radiusMeters
    }

    /**
     * Calculate distance between two points using Haversine formula
     */
    private fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }
}