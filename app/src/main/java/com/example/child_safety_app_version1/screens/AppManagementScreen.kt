package com.example.child_safety_app_version1.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.child_safety_app_version1.data.AppUsageInfo
import com.example.child_safety_app_version1.components.AppListItem
import com.example.child_safety_app_version1.components.TopAppsInsightsCard
import com.example.child_safety_app_version1.components.WeeklyInsightsCard
import com.example.child_safety_app_version1.data.WeeklyInsights
import com.example.child_safety_app_version1.managers.WeeklyInsightsCollector
import com.example.child_safety_app_version1.utils.AIInsightsGenerator
import com.example.child_safety_app_version1.utils.UsageStatsHelper
import com.example.child_safety_app_version1.viewModels.AppManagementState
import com.example.child_safety_app_version1.viewModels.AppManagementViewModel
import com.example.child_safety_app_version1.viewModels.SortOption
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "AppManagementScreen"

data class Child1Info(
    val childId: String,
    val childName: String,
    val childEmail: String
)

data class BlockedAppInfo(
    val appName: String,
    val packageName: String,
    val blockedAt: Long,
    val blockedBy: String
)

@Composable
fun AppManagementScreen() {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val parentUid = auth.currentUser?.uid

    // State for children list
    val childrenList = remember { mutableStateOf<List<Child1Info>>(emptyList()) }
    val selectedChild = remember { mutableStateOf<Child1Info?>(null) }
    val isLoadingChildren = remember { mutableStateOf(true) }
    val errorMessage = remember { mutableStateOf<String?>(null) }
    val expandedDropdown = remember { mutableStateOf(false) }
    // State for all installed apps

    // Fetch children on first load
    LaunchedEffect(parentUid) {
        if (parentUid != null) {
            try {
                Log.d(TAG, "=== Starting to fetch children ===")
                Log.d(TAG, "Parent UID: $parentUid")

                val db = FirebaseFirestore.getInstance()
                val childrenRef = db.collection("users")
                    .document(parentUid)
                    .collection("children")

                Log.d(TAG, "Firestore path: users/$parentUid/children")

                val snapshot = childrenRef.get().await()

                Log.d(TAG, "Query completed. Document count: ${snapshot.documents.size}")
                Log.d(TAG, "Is empty: ${snapshot.isEmpty}")

                if (snapshot.isEmpty) {
                    Log.w(TAG, "No children documents found")
                    errorMessage.value = "No children linked to this account"
                }

                val children = snapshot.documents.mapNotNull { doc ->
                    Log.d(TAG, "--- Processing document ---")
                    Log.d(TAG, "Document ID: ${doc.id}")
                    Log.d(TAG, "Document exists: ${doc.exists()}")
                    Log.d(TAG, "Document data: ${doc.data}")

                    val childId = doc.getString("childId")
                    val childName = doc.getString("childName")
                    val childEmail = doc.getString("childEmail")

                    Log.d(TAG, "Extracted - childId: $childId")
                    Log.d(TAG, "Extracted - childName: $childName")
                    Log.d(TAG, "Extracted - childEmail: $childEmail")

                    if (childId != null && childName != null && childEmail != null) {
                        Log.d(TAG, "✅ Valid child info created")
                        Child1Info(childId, childName, childEmail)
                    } else {
                        Log.e(TAG, "❌ Missing required fields in document ${doc.id}")
                        Log.e(TAG, "Available fields: ${doc.data?.keys}")
                        null
                    }
                }

                Log.d(TAG, "=== Fetch complete ===")
                Log.d(TAG, "Total valid children parsed: ${children.size}")

                childrenList.value = children

                if (children.isNotEmpty()) {
                    selectedChild.value = children[0]
                    Log.d(TAG, "Selected first child: ${children[0].childName}")
                    errorMessage.value = null
                } else {
                    Log.w(TAG, "No valid children after parsing")
                    if (snapshot.documents.isNotEmpty()) {
                        errorMessage.value = "Children data is incomplete. Please check Firestore."
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error fetching children", e)
                Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Error message: ${e.message}")
                e.printStackTrace()
                errorMessage.value = "Error: ${e.message}"
            } finally {
                isLoadingChildren.value = false
                Log.d(TAG, "Loading complete")
            }
        } else {
            Log.e(TAG, "❌ Parent UID is null - user not authenticated")
            errorMessage.value = "User not authenticated"
            isLoadingChildren.value = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        // Header
        AppManagementHeader()

        Spacer(modifier = Modifier.height(16.dp))

        // Loading state
        if (isLoadingChildren.value) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        // Error state
        else if (errorMessage.value != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = Color(0xFFD32F2F),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = errorMessage.value ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD32F2F)
                    )
                }
            }
        }
        // Child Selector Dropdown
        else if (childrenList.value.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Select Child Device",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF757575)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expandedDropdown.value = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = selectedChild.value?.childName ?: "Select Child",
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start
                            )
                        }

                        DropdownMenu(
                            expanded = expandedDropdown.value,
                            onDismissRequest = { expandedDropdown.value = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            childrenList.value.forEach { child ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = child.childName,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Text(
                                                text = child.childEmail,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF9E9E9E)
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedChild.value = child
                                        expandedDropdown.value = false
                                        Log.d(TAG, "Child selected: ${child.childName}")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content - Show only if a child is selected
        if (selectedChild.value != null) {
            SelectedChildContent(
                childUid = selectedChild.value!!.childId,
                childName = selectedChild.value!!.childName
            )
        } else if (!isLoadingChildren.value && errorMessage.value == null) {
            // No children message
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No children linked yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF757575),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// Replace the SelectedChildContent composable with this updated version:

// Add this to your AppManagementScreen.kt SelectedChildContent composable
// Replace the existing SelectedChildContent function with this enhanced version

@Composable
private fun SelectedChildContent(
    childUid: String,
    childName: String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Existing ViewModel
    val viewModel = remember(childUid) {
        AppManagementViewModel(
            childUid = childUid,
            context = context
        )
    }

    val state by viewModel.state.collectAsState()
    val blockedApps by viewModel.blockedApps.collectAsState()

    // Existing state variables
    val blockedAppsList = remember { mutableStateOf<List<BlockedAppInfo>>(emptyList()) }
    val isLoadingBlockedApps = remember { mutableStateOf(true) }
    val allInstalledApps = remember { mutableStateOf<List<AppUsageInfo>>(emptyList()) }
    val isLoadingAllApps = remember { mutableStateOf(false) }
    val hasLoadedAllApps = remember { mutableStateOf(false) }
    val allAppsRequestListener = remember { mutableStateOf<com.google.firebase.firestore.ListenerRegistration?>(null) }

    // 🆕 NEW: Weekly Insights State
    var weeklyInsights by remember { mutableStateOf<WeeklyInsights?>(null) }
    var isLoadingInsights by remember { mutableStateOf(false) }
    var hasInsights by remember { mutableStateOf(false) }
    var insightsError by remember { mutableStateOf<String?>(null) }

    // 🆕 NEW: Load existing insights on launch
    LaunchedEffect(childUid) {
        Log.d(TAG, "🔍 Checking for existing insights for child: $childUid")
        isLoadingInsights = true
        try {
            val (weekId, startDate, endDate) = WeeklyInsightsCollector.getLastWeekDateRange()
            Log.d(TAG, "   Week ID: $weekId ($startDate to $endDate)")

            val existingInsights = AIInsightsGenerator.loadInsightsFromFirestore(childUid, weekId)

            if (existingInsights != null) {
                Log.d(TAG, "   ✅ Found existing insights with ${existingInsights.insights.size} items")
                weeklyInsights = existingInsights
                hasInsights = true
                insightsError = null
            } else {
                Log.d(TAG, "   ℹ️ No existing insights found")
                hasInsights = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error loading insights", e)
            insightsError = "Failed to load insights: ${e.message}"
        } finally {
            isLoadingInsights = false
        }
    }

    // 🆕 NEW: Function to generate insights
    fun generateInsights() {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "🤖 GENERATING WEEKLY INSIGHTS")
        Log.d(TAG, "════════════════════════════════════════")

        coroutineScope.launch {
            isLoadingInsights = true
            insightsError = null

            try {
                // Step 1: Collect weekly data
                Log.d(TAG, "STEP 1: Collecting weekly data...")
                val weeklyData = WeeklyInsightsCollector.collectLastWeekData(
                    context = context,
                    childUid = childUid
                )

                if (weeklyData == null) {
                    Log.w(TAG, "⚠️ No weekly data available")
                    insightsError = "Not enough data for last week. Please try again later."
                    Toast.makeText(
                        context,
                        "Insufficient data for last week. Need at least 1 day of usage data.",
                        Toast.LENGTH_LONG
                    ).show()
                    isLoadingInsights = false
                    return@launch
                }

                Log.d(TAG, "✅ Weekly data collected:")
                Log.d(TAG, "   Days with data: ${weeklyData.dataAvailableDays}/7")
                Log.d(TAG, "   Total apps: ${weeklyData.aggregatedApps.size}")
                Log.d(TAG, "   Total screen time: ${UsageStatsHelper.formatDuration(weeklyData.totalScreenTimeMs)}")

                // Step 2: Generate insights using AI
                Log.d(TAG, "")
                Log.d(TAG, "STEP 2: Generating AI insights...")
                val insights = AIInsightsGenerator.generateInsights(
                    context = context,
                    childUid = childUid,
                    childName = childName,
                    weeklyData = weeklyData
                )

                if (insights != null) {
                    Log.d(TAG, "✅ Insights generated successfully!")
                    Log.d(TAG, "   Insights count: ${insights.insights.size}")
                    weeklyInsights = insights
                    hasInsights = true
                    insightsError = null

                    Toast.makeText(
                        context,
                        "✨ Insights generated successfully!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.e(TAG, "❌ Failed to generate insights")
                    insightsError = "Failed to generate insights. Please try again."
                    Toast.makeText(
                        context,
                        "Failed to generate insights. Check your connection and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ CRITICAL ERROR generating insights", e)
                e.printStackTrace()
                insightsError = "Error: ${e.message}"
                Toast.makeText(
                    context,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isLoadingInsights = false
                Log.d(TAG, "════════════════════════════════════════")
            }
        }
    }

    // Existing LaunchedEffect calls for blocked apps, all apps, etc.
    // ... (keep all your existing code) ...

    // Function to trigger fetch from child device
    fun triggerFetchFromChildDevice() {
        Log.d(TAG, "🔄 Triggering fetch from child device")
        isLoadingAllApps.value = true
        viewModel.fetchAllInstalledApps()
    }

    val isShowingUsageData = state is AppManagementState.Success

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (isShowingUsageData) {
            // Show usage data results (existing code)
            when (state) {
                is AppManagementState.Success -> {
                    UsageDataResultsCard(
                        state = state as AppManagementState.Success,
                        viewModel = viewModel,
                        blockedApps = blockedApps,
                        childUid = childUid
                    )
                }
                is AppManagementState.Loading -> {
                    LoadingContent()
                }
                is AppManagementState.Error -> {
                    ErrorContent(
                        message = (state as AppManagementState.Error).message,
                        onRetry = { viewModel.fetchUsageData() }
                    )
                }
                else -> {}
            }
        } else {
            // Show normal content with cards
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // 🆕 NEW: Weekly Insights Card (added at the top)
                WeeklyInsightsCard(
                    childName = childName,
                    isLoadingInsights = isLoadingInsights,
                    hasInsights = hasInsights,
                    insights = weeklyInsights,
                    onGetInsights = { generateInsights() },
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 🆕 NEW: Show error if any
                if (insightsError != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = insightsError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // 🆕 NEW: Top Apps Card (if insights available)
                if (hasInsights && weeklyInsights != null && weeklyInsights!!.topApps.isNotEmpty()) {
                    TopAppsInsightsCard(
                        topApps = weeklyInsights!!.topApps,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                // Existing Cards (keep all your existing code)
                // Card 1: All Apps
                AllAppsCard(
                    apps = allInstalledApps.value,
                    isLoading = isLoadingAllApps.value,
                    hasLoadedAllApps = hasLoadedAllApps.value,
                    blockedApps = blockedApps,
                    childUid = childUid,
                    onRefresh = { triggerFetchFromChildDevice() },
                    onToggleBlocked = { packageName, appName ->
                        viewModel.toggleAppBlocked(packageName, appName)
                    },
                    onAppUnblocked = { packageName ->
                        blockedAppsList.value = blockedAppsList.value.filter {
                            it.packageName != packageName
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Card 2: Blocked Apps
                BlockedAppsSection(
                    blockedApps = blockedAppsList.value,
                    isLoading = isLoadingBlockedApps.value,
                    childUid = childUid,
                    onUnblock = { packageName ->
                        // ... existing unblock code ...
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Button for Fetch Usage Data
                Button(
                    onClick = { viewModel.fetchUsageData() },
                    enabled = state !is AppManagementState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fetch Usage Data", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// Updated AllAppsCard with proper button states
@Composable
private fun AllAppsCard(
    apps: List<AppUsageInfo>,
    isLoading: Boolean,
    hasLoadedAllApps: Boolean,
    blockedApps: Set<String>,
    childUid: String,
    onRefresh: () -> Unit,
    onToggleBlocked: (String, String) -> Unit,
    onAppUnblocked: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = Color(0xFF1976D2),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "All Apps",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF212121)
                        )
                        Text(
                            text = when {
                                isLoading -> "Loading from device..."
                                !hasLoadedAllApps -> "Click to load apps"
                                apps.isEmpty() -> "No apps loaded"
                                else -> "${apps.size} apps"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isLoading) Color(0xFF1976D2) else Color(0xFF757575)
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = Color(0xFF757575)
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFFE0E0E0))
                Spacer(modifier = Modifier.height(16.dp))

                // Refresh Button with loading state
                Button(
                    onClick = onRefresh,
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLoading) Color(0xFFBBDEFB) else Color(0xFF4CAF50),
                        disabledContainerColor = Color(0xFFE0E0E0)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Fetching from Device...",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh All Apps", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Loading state
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                color = Color(0xFF1976D2)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Requesting apps from child device...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF1976D2),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "This may take a few seconds",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF757575),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                // No apps state
                else if (!hasLoadedAllApps) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color(0xFFBDBDBD)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No apps loaded yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFF757575),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Click 'Refresh All Apps' to fetch from device",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9E9E9E),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                // Empty apps state
                else if (apps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No apps available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9E9E9E),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                // Apps list
                else {
                    Column {
                        // Show total count
                        Text(
                            text = "Showing all ${apps.size} apps",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1976D2),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        apps.forEach { app ->
                            AppListItem(
                                app = app,
                                isBlocked = blockedApps.contains(app.packageName),
                                onToggleBlocked = {
                                    if (!blockedApps.contains(app.packageName)) {
                                        onToggleBlocked(app.packageName, app.appName)
                                    } else {
                                        val db = FirebaseFirestore.getInstance()
                                        db.collection("users")
                                            .document(childUid)
                                            .collection("blockedApps")
                                            .document(app.packageName)
                                            .delete()
                                            .addOnSuccessListener {
                                                Log.d(TAG, "Successfully deleted blocked app: ${app.packageName}")
                                                onAppUnblocked(app.packageName)
                                                onToggleBlocked(app.packageName, app.appName)
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e(TAG, "Error deleting blocked app", e)
                                            }
                                    }
                                }
                            )

                            if (app != apps.last()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// New Composable: Usage Data Results Card
@Composable
private fun UsageDataResultsCard(
    state: AppManagementState.Success,
    viewModel: AppManagementViewModel,
    blockedApps: Set<String>,
    childUid: String
) {
    val sortedApps = remember(state.apps, viewModel.searchQuery.collectAsState().value, viewModel.sortBy.collectAsState().value) {
        viewModel.getSortedAndFilteredApps(state.apps)
    }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF1976D2),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Usage Data",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF212121)
                        )
                        Text(
                            text = "Last updated: ${state.lastUpdated}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF757575)
                        )
                    }
                }

                // Close button
                IconButton(onClick = { viewModel.resetToIdle() }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF757575)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFFE0E0E0))
            Spacer(modifier = Modifier.height(16.dp))

            // Action bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.fetchUsageData() },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Refresh", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Sort dropdown
                var expandedSort by remember { mutableStateOf(false) }

                OutlinedButton(
                    onClick = { expandedSort = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(sortBy.displayName, style = MaterialTheme.typography.labelSmall)
                }

                DropdownMenu(
                    expanded = expandedSort,
                    onDismissRequest = { expandedSort = false }
                ) {
                    SortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                viewModel.setSortBy(option)
                                expandedSort = false
                            }
                        )
                    }
                }
            }

            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { viewModel.setSearchQuery(it) },
                onClearClick = { viewModel.setSearchQuery("") }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Apps count
            Text(
                text = "${sortedApps.size} apps",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF757575),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Apps list
            if (sortedApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No apps found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF9E9E9E)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sortedApps) { app ->
                        AppListItem(
                            app = app,
                            isBlocked = blockedApps.contains(app.packageName),
                            onToggleBlocked = {
                                if (!blockedApps.contains(app.packageName)) {
                                    viewModel.toggleAppBlocked(app.packageName, app.appName)
                                } else {
                                    val db = FirebaseFirestore.getInstance()
                                    db.collection("users")
                                        .document(childUid)
                                        .collection("blockedApps")
                                        .document(app.packageName)
                                        .delete()
                                        .addOnSuccessListener {
                                            Log.d(TAG, "Successfully deleted blocked app: ${app.packageName}")
                                            viewModel.toggleAppBlocked(app.packageName, app.appName)
                                        }
                                        .addOnFailureListener { e ->
                                            Log.e(TAG, "Error deleting blocked app", e)
                                        }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedAppsSection(
    blockedApps: List<BlockedAppInfo>,
    isLoading: Boolean,
    childUid: String,
    onUnblock: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    tint = Color(0xFFD32F2F),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Blocked Apps",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF212121)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (blockedApps.isEmpty()) {
                Text(
                    text = "No blocked apps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column {
                    blockedApps.forEach { app ->
                        BlockedAppItem(
                            app = app,
                            onUnblock = { onUnblock(app.packageName) }
                        )
                        if (app != blockedApps.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = Color(0xFFE0E0E0)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedAppItem(
    app: BlockedAppInfo,
    onUnblock: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF212121)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF757575)
            )
        }

        Button(
            onClick = onUnblock,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4CAF50)
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.height(36.dp)
        ) {
            Text(
                text = "Unblock",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun AppManagementHeader() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1976D2)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "App Management",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Monitor and control app usage",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = Color(0xFF1976D2)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Collecting Data",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFF212121)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Requesting app usage data from device...",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF757575),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "This may take a few seconds",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9E9E9E),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color(0xFFD32F2F)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFD32F2F)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF757575),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .height(48.dp)
                .widthIn(min = 200.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Try Again", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearClick: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        placeholder = {
            Text("Search apps...", color = Color(0xFF9E9E9E))
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Color(0xFF9E9E9E)
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = onClearClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = Color(0xFF9E9E9E)
                    )
                }
            }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF1976D2),
            unfocusedBorderColor = Color(0xFFE0E0E0)
        )
    )
}