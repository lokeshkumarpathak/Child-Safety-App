package com.example.child_safety_app_version1.utils

import android.content.Context
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object OAuth2TokenManager {
    private const val SERVICE_ACCOUNT_FILE = "child-safety-c7fea-firebase-adminsdk-fbsvc-4eaccaa54c.json"
    private const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

    // Cache for the access token
    private var cachedToken: String? = null
    private var tokenExpiryTime: Long = 0

    /**
     * Gets a valid OAuth2 access token for FCM API v1
     * Caches the token and refreshes when expired
     */
    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            // Check if cached token is still valid (with 5 minute buffer)
            val currentTime = System.currentTimeMillis()
            if (cachedToken != null && currentTime < tokenExpiryTime - (5 * 60 * 1000)) {
                return@withContext cachedToken
            }

            // Load service account JSON from assets
            val inputStream: InputStream = context.assets.open(SERVICE_ACCOUNT_FILE)

            // Create credentials from service account
            val googleCredentials = GoogleCredentials
                .fromStream(inputStream)
                .createScoped(listOf(FCM_SCOPE))

            // Refresh to get access token
            googleCredentials.refreshIfExpired()

            // Get the access token
            val accessToken = googleCredentials.accessToken.tokenValue

            // Cache the token (tokens typically expire in 1 hour)
            cachedToken = accessToken
            tokenExpiryTime = currentTime + (55 * 60 * 1000) // Cache for 55 minutes

            inputStream.close()

            return@withContext accessToken

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    /**
     * Clears the cached token (useful when forcing a refresh)
     */
    fun clearCache() {
        cachedToken = null
        tokenExpiryTime = 0
    }

    /**
     * Checks if the cached token is still valid
     */
    fun isTokenValid(): Boolean {
        val currentTime = System.currentTimeMillis()
        return cachedToken != null && currentTime < tokenExpiryTime
    }
}