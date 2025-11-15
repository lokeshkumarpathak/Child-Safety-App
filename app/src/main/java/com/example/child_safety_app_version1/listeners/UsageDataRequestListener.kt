package com.example.child_safety_app_version1.listeners

import android.content.Context
import android.util.Log
import com.example.child_safety_app_version1.managers.UsageDataCollector
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Listens to Firestore for usage data requests from parent
 * Triggers data collection when new request is detected
 */
class UsageDataRequestListener(private val context: Context) {
    private val TAG = "UsageDataRequestListener"
    private var listenerRegistration: ListenerRegistration? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start listening for usage data requests
     */
    fun startListening() {
        val childUid = FirebaseAuth.getInstance().currentUser?.uid
        if (childUid == null) {
            Log.e(TAG, "❌ Cannot start listener: User not logged in")
            return
        }

        Log.d(TAG, "🎧 Starting usage data request listener...")
        Log.d(TAG, "   Child UID: ${childUid.take(10)}...")

        val db = FirebaseFirestore.getInstance()

        // Listen to: /users/{childUid}/usageDataRequest/
        listenerRegistration = db.collection("users")
            .document(childUid)
            .collection("usageDataRequest")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Listener error", error)
                    return@addSnapshotListener
                }

                if (snapshots == null || snapshots.isEmpty) {
                    Log.d(TAG, "📭 No pending requests")
                    return@addSnapshotListener
                }

                Log.d(TAG, "========================================")
                Log.d(TAG, "📬 NEW USAGE DATA REQUEST DETECTED!")
                Log.d(TAG, "========================================")

                // Process each pending request
                for (document in snapshots.documents) {
                    val requestId = document.id
                    val requestedAt = document.getLong("requestedAt") ?: 0L
                    val requestedBy = document.getString("requestedBy") ?: "unknown"

                    Log.d(TAG, "📋 Request Details:")
                    Log.d(TAG, "   Request ID: $requestId")
                    Log.d(TAG, "   Requested by: ${requestedBy.take(10)}...")
                    Log.d(TAG, "   Requested at: $requestedAt")

                    // Trigger data collection in background
                    scope.launch {
                        Log.d(TAG, "🚀 Starting data collection...")
                        val success = UsageDataCollector.collectAndUpload(
                            context = context,
                            childUid = childUid,
                            requestId = requestId
                        )

                        if (success) {
                            Log.d(TAG, "✅ Data collection completed successfully")
                            UsageDataCollector.saveLastCollectionTime(context)
                        } else {
                            Log.e(TAG, "❌ Data collection failed")
                        }
                    }
                }
            }

        Log.d(TAG, "✅ Listener started successfully")
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        listenerRegistration?.remove()
        listenerRegistration = null
        Log.d(TAG, "🛑 Listener stopped")
    }

    /**
     * Check if listener is active
     */
    fun isListening(): Boolean {
        return listenerRegistration != null
    }
}