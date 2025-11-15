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
    val type: String,
    val children: List<String> = emptyList() // List of child IDs this safe zone applies to
)

object SafeZoneChecker {
    private const val TAG = "SafeZoneChecker"
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Check if a child has any applicable safe zones defined
     * Returns true if at least one safe zone applies to this child
     */
    suspend fun hasApplicableSafeZones(childUid: String): Boolean {
        return try {
            val safeZones = getAllSafeZonesForChild(childUid)
            val hasZones = safeZones.isNotEmpty()

            Log.d(TAG, "Child $childUid has ${safeZones.size} applicable safe zone(s)")
            hasZones
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if child has safe zones", e)
            false
        }
    }

    /**
     * Check if a location is inside any of the child's applicable safe zones
     */
    suspend fun isLocationInAnySafeZone(
        childUid: String,
        latitude: Double,
        longitude: Double
    ): Boolean {
        return try {
            val safeZones = getAllSafeZonesForChild(childUid)

            if (safeZones.isEmpty()) {
                Log.w(TAG, "No applicable safe zones found for child $childUid")
                return true // If no safe zones defined, consider child safe (avoid false alarms)
            }

            Log.d(TAG, "Checking location against ${safeZones.size} applicable safe zone(s)")

            for (zone in safeZones) {
                val isInside = if (zone.boundingBox != null && zone.boundingBox.size >= 4) {
                    isInsideBoundingBox(latitude, longitude, zone.boundingBox)
                } else {
                    isInsideCircle(latitude, longitude, zone.centerLat, zone.centerLon, zone.radius)
                }

                if (isInside) {
                    Log.d(TAG, "✓ Location IS inside safe zone: ${zone.name}")
                    return true
                }
            }

            Log.d(TAG, "✗ Location is NOT inside any applicable safe zone")
            false

        } catch (e: Exception) {
            Log.e(TAG, "Error checking safe zones", e)
            true // On error, assume safe to avoid false alarms
        }
    }

    /**
     * Get all safe zones for a child by querying all their parents' safe zones
     * and filtering for zones that include this child in their 'children' array
     */
    private suspend fun getAllSafeZonesForChild(childUid: String): List<SafeZone> {
        val db = FirebaseFirestore.getInstance()
        val allSafeZones = mutableListOf<SafeZone>()

        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "Getting safe zones for child: $childUid")
            Log.d(TAG, "========================================")

            // Get all parents from child's parents subcollection
            val parentsSnapshot = db.collection("users")
                .document(childUid)
                .collection("parents")
                .get()
                .await()

            Log.d(TAG, "📋 Query: /users/$childUid/parents")
            Log.d(TAG, "📊 Found ${parentsSnapshot.size()} parent document(s)")

            if (parentsSnapshot.isEmpty) {
                Log.w(TAG, "⚠️ WARNING: No parents found in child's 'parents' subcollection!")
                Log.w(TAG, "⚠️ Check if parent-child relationship is properly set up in Firestore")
            }

            // For each parent, get their safe zones that apply to this child
            for (parentDoc in parentsSnapshot.documents) {
                val parentId = parentDoc.getString("parentId")
                Log.d(TAG, "----------------------------------------")
                Log.d(TAG, "Processing parent document: ${parentDoc.id}")
                Log.d(TAG, "Parent ID field value: $parentId")
                Log.d(TAG, "All fields in parent doc: ${parentDoc.data}")

                if (parentId == null) {
                    Log.e(TAG, "❌ Parent document ${parentDoc.id} has NULL 'parentId' field!")
                    continue
                }

                try {
                    val parentSafeZones = getSafeZonesForParent(parentId, childUid)
                    allSafeZones.addAll(parentSafeZones)
                    Log.d(TAG, "✓ Parent $parentId has ${parentSafeZones.size} safe zone(s) applicable to this child")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error getting safe zones for parent $parentId", e)
                    e.printStackTrace()
                }
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "FINAL RESULT: ${allSafeZones.size} total applicable safe zones")
            Log.d(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL ERROR in getAllSafeZonesForChild", e)
            e.printStackTrace()
        }

        return allSafeZones
    }

    /**
     * Get safe zones for a specific parent that apply to the given child
     * Only returns zones where the child's ID is in the 'children' array
     */
    private suspend fun getSafeZonesForParent(parentUid: String, childUid: String): List<SafeZone> {
        val db = FirebaseFirestore.getInstance()
        val safeZones = mutableListOf<SafeZone>()

        try {
            Log.d(TAG, "  📋 Query: /users/$parentUid/safeZones")

            val zonesSnapshot = db.collection("users")
                .document(parentUid)
                .collection("safeZones")
                .get()
                .await()

            Log.d(TAG, "  📊 Found ${zonesSnapshot.size()} total safe zone document(s) for parent")

            if (zonesSnapshot.isEmpty) {
                Log.w(TAG, "  ⚠️ WARNING: Parent has NO safe zones in /users/$parentUid/safeZones")
            }

            for (zoneDoc in zonesSnapshot.documents) {
                try {
                    Log.d(TAG, "  ──────────────────────────────────────")
                    Log.d(TAG, "  Processing safe zone: ${zoneDoc.id}")
                    Log.d(TAG, "  Zone name: ${zoneDoc.getString("name")}")
                    Log.d(TAG, "  All fields: ${zoneDoc.data}")

                    // Get the children array from the safe zone
                    val childrenList = (zoneDoc.get("children") as? List<*>)?.mapNotNull {
                        it as? String
                    } ?: emptyList()

                    Log.d(TAG, "  Children in this zone: $childrenList")
                    Log.d(TAG, "  Looking for child: $childUid")
                    Log.d(TAG, "  Contains child? ${childrenList.contains(childUid)}")

                    // Only include this safe zone if it applies to the current child
                    if (childrenList.contains(childUid)) {
                        val zone = SafeZone(
                            id = zoneDoc.getString("id") ?: zoneDoc.id,
                            name = zoneDoc.getString("name") ?: "Unnamed Zone",
                            centerLat = (zoneDoc.get("centerLat") as? Number)?.toDouble() ?: 0.0,
                            centerLon = (zoneDoc.get("centerLon") as? Number)?.toDouble() ?: 0.0,
                            boundingBox = (zoneDoc.get("boundingBox") as? List<*>)?.mapNotNull {
                                (it as? Number)?.toDouble()
                            },
                            radius = (zoneDoc.get("radius") as? Number)?.toDouble() ?: 100.0,
                            type = zoneDoc.getString("type") ?: "custom",
                            children = childrenList
                        )
                        safeZones.add(zone)
                        Log.d(TAG, "  ✅ ADDED: Safe zone '${zone.name}' applies to child")
                    } else {
                        Log.d(TAG, "  ❌ SKIPPED: Zone does NOT apply to this child")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "  ❌ Error parsing safe zone: ${zoneDoc.id}", e)
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ CRITICAL ERROR getting safe zones for parent $parentUid", e)
            e.printStackTrace()
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