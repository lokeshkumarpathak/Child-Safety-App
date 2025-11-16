package com.example.child_safety_app_version1.utils.sms

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caches parent phone numbers locally for offline SMS sending
 */
object ParentPhoneCache {

    private const val TAG = "ParentPhoneCache"
    private const val DATASTORE_NAME = "parent_phone_cache"

    // DataStore extension
    private val Context.parentPhoneDataStore: DataStore<Preferences> by preferencesDataStore(
        name = DATASTORE_NAME
    )

    // Keys
    private val PHONE_DATA_KEY = stringPreferencesKey("phone_data")
    private val LAST_UPDATED_KEY = stringPreferencesKey("last_updated")

    /**
     * Data class for cached parent info
     */
    data class CachedParentInfo(
        val phoneNumber: String,
        val childName: String,
        val parentEmail: String
    )

    /**
     * Fetch and cache parent phone numbers from Firestore
     * Call this when internet is available
     */
    suspend fun updateCache(context: Context, childUid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "UPDATING PARENT PHONE CACHE")
            Log.d(TAG, "═══════════════════════════════════════════════════")
            Log.d(TAG, "Child UID: ${childUid.take(10)}...")

            val db = FirebaseFirestore.getInstance()

            // Get all parents
            val parentsSnapshot = db.collection("users")
                .document(childUid)
                .collection("parents")
                .get()
                .await()

            Log.d(TAG, "Found ${parentsSnapshot.size()} parent(s)")

            if (parentsSnapshot.isEmpty) {
                Log.w(TAG, "No parents found")
                return@withContext false
            }

            val cachedData = JSONArray()

            for (parentDoc in parentsSnapshot.documents) {
                val parentId = parentDoc.getString("parentId") ?: continue
                val parentEmail = parentDoc.getString("parentEmail") ?: "unknown"

                Log.d(TAG, "Processing parent: ${parentId.take(10)}... ($parentEmail)")

                // Get child name
                val childName = getChildNameFromParent(parentId, childUid)

                // Get parent's phone number
                val parentSnapshot = db.collection("users")
                    .document(parentId)
                    .get()
                    .await()

                val phoneNumber = parentSnapshot.getString("phone")

                if (phoneNumber != null && phoneNumber.isNotBlank()) {
                    Log.d(TAG, "  ✅ Phone: ${phoneNumber.take(5)}...${phoneNumber.takeLast(4)}")

                    // Add to cache
                    val parentInfo = JSONObject().apply {
                        put("phone", phoneNumber)
                        put("childName", childName)
                        put("parentEmail", parentEmail)
                    }
                    cachedData.put(parentInfo)
                } else {
                    Log.w(TAG, "  ⚠️ No phone number")
                }
            }

            if (cachedData.length() == 0) {
                Log.e(TAG, "No phone numbers to cache")
                return@withContext false
            }

            // Save to DataStore
            context.parentPhoneDataStore.edit { preferences ->
                preferences[PHONE_DATA_KEY] = cachedData.toString()
                preferences[LAST_UPDATED_KEY] = System.currentTimeMillis().toString()
            }

            Log.d(TAG, "✅ Cached ${cachedData.length()} parent phone number(s)")
            Log.d(TAG, "═══════════════════════════════════════════════════")

            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating cache", e)
            e.printStackTrace()
            return@withContext false
        }
    }

    /**
     * Get cached parent phone numbers (works offline)
     */
    suspend fun getCachedPhoneNumbers(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📦 Loading cached phone numbers...")

            val preferences = context.parentPhoneDataStore.data.first()
            val phoneDataJson = preferences[PHONE_DATA_KEY]

            if (phoneDataJson == null) {
                Log.w(TAG, "⚠️ No cached data found")
                return@withContext emptyMap()
            }

            val lastUpdated = preferences[LAST_UPDATED_KEY]?.toLongOrNull() ?: 0L
            val ageMinutes = (System.currentTimeMillis() - lastUpdated) / 60000

            Log.d(TAG, "📦 Cache age: $ageMinutes minutes")

            // Parse JSON
            val phoneMap = mutableMapOf<String, String>()
            val jsonArray = JSONArray(phoneDataJson)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val phone = obj.getString("phone")
                val childName = obj.getString("childName")
                phoneMap[phone] = childName
            }

            Log.d(TAG, "✅ Loaded ${phoneMap.size} cached phone number(s)")
            phoneMap.forEach { (phone, name) ->
                Log.d(TAG, "  ${phone.take(5)}...${phone.takeLast(4)} → $name")
            }

            return@withContext phoneMap

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading cache", e)
            e.printStackTrace()
            return@withContext emptyMap()
        }
    }

    /**
     * Get child's name from parent's children subcollection
     */
    private suspend fun getChildNameFromParent(parentId: String, childUid: String): String {
        return try {
            val db = FirebaseFirestore.getInstance()

            val childrenSnapshot = db.collection("users")
                .document(parentId)
                .collection("children")
                .whereEqualTo("childId", childUid)
                .get()
                .await()

            val childDoc = childrenSnapshot.documents.firstOrNull()
            childDoc?.getString("childName") ?: "Your Child"

        } catch (e: Exception) {
            Log.e(TAG, "Error getting child name", e)
            "Your Child"
        }
    }

    /**
     * Check if cache exists and is recent (less than 24 hours old)
     */
    suspend fun isCacheValid(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val preferences = context.parentPhoneDataStore.data.first()
            val phoneData = preferences[PHONE_DATA_KEY]
            val lastUpdated = preferences[LAST_UPDATED_KEY]?.toLongOrNull() ?: 0L

            if (phoneData == null) return@withContext false

            val ageHours = (System.currentTimeMillis() - lastUpdated) / (1000 * 60 * 60)
            return@withContext ageHours < 24

        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear cache (for testing or logout)
     */
    suspend fun clearCache(context: Context) {
        context.parentPhoneDataStore.edit { preferences ->
            preferences.clear()
        }
        Log.d(TAG, "🗑️ Cache cleared")
    }
}