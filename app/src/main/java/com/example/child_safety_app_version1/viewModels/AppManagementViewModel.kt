package com.example.child_safety_app_version1.viewModels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.child_safety_app_version1.data.AppUsageInfo
import com.example.child_safety_app_version1.utils.FcmNotificationSender
import com.example.child_safety_app_version1.utils.UsageStatsHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * State class for UI
 */
sealed class AppManagementState {
    object Idle : AppManagementState()
    object Loading : AppManagementState()
    data class Success(
        val apps: List<AppUsageInfo>,
        val lastUpdated: String
    ) : AppManagementState()
    data class Error(val message: String) : AppManagementState()
}

/**
 * ViewModel for App Management Screen
 * Handles fetching usage data, triggering collection, and managing blocked apps
 */
class AppManagementViewModel(
    private val childUid: String,
    private val context: Context
) : ViewModel() {
    private val TAG = "AppManagementViewModel"
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // UI State
    private val _state = MutableStateFlow<AppManagementState>(AppManagementState.Idle)
    val state: StateFlow<AppManagementState> = _state.asStateFlow()

    // Current request ID being tracked
    private var currentRequestId: String? = null
    private var requestStatusListener: ListenerRegistration? = null

    // Blocked apps list
    private val _blockedApps = MutableStateFlow<Set<String>>(emptySet())
    val blockedApps: StateFlow<Set<String>> = _blockedApps.asStateFlow()

    // Sort and filter
    private val _sortBy = MutableStateFlow<SortOption>(SortOption.MOST_USED)
    val sortBy: StateFlow<SortOption> = _sortBy.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        Log.d(TAG, "🚀 Initializing AppManagementViewModel")
        Log.d(TAG, "   Child UID: ${childUid.take(10)}...")
        loadBlockedApps()
    }

    /**
     * Trigger usage data collection from child device
     */
    fun fetchUsageData() {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "📋 FETCHING USAGE DATA - COMPLETE FLOW")
        Log.d(TAG, "════════════════════════════════════════")

        viewModelScope.launch {
            try {
                _state.value = AppManagementState.Loading

                // Generate request ID
                val requestId = UUID.randomUUID().toString()
                currentRequestId = requestId
                val parentUid = auth.currentUser?.uid ?: return@launch

                Log.d(TAG, "STEP 1: Creating Request Document")
                Log.d(TAG, "   Request ID: $requestId")
                Log.d(TAG, "   Parent UID: ${parentUid.take(10)}...")
                Log.d(TAG, "   Child UID: ${childUid.take(10)}...")

                // Create usage data request document
                val requestData = hashMapOf(
                    "requestedAt" to System.currentTimeMillis(),
                    "requestedBy" to parentUid,
                    "status" to "pending"
                )

                db.collection("users")
                    .document(childUid)
                    .collection("usageDataRequest")
                    .document(requestId)
                    .set(requestData)
                    .await()

                Log.d(TAG, "✅ Request document created in Firestore")

                // ⭐ NEW: Send FCM message to trigger collection immediately
                Log.d(TAG, "")
                Log.d(TAG, "STEP 2: Sending FCM Trigger to Child Device")
                Log.d(TAG, "   Sending USAGE_DATA_REQUEST message...")

                val fcmSent = try {
                    FcmNotificationSender.sendUsageDataRequestToChild(
                        context = context,
                        childUid = childUid,
                        requestId = requestId,
                        parentUid = parentUid
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception sending FCM", e)
                    false
                }

                if (fcmSent) {
                    Log.d(TAG, "✅ FCM message sent successfully")
                    Log.d(TAG, "   Child device will collect data immediately")
                } else {
                    Log.w(TAG, "⚠️ FCM message failed to send")
                    Log.w(TAG, "   Will rely on Firestore listener (slower)")
                }

                // Start listening for status updates
                Log.d(TAG, "")
                Log.d(TAG, "STEP 3: Listening for Completion")
                Log.d(TAG, "   Waiting for child to complete collection...")
                listenToRequestStatus(requestId)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in fetchUsageData", e)
                _state.value = AppManagementState.Error(
                    "Failed to request data: ${e.message}"
                )
            }
        }
    }


    fun fetchAllInstalledApps() {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "📱 FETCHING ALL INSTALLED APPS")
        Log.d(TAG, "════════════════════════════════════════")

        viewModelScope.launch {
            try {
                // Generate request ID
                val requestId = UUID.randomUUID().toString()
                val parentUid = auth.currentUser?.uid ?: return@launch

                Log.d(TAG, "STEP 1: Creating All Apps Request Document")
                Log.d(TAG, "   Request ID: $requestId")
                Log.d(TAG, "   Parent UID: ${parentUid.take(10)}...")
                Log.d(TAG, "   Child UID: ${childUid.take(10)}...")

                // Create request document
                val requestData = hashMapOf(
                    "requestedAt" to System.currentTimeMillis(),
                    "requestedBy" to parentUid,
                    "status" to "pending"
                )

                db.collection("users")
                    .document(childUid)
                    .collection("allAppsRequest")
                    .document(requestId)
                    .set(requestData)
                    .await()

                Log.d(TAG, "✅ Request document created")

                // Send FCM message
                Log.d(TAG, "")
                Log.d(TAG, "STEP 2: Sending FCM Trigger to Child Device")
                Log.d(TAG, "   Sending ALL_APPS_REQUEST message...")

                val fcmSent = try {
                    FcmNotificationSender.sendAllAppsRequestToChild(
                        context = context,
                        childUid = childUid,
                        requestId = requestId,
                        parentUid = parentUid
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception sending FCM", e)
                    false
                }

                if (fcmSent) {
                    Log.d(TAG, "✅ FCM message sent successfully")
                    Log.d(TAG, "   Child device will collect apps immediately")
                } else {
                    Log.w(TAG, "⚠️ FCM message failed to send")
                    Log.w(TAG, "   Will rely on Firestore listener (slower)")
                }

                // Listen for completion
                Log.d(TAG, "")
                Log.d(TAG, "STEP 3: Listening for Completion")
                Log.d(TAG, "   Waiting for child to complete collection...")
                listenToAllAppsRequestStatus(requestId)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in fetchAllInstalledApps", e)
            }
        }
    }

    /**
     * Listen to all apps request status
     */
    private fun listenToAllAppsRequestStatus(requestId: String) {
        Log.d(TAG, "🎧 Starting status listener for all apps request: $requestId")

        requestStatusListener?.remove()

        requestStatusListener = db.collection("users")
            .document(childUid)
            .collection("allAppsRequest")
            .document(requestId)
            .addSnapshotListener { documentSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Status listener error", error)
                    return@addSnapshotListener
                }

                if (documentSnapshot == null || !documentSnapshot.exists()) {
                    Log.w(TAG, "⚠️ Request document not found")
                    return@addSnapshotListener
                }

                val status = documentSnapshot.getString("status") ?: "pending"
                Log.d(TAG, "📡 All Apps Status update: $status")

                when (status) {
                    "pending" -> {
                        Log.d(TAG, "   ⏳ Still waiting for child device...")
                    }
                    "completed" -> {
                        Log.d(TAG, "   ✅ All apps collection completed!")
                        Log.d(TAG, "")
                        Log.d(TAG, "STEP 4: Apps data available in Firestore")
                        Log.d(TAG, "   UI will refresh automatically via snapshot listener")
                        requestStatusListener?.remove()
                        requestStatusListener = null
                    }
                    "failed" -> {
                        val errorMsg = documentSnapshot.getString("errorMessage") ?: "Unknown error"
                        Log.e(TAG, "   ❌ Collection failed: $errorMsg")
                        requestStatusListener?.remove()
                        requestStatusListener = null
                    }
                }
            }
    }

    /**
     * Listen to request status changes
     */
    private fun listenToRequestStatus(requestId: String) {
        Log.d(TAG, "🎧 Starting status listener for request: $requestId")

        requestStatusListener?.remove()

        requestStatusListener = db.collection("users")
            .document(childUid)
            .collection("usageDataRequest")
            .document(requestId)
            .addSnapshotListener { documentSnapshot, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Status listener error", error)
                    _state.value = AppManagementState.Error("Connection error: ${error.message}")
                    return@addSnapshotListener
                }

                if (documentSnapshot == null || !documentSnapshot.exists()) {
                    Log.w(TAG, "⚠️ Request document not found")
                    return@addSnapshotListener
                }

                val status = documentSnapshot.getString("status") ?: "pending"

                Log.d(TAG, "📡 Status update: $status")

                when (status) {
                    "pending" -> {
                        Log.d(TAG, "   ⏳ Still waiting for child device...")
                    }

                    "completed" -> {
                        Log.d(TAG, "   ✅ Collection completed!")
                        Log.d(TAG, "")
                        Log.d(TAG, "STEP 4: Fetching Data from Firestore")
                        viewModelScope.launch {
                            fetchAppUsageData()
                        }
                        requestStatusListener?.remove()
                        requestStatusListener = null
                    }

                    "failed" -> {
                        val errorMsg = documentSnapshot.getString("errorMessage") ?: "Unknown error"
                        Log.e(TAG, "   ❌ Collection failed: $errorMsg")
                        _state.value = AppManagementState.Error(
                            "Child device failed to collect data: $errorMsg"
                        )
                        requestStatusListener?.remove()
                        requestStatusListener = null
                    }
                }
            }
    }

    /**
     * Fetch app usage data from Firestore
     */
    private suspend fun fetchAppUsageData() {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "📊 FETCHING APP USAGE DATA")
        Log.d(TAG, "════════════════════════════════════════")

        try {
            val todayDate = UsageStatsHelper.getTodayDateString()
            Log.d(TAG, "   Date: $todayDate")
            Log.d(TAG, "   Path: /users/$childUid/appUsage/$todayDate/apps/")

            val snapshot = db.collection("users")
                .document(childUid)
                .collection("appUsage")
                .document(todayDate)
                .collection("apps")
                .get()
                .await()

            if (snapshot.isEmpty) {
                Log.w(TAG, "⚠️ No app usage data found")
                _state.value = AppManagementState.Success(
                    apps = emptyList(),
                    lastUpdated = getCurrentTimeString()
                )
                return
            }

            Log.d(TAG, "   Found ${snapshot.documents.size} apps")

            // Convert Firestore documents to AppUsageInfo objects
            val appsList = snapshot.documents.mapNotNull { document ->
                try {
                    AppUsageInfo(
                        packageName = document.getString("packageName") ?: return@mapNotNull null,
                        appName = document.getString("appName") ?: "Unknown",
                        totalTimeMs = document.getLong("totalTimeMs") ?: 0L,
                        lastUsed = document.getLong("lastUsed") ?: 0L,
                        icon = null
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing app data: ${e.message}")
                    null
                }
            }

            Log.d(TAG, "   Successfully parsed ${appsList.size} apps")
            appsList.take(5).forEach { app ->
                val duration = UsageStatsHelper.formatDuration(app.totalTimeMs)
                Log.d(TAG, "      - ${app.appName}: $duration")
            }

            Log.d(TAG, "✅ Data fetched successfully")
            Log.d(TAG, "════════════════════════════════════════")
            _state.value = AppManagementState.Success(
                apps = appsList,
                lastUpdated = getCurrentTimeString()
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching usage data", e)
            _state.value = AppManagementState.Error(
                "Failed to fetch data: ${e.message}"
            )
        }
    }

    /**
     * Refresh the app usage data
     */
    fun refreshData() {
        Log.d(TAG, "🔄 Refreshing data...")
        fetchUsageData()
    }

    /**
     * Toggle app blocked status
     */
    fun toggleAppBlocked(packageName: String, appName: String) {
        Log.d(TAG, "🔄 Toggling blocked status for: $appName ($packageName)")

        viewModelScope.launch {
            try {
                val isCurrentlyBlocked = _blockedApps.value.contains(packageName)
                val newBlockedStatus = !isCurrentlyBlocked

                val blockedAppData = hashMapOf(
                    "packageName" to packageName,
                    "appName" to appName,
                    "blocked" to newBlockedStatus,
                    "blockedAt" to System.currentTimeMillis(),
                    "blockedBy" to (auth.currentUser?.uid ?: "unknown")
                )

                db.collection("users")
                    .document(childUid)
                    .collection("blockedApps")
                    .document(packageName)
                    .set(blockedAppData)
                    .await()

                // Update local state
                val updatedBlockedApps = _blockedApps.value.toMutableSet()
                if (newBlockedStatus) {
                    updatedBlockedApps.add(packageName)
                    Log.d(TAG, "✅ App blocked: $appName")
                } else {
                    updatedBlockedApps.remove(packageName)
                    Log.d(TAG, "✅ App unblocked: $appName")
                }
                _blockedApps.value = updatedBlockedApps

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error toggling blocked status", e)
            }
        }
    }

    /**
     * Load blocked apps list from Firestore
     */
    private fun loadBlockedApps() {
        Log.d(TAG, "📥 Loading blocked apps list...")

        viewModelScope.launch {
            try {
                val snapshot = db.collection("users")
                    .document(childUid)
                    .collection("blockedApps")
                    .get()
                    .await()

                val blockedPackages = snapshot.documents
                    .filter { it.getBoolean("blocked") == true }
                    .mapNotNull { it.getString("packageName") }
                    .toSet()

                _blockedApps.value = blockedPackages
                Log.d(TAG, "✅ Loaded ${blockedPackages.size} blocked apps")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading blocked apps", e)
            }
        }
    }

    /**
     * Update sort option
     */
    fun setSortBy(option: SortOption) {
        _sortBy.value = option
        Log.d(TAG, "🔄 Sort changed to: ${option.displayName}")
    }

    /**
     * Update search query
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        Log.d(TAG, "🔍 Search query: $query")
    }

    /**
     * Get sorted and filtered apps
     */
    fun getSortedAndFilteredApps(apps: List<AppUsageInfo>): List<AppUsageInfo> {
        var filtered = apps

        // Apply search filter
        if (_searchQuery.value.isNotEmpty()) {
            filtered = filtered.filter { app ->
                app.appName.contains(_searchQuery.value, ignoreCase = true) ||
                        app.packageName.contains(_searchQuery.value, ignoreCase = true)
            }
        }

        // Apply sorting
        return when (_sortBy.value) {
            SortOption.MOST_USED -> filtered.sortedByDescending { it.totalTimeMs }
            SortOption.LEAST_USED -> filtered.sortedBy { it.totalTimeMs }
            SortOption.ALPHABETICAL -> filtered.sortedBy { it.appName }
            SortOption.RECENTLY_USED -> filtered.sortedByDescending { it.lastUsed }
        }
    }

    /**
     * Get current time as formatted string
     */
    private fun getCurrentTimeString(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    override fun onCleared() {
        super.onCleared()
        requestStatusListener?.remove()
        Log.d(TAG, "🛑 ViewModel cleared")
    }

    fun resetToIdle() {
        _state.value = AppManagementState.Idle
    }
}

/**
 * Enum for sorting options
 */
enum class SortOption(val displayName: String) {
    MOST_USED("Most Used"),
    LEAST_USED("Least Used"),
    ALPHABETICAL("A-Z"),
    RECENTLY_USED("Recently Used")
}