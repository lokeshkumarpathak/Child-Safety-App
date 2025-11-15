package com.example.child_safety_app_version1.viewModels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.child_safety_app_version1.data.AppInfo
import com.example.child_safety_app_version1.data.AppMode
import com.example.child_safety_app_version1.utils.FcmNotificationSender
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * ViewModel for Mode Control Screen
 * Handles mode switching (NORMAL, STUDY, BEDTIME) and configurations
 */
class ModeControlViewModel(
    private val childUid: String,
    private val context: Context
) : ViewModel() {
    private val TAG = "ModeControlViewModel"
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Current mode
    private val _currentMode = MutableStateFlow<AppMode>(AppMode.NORMAL)
    val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

    // Mode activation status
    private val _modeStatus = MutableStateFlow<ModeStatus>(ModeStatus.Idle)
    val modeStatus: StateFlow<ModeStatus> = _modeStatus.asStateFlow()

    // Study Mode configuration - ALLOWED APPS (whitelist)
    private val _studyModeAllowedApps = MutableStateFlow<Set<String>>(emptySet())
    val studyModeAllowedApps: StateFlow<Set<String>> = _studyModeAllowedApps.asStateFlow()

    // Normal Mode configuration - BLOCKED APPS (blacklist)
    private val _normalModeBlockedApps = MutableStateFlow<Set<String>>(emptySet())
    val normalModeBlockedApps: StateFlow<Set<String>> = _normalModeBlockedApps.asStateFlow()

    // Bedtime Mode configuration - ALLOWED APPS (whitelist, minimal)
    private val _bedtimeModeAllowedApps = MutableStateFlow<Set<String>>(emptySet())
    val bedtimeModeAllowedApps: StateFlow<Set<String>> = _bedtimeModeAllowedApps.asStateFlow()

    // Available apps for selection (fetched from child device)
    private val _availableApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val availableApps: StateFlow<List<AppInfo>> = _availableApps.asStateFlow()

    // Loading state for fetching apps
    private val _isFetchingApps = MutableStateFlow(false)
    val isFetchingApps: StateFlow<Boolean> = _isFetchingApps.asStateFlow()

    // Pending mode activation (for two-step process)
    private val _pendingMode = MutableStateFlow<AppMode?>(null)
    val pendingMode: StateFlow<AppMode?> = _pendingMode.asStateFlow()

    // Bedtime settings
    private val _bedtimeStartTime = MutableStateFlow("21:00")
    val bedtimeStartTime: StateFlow<String> = _bedtimeStartTime.asStateFlow()

    private val _bedtimeEndTime = MutableStateFlow("07:00")
    val bedtimeEndTime: StateFlow<String> = _bedtimeEndTime.asStateFlow()

    private val _isBedtimeScheduled = MutableStateFlow(false)
    val isBedtimeScheduled: StateFlow<Boolean> = _isBedtimeScheduled.asStateFlow()

    init {
        Log.d(TAG, "🚀 Initializing ModeControlViewModel")
        Log.d(TAG, "   Child UID: ${childUid.take(10)}...")
        loadCurrentMode()
        loadModeConfigurations()
        // ⭐ REMOVED: Don't fetch apps on init - only when Study Mode is selected
    }

    /**
     * Load current mode from Firestore
     */
    private fun loadCurrentMode() {
        Log.d(TAG, "Loading current mode...")

        viewModelScope.launch {
            try {
                val doc = db.collection("users")
                    .document(childUid)
                    .get()
                    .await()

                val modeString = doc.getString("currentMode") ?: "NORMAL"
                val mode = AppMode.fromString(modeString)
                _currentMode.value = mode

                Log.d(TAG, "✅ Current mode loaded: ${mode.displayName}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading current mode", e)
                _currentMode.value = AppMode.NORMAL
            }
        }
    }

    /**
     * Load mode configurations from Firestore
     */
    private fun loadModeConfigurations() {
        Log.d(TAG, "Loading mode configurations...")

        viewModelScope.launch {
            try {
                // ===== NORMAL MODE: Load blocked apps =====
                val normalDoc = db.collection("users")
                    .document(childUid)
                    .collection("modes")
                    .document("normal")
                    .get()
                    .await()

                if (normalDoc.exists()) {
                    val blockedApps = normalDoc.get("blockedApps") as? List<String>
                    if (blockedApps != null) {
                        _normalModeBlockedApps.value = blockedApps.toSet()
                        Log.d(TAG, "✅ Loaded ${blockedApps.size} Normal Mode blocked apps")
                    }
                } else {
                    Log.d(TAG, "✅ Using no blocked apps for Normal Mode (default)")
                    _normalModeBlockedApps.value = emptySet()
                }

                // ===== STUDY MODE: Load allowed apps =====
                val studyDoc = db.collection("users")
                    .document(childUid)
                    .collection("modes")
                    .document("study")
                    .get()
                    .await()

                if (studyDoc.exists()) {
                    val allowedApps = studyDoc.get("allowedApps") as? List<String>
                    if (allowedApps != null) {
                        _studyModeAllowedApps.value = allowedApps.toSet()
                        Log.d(TAG, "✅ Loaded ${allowedApps.size} Study Mode allowed apps")
                    }
                } else {
                    _studyModeAllowedApps.value = emptySet()
                    Log.d(TAG, "✅ No previous Study Mode apps")
                }

                // ===== BEDTIME MODE: Always use system apps only =====
                val bedtimeDoc = db.collection("users")
                    .document(childUid)
                    .collection("modes")
                    .document("bedtime")
                    .get()
                    .await()

                if (bedtimeDoc.exists()) {
                    val startTime = bedtimeDoc.getString("startTime")
                    val endTime = bedtimeDoc.getString("endTime")
                    val isScheduled = bedtimeDoc.getBoolean("isScheduled") ?: false

                    if (startTime != null) _bedtimeStartTime.value = startTime
                    if (endTime != null) _bedtimeEndTime.value = endTime
                    _isBedtimeScheduled.value = isScheduled

                    Log.d(TAG, "✅ Loaded bedtime settings")
                }

                // Bedtime mode always allows only system essentials
                _bedtimeModeAllowedApps.value = setOf(
                    "com.android.phone",
                    "com.android.dialer",
                    "com.android.contacts",
                    "com.example.child_safety_app_version1"
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading mode configurations", e)
            }
        }
    }

    /**
     * Fetch all installed apps from child device
     * ⭐ NOW ONLY CALLED WHEN STUDY MODE IS SELECTED
     */
    private fun fetchAllInstalledApps() {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "📱 FETCHING ALL INSTALLED APPS")
        Log.d(TAG, "════════════════════════════════════════")

        viewModelScope.launch {
            try {
                _isFetchingApps.value = true

                // Generate request ID
                val requestId = UUID.randomUUID().toString()
                val parentUid = auth.currentUser?.uid ?: return@launch

                Log.d(TAG, "STEP 1: Creating All Apps Request Document")
                Log.d(TAG, "   Request ID: $requestId")

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
                Log.d(TAG, "STEP 2: Sending FCM Trigger to Child Device")

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
                } else {
                    Log.w(TAG, "⚠️ FCM message failed to send")
                }

                // Listen for completion
                Log.d(TAG, "STEP 3: Listening for Completion")
                listenToAllAppsRequestStatus(requestId)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in fetchAllInstalledApps", e)
                _isFetchingApps.value = false
            }
        }
    }

    /**
     * Listen to all apps request status
     */
    private fun listenToAllAppsRequestStatus(requestId: String) {
        db.collection("users")
            .document(childUid)
            .collection("allAppsRequest")
            .document(requestId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Error listening to request status", error)
                    _isFetchingApps.value = false
                    return@addSnapshotListener
                }

                val status = snapshot?.getString("status")
                Log.d(TAG, "Request status: $status")

                if (status == "completed") {
                    Log.d(TAG, "✅ Apps collection completed!")
                    loadAllAppsFromFirestore()
                }
            }
    }

    /**
     * Load all apps from Firestore after collection
     */
    private fun loadAllAppsFromFirestore() {
        viewModelScope.launch {
            try {
                val snapshot = db.collection("users")
                    .document(childUid)
                    .collection("allApps")
                    .get()
                    .await()

                val apps = snapshot.documents.mapNotNull { doc ->
                    val packageName = doc.id
                    val appName = doc.getString("appName") ?: packageName
                    AppInfo(packageName, appName)
                }

                _availableApps.value = apps.sortedBy { it.appName }
                _isFetchingApps.value = false

                Log.d(TAG, "✅ Loaded ${apps.size} apps from child device")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading apps", e)
                _isFetchingApps.value = false
            }
        }
    }

    /**
     * Initiate mode activation (shows app selection for Study Mode)
     */
    fun initiateMode(mode: AppMode) {
        Log.d(TAG, "🚀 Initiating mode: ${mode.displayName}")

        when (mode) {
            AppMode.STUDY -> {
                // For Study Mode: Show app selection screen + fetch apps
                _pendingMode.value = mode
                _modeStatus.value = ModeStatus.Idle
                // ⭐ NOW FETCH APPS ONLY WHEN STUDY MODE IS CLICKED
                fetchAllInstalledApps()
            }
            AppMode.BEDTIME -> {
                // For Bedtime: Activate immediately with system apps only
                activateBedtimeMode()
            }
            AppMode.NORMAL -> {
                // For Normal: Activate immediately
                activateMode(mode)
            }
        }
    }

    /**
     * Finalize and activate Study Mode with selected apps
     */
    fun finalizeStudyMode() {
        Log.d(TAG, "✅ Finalizing Study Mode with ${_studyModeAllowedApps.value.size} apps")

        viewModelScope.launch {
            try {
                _modeStatus.value = ModeStatus.Loading

                // ⭐ ENFORCE BLOCKING: Update blockedApps collection
                val allAppPackages = _availableApps.value.map { it.packageName }.toSet()
                val blockedApps = allAppPackages - _studyModeAllowedApps.value

                // Save to blockedApps collection for enforcement
                saveBlockedAppsToFirestore(blockedApps)

                // Save allowed apps to Firestore
                db.collection("users")
                    .document(childUid)
                    .collection("modes")
                    .document("study")
                    .set(
                        hashMapOf(
                            "allowedApps" to _studyModeAllowedApps.value.toList(),
                            "updatedAt" to System.currentTimeMillis(),
                            "updatedBy" to (auth.currentUser?.uid ?: "unknown")
                        ),
                        SetOptions.merge()
                    )
                    .await()

                // Activate the mode
                activateMode(AppMode.STUDY)
                _pendingMode.value = null

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error finalizing Study Mode", e)
                _modeStatus.value = ModeStatus.Error("Failed to activate: ${e.message}")
            }
        }
    }

    /**
     * Save blocked apps to Firestore for enforcement by blocking service
     */
    private suspend fun saveBlockedAppsToFirestore(blockedPackages: Set<String>) {
        try {
            val batch = db.batch()

            // First, clear all existing blocked apps
            val existingDocs = db.collection("users")
                .document(childUid)
                .collection("blockedApps")
                .get()
                .await()

            for (doc in existingDocs.documents) {
                batch.delete(doc.reference)
            }

            // Add new blocked apps
            for (packageName in blockedPackages) {
                val docRef = db.collection("users")
                    .document(childUid)
                    .collection("blockedApps")
                    .document(packageName)

                batch.set(docRef, hashMapOf(
                    "packageName" to packageName,
                    "blocked" to true,
                    "blockedAt" to System.currentTimeMillis(),
                    "mode" to "STUDY"
                ))
            }

            batch.commit().await()
            Log.d(TAG, "✅ Blocked ${blockedPackages.size} apps for Study Mode enforcement")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving blocked apps", e)
            throw e
        }
    }

    /**
     * Activate Bedtime Mode with minimal apps
     */
    private fun activateBedtimeMode() {
        Log.d(TAG, "🌙 Activating Bedtime Mode")

        viewModelScope.launch {
            try {
                _modeStatus.value = ModeStatus.Loading

                // Bedtime mode allows only system essentials
                val bedtimeAllowedApps = listOf(
                    "com.android.phone",
                    "com.android.dialer",
                    "com.android.contacts",
                    "com.example.child_safety_app_version1"
                )

                // ⭐ ENFORCE BLOCKING: Block all other apps
                val allAppsSnapshot = db.collection("users")
                    .document(childUid)
                    .collection("allApps")
                    .get()
                    .await()

                val allAppPackages = allAppsSnapshot.documents.map { it.id }.toSet()
                val blockedApps = allAppPackages - bedtimeAllowedApps.toSet()

                saveBlockedAppsToFirestore(blockedApps)

                // Save to Firestore
                db.collection("users")
                    .document(childUid)
                    .collection("modes")
                    .document("bedtime")
                    .set(
                        hashMapOf(
                            "allowedApps" to bedtimeAllowedApps,
                            "startTime" to _bedtimeStartTime.value,
                            "endTime" to _bedtimeEndTime.value,
                            "isScheduled" to _isBedtimeScheduled.value,
                            "updatedAt" to System.currentTimeMillis(),
                            "updatedBy" to (auth.currentUser?.uid ?: "unknown")
                        ),
                        SetOptions.merge()
                    )
                    .await()

                // Activate the mode
                activateMode(AppMode.BEDTIME)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error activating Bedtime Mode", e)
                _modeStatus.value = ModeStatus.Error("Failed to activate: ${e.message}")
            }
        }
    }

    /**
     * Activate a specific mode (final step)
     */
    private fun activateMode(mode: AppMode) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 ACTIVATING MODE: ${mode.displayName}")
        Log.d(TAG, "========================================")

        viewModelScope.launch {
            try {
                _modeStatus.value = ModeStatus.Loading

                // ⭐ If activating NORMAL mode, clear all blocked apps
                if (mode == AppMode.NORMAL) {
                    clearAllBlockedApps()
                }

                val modeData = hashMapOf(
                    "currentMode" to mode.modeName,
                    "activatedAt" to System.currentTimeMillis(),
                    "activatedBy" to (auth.currentUser?.uid ?: "unknown")
                )

                db.collection("users")
                    .document(childUid)
                    .set(modeData, SetOptions.merge())
                    .await()

                _currentMode.value = mode

                Log.d(TAG, "✅ Mode activated successfully: ${mode.displayName}")
                _modeStatus.value = ModeStatus.Success("${mode.displayName} activated")

                kotlinx.coroutines.delay(2000)
                _modeStatus.value = ModeStatus.Idle

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error activating mode", e)
                _modeStatus.value = ModeStatus.Error("Failed to activate mode: ${e.message}")
            }
        }
    }

    /**
     * Clear all blocked apps (for Normal Mode)
     */
    private suspend fun clearAllBlockedApps() {
        try {
            val existingDocs = db.collection("users")
                .document(childUid)
                .collection("blockedApps")
                .get()
                .await()

            val batch = db.batch()
            for (doc in existingDocs.documents) {
                batch.delete(doc.reference)
            }
            batch.commit().await()

            Log.d(TAG, "✅ Cleared all blocked apps for Normal Mode")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing blocked apps", e)
        }
    }

    /**
     * Cancel pending mode activation
     */
    fun cancelPendingMode() {
        _pendingMode.value = null
        _modeStatus.value = ModeStatus.Idle
    }

    /**
     * Update blocked apps for Normal Mode
     */
    fun updateNormalModeBlockedApps(blockedPackages: Set<String>) {
        Log.d(TAG, "Updating Normal Mode blocked apps...")

        viewModelScope.launch {
            try {
                _modeStatus.value = ModeStatus.Loading

                db.collection("users")
                    .document(childUid)
                    .collection("modes")
                    .document("normal")
                    .set(
                        hashMapOf(
                            "blockedApps" to blockedPackages.toList(),
                            "updatedAt" to System.currentTimeMillis(),
                            "updatedBy" to (auth.currentUser?.uid ?: "unknown")
                        ),
                        SetOptions.merge()
                    )
                    .await()

                _normalModeBlockedApps.value = blockedPackages

                Log.d(TAG, "✅ Normal Mode blocked apps updated")
                _modeStatus.value = ModeStatus.Success("Blocked apps updated")

                kotlinx.coroutines.delay(2000)
                _modeStatus.value = ModeStatus.Idle

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating Normal Mode apps", e)
                _modeStatus.value = ModeStatus.Error("Failed to update: ${e.message}")
            }
        }
    }

    /**
     * Update Bedtime schedule
     */
    fun updateBedtimeSchedule(
        startTime: String,
        endTime: String,
        isScheduled: Boolean
    ) {
        Log.d(TAG, "Updating Bedtime schedule...")

        viewModelScope.launch {
            try {
                _modeStatus.value = ModeStatus.Loading

                db.collection("users")
                    .document(childUid)
                    .collection("modes")
                    .document("bedtime")
                    .set(
                        hashMapOf(
                            "startTime" to startTime,
                            "endTime" to endTime,
                            "isScheduled" to isScheduled,
                            "allowedApps" to _bedtimeModeAllowedApps.value.toList(),
                            "updatedAt" to System.currentTimeMillis(),
                            "updatedBy" to (auth.currentUser?.uid ?: "unknown")
                        ),
                        SetOptions.merge()
                    )
                    .await()

                _bedtimeStartTime.value = startTime
                _bedtimeEndTime.value = endTime
                _isBedtimeScheduled.value = isScheduled

                Log.d(TAG, "✅ Bedtime schedule updated")
                _modeStatus.value = ModeStatus.Success("Bedtime schedule updated")

                kotlinx.coroutines.delay(2000)
                _modeStatus.value = ModeStatus.Idle

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating Bedtime schedule", e)
                _modeStatus.value = ModeStatus.Error("Failed to update: ${e.message}")
            }
        }
    }

    /**
     * Toggle blocked app for Normal Mode
     */
    fun toggleNormalModeBlockedApp(packageName: String) {
        val currentApps = _normalModeBlockedApps.value.toMutableSet()

        if (currentApps.contains(packageName)) {
            currentApps.remove(packageName)
        } else {
            currentApps.add(packageName)
        }

        updateNormalModeBlockedApps(currentApps)
    }

    /**
     * Toggle allowed app for Study Mode
     */
    fun toggleStudyModeApp(packageName: String) {
        val currentApps = _studyModeAllowedApps.value.toMutableSet()

        if (currentApps.contains(packageName)) {
            currentApps.remove(packageName)
        } else {
            currentApps.add(packageName)
        }

        _studyModeAllowedApps.value = currentApps
    }
}

/**
 * Sealed class for mode operation status
 */
sealed class ModeStatus {
    object Idle : ModeStatus()
    object Loading : ModeStatus()
    data class Success(val message: String) : ModeStatus()
    data class Error(val message: String) : ModeStatus()
}