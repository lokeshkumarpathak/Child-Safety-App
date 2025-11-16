package com.example.child_safety_app_version1.utils

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Syncs Firebase authenticated users to Supabase database
 * This allows Supabase to recognize Firebase users for media uploads
 */
object SupabaseUserSyncManager {
    private const val TAG = "SupabaseUserSync"

    // ✅ YOUR SUPABASE CONFIGURATION
    private const val SUPABASE_URL = "https://qcqdpnsetwljcflwlxex.supabase.co"

    // ⚠️ REPLACE THIS WITH YOUR SERVICE ROLE KEY FROM SUPABASE DASHBOARD
    // Go to: Supabase Dashboard → Settings → API → service_role key (secret)
    private const val SUPABASE_SERVICE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFjcWRwbnNldHdsamNmbHdseGV4Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc2MzIxODQyOSwiZXhwIjoyMDc4Nzk0NDI5fQ.MYm70X_aoHLSw_CuL3wfeL-YAaAY61yXeVaXAPv11wI"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sync current Firebase user to Supabase
     * Call this after Firebase login/signup
     */
    suspend fun syncCurrentUserToSupabase(context: Context, role: String = "child"): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val currentUser = FirebaseAuth.getInstance().currentUser

                if (currentUser == null) {
                    Log.e(TAG, "❌ No Firebase user logged in")
                    return@withContext false
                }

                Log.d(TAG, "🔄 Syncing Firebase user to Supabase...")
                Log.d(TAG, "   UID: ${currentUser.uid}")
                Log.d(TAG, "   Email: ${currentUser.email}")
                Log.d(TAG, "   Role: $role")

                // Check if service key is configured
                if (SUPABASE_SERVICE_KEY == "YOUR_SERVICE_ROLE_KEY_HERE") {
                    Log.e(TAG, "❌ SUPABASE_SERVICE_KEY not configured!")
                    Log.e(TAG, "   Please add your service_role key to SupabaseUserSyncManager.kt")
                    return@withContext false
                }

                return@withContext syncUserToSupabase(
                    firebaseUid = currentUser.uid,
                    email = currentUser.email ?: "unknown@example.com",
                    role = role,
                    createdAt = System.currentTimeMillis()
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error syncing user", e)
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    /**
     * Sync specific user to Supabase
     */
    private suspend fun syncUserToSupabase(
        firebaseUid: String,
        email: String,
        role: String,
        createdAt: Long
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // First, check if user already exists
                if (checkUserExists(firebaseUid)) {
                    Log.d(TAG, "✅ User already exists in Supabase")
                    return@withContext true
                }

                // Insert new user
                val url = "$SUPABASE_URL/rest/v1/firebase_users"

                val userJson = JSONObject().apply {
                    put("firebase_uid", firebaseUid)
                    put("email", email)
                    put("role", role)
                    put("created_at", createdAt)
                }

                Log.d(TAG, "   POST: $url")
                Log.d(TAG, "   Body: $userJson")

                val requestBody = userJson.toString()
                    .toRequestBody("application/json".toMediaType())

                // ✅ DEBUG: Check if key is properly set
                Log.d(TAG, "   Service key length: ${SUPABASE_SERVICE_KEY.length}")
                Log.d(TAG, "   Service key starts with: ${SUPABASE_SERVICE_KEY.take(20)}...")

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $SUPABASE_SERVICE_KEY")
                    .addHeader("apikey", SUPABASE_SERVICE_KEY)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()

                Log.d(TAG, "   Headers: ${request.headers}")

                val response = client.newCall(request).execute()

                return@withContext when (response.code) {
                    200, 201 -> {
                        Log.d(TAG, "✅ User synced to Supabase successfully")
                        true
                    }
                    409 -> {
                        // Conflict - user already exists
                        Log.d(TAG, "✅ User already exists (409 conflict)")
                        true
                    }
                    else -> {
                        Log.e(TAG, "❌ Failed to sync user: ${response.code}")
                        val errorBody = response.body?.string()
                        Log.e(TAG, "   Error: $errorBody")
                        false
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception syncing user", e)
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    /**
     * Check if user already exists in Supabase
     */
    private suspend fun checkUserExists(firebaseUid: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$SUPABASE_URL/rest/v1/firebase_users?firebase_uid=eq.$firebaseUid&select=firebase_uid"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $SUPABASE_SERVICE_KEY")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", SUPABASE_SERVICE_KEY)
                    .build()

                val response = client.newCall(request).execute()

                if (response.code == 200) {
                    val body = response.body?.string() ?: "[]"
                    // If body is not empty array, user exists
                    return@withContext body != "[]" && body.isNotEmpty()
                }

                return@withContext false

            } catch (e: Exception) {
                Log.e(TAG, "Error checking user existence", e)
                return@withContext false
            }
        }
    }

    /**
     * Get Firebase user info from Supabase
     */
    suspend fun getUserFromSupabase(firebaseUid: String): SupabaseUser? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$SUPABASE_URL/rest/v1/firebase_users?firebase_uid=eq.$firebaseUid"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $SUPABASE_SERVICE_KEY")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("apikey", SUPABASE_SERVICE_KEY)
                    .build()

                val response = client.newCall(request).execute()

                if (response.code == 200) {
                    val body = response.body?.string() ?: "[]"

                    if (body != "[]" && body.isNotEmpty()) {
                        // Parse JSON array response
                        val jsonArray = org.json.JSONArray(body)
                        if (jsonArray.length() > 0) {
                            val userJson = jsonArray.getJSONObject(0)
                            return@withContext SupabaseUser(
                                firebaseUid = userJson.getString("firebase_uid"),
                                email = userJson.getString("email"),
                                role = userJson.getString("role"),
                                createdAt = userJson.getLong("created_at")
                            )
                        }
                    }
                }

                return@withContext null

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching user", e)
                return@withContext null
            }
        }
    }

    /**
     * Data class for Supabase user
     */
    data class SupabaseUser(
        val firebaseUid: String,
        val email: String,
        val role: String,
        val createdAt: Long
    )
}