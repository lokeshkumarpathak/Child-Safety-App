package com.example.child_safety_app_version1.utils

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object FcmTokenManager {

    /**
     * Saves the current FCM token to Firestore
     */
    suspend fun saveFcmToken(uid: String): Result<Unit> {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            val firestore = Firebase.firestore

            firestore.collection("users").document(uid)
                .collection("fcmTokens").document(token)
                .set(mapOf(
                    "token" to token,
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "deviceInfo" to android.os.Build.MODEL
                ))
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            println("FCM Token save error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Removes the current FCM token from Firestore
     */
    suspend fun removeFcmToken(uid: String): Result<Unit> {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            val firestore = Firebase.firestore

            firestore.collection("users").document(uid)
                .collection("fcmTokens").document(token)
                .delete()
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            println("FCM Token removal error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Complete logout process: removes FCM token, signs out, and clears login state
     */
    suspend fun performLogout(context: Context, onComplete: () -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val uid = auth.currentUser?.uid

        // Remove FCM token if user is logged in
        if (uid != null) {
            removeFcmToken(uid)
        }

        // Sign out from Firebase
        auth.signOut()

        // Clear local login state
        clearLoginState(context)

        // Callback to navigate
        onComplete()
    }
}