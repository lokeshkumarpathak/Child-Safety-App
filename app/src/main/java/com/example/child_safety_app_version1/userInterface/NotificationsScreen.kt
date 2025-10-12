package com.example.child_safety_app_version1.userInterface

import android.content.Context
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.tasks.await
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.*

data class SafetyNotification(
    val id: String = "",
    val title: String,
    val body: String,
    val type: String,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long,
    val read: Boolean = false,
    val childUid: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(navController: NavController) {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf<List<SafetyNotification>>(emptyList()) }
    var selectedNotification by remember { mutableStateOf<SafetyNotification?>(null) }
    var showMapDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var userRole by remember { mutableStateOf<String?>(null) }

    // Load notifications with real-time updates
    LaunchedEffect(Unit) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.e("NotificationsScreen", "❌ No user logged in")
            errorMessage = "No user logged in"
            isLoading = false
            return@LaunchedEffect
        }

        Log.d("NotificationsScreen", "════════════════════════════════════════")
        Log.d("NotificationsScreen", "🔔 NOTIFICATIONS SCREEN STARTING")
        Log.d("NotificationsScreen", "════════════════════════════════════════")
        Log.d("NotificationsScreen", "Current User UID: ${currentUser.uid.take(10)}...")
        Log.d("NotificationsScreen", "Full UID: ${currentUser.uid}")

        // Determine user role
        try {
            val db = FirebaseFirestore.getInstance()
            Log.d("NotificationsScreen", "📋 Fetching user document...")
            val userDoc = db.collection("users").document(currentUser.uid).get().await()

            if (!userDoc.exists()) {
                Log.e("NotificationsScreen", "❌ User document does NOT exist!")
                Log.e("NotificationsScreen", "   Path: /users/${currentUser.uid}")
            } else {
                Log.d("NotificationsScreen", "✅ User document exists")
                Log.d("NotificationsScreen", "   All fields: ${userDoc.data}")
            }

            userRole = userDoc.getString("role")
            Log.d("NotificationsScreen", "👤 User Role: '$userRole'")
            Log.d("NotificationsScreen", "   Role field type: ${userRole?.javaClass?.simpleName}")
            Log.d("NotificationsScreen", "   Role lowercase: '${userRole?.lowercase()}'")

        } catch (e: Exception) {
            Log.e("NotificationsScreen", "❌ Error fetching user role", e)
            e.printStackTrace()
        }

        // Check role - be flexible with case
        val roleCheck = userRole?.lowercase()?.trim()
        Log.d("NotificationsScreen", "🔍 Role check: '$roleCheck'")

        if (roleCheck != "parent") {
            Log.w("NotificationsScreen", "⚠️ User is not a parent (role: '$userRole')")
            Log.w("NotificationsScreen", "   Showing empty notifications screen")
            isLoading = false
            return@LaunchedEffect
        }

        Log.d("NotificationsScreen", "✅ User is a parent - proceeding to load notifications")

        Log.d("NotificationsScreen", "✅ User is a parent - proceeding to load notifications")

        // Set up real-time listener
        val db = FirebaseFirestore.getInstance()
        val notificationsPath = "users/${currentUser.uid}/notifications"
        Log.d("NotificationsScreen", "")
        Log.d("NotificationsScreen", "📂 Setting up Firestore listener...")
        Log.d("NotificationsScreen", "   Path: /$notificationsPath")

        val listenerRegistration = db.collection("users")
            .document(currentUser.uid)
            .collection("notifications")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                Log.d("NotificationsScreen", "")
                Log.d("NotificationsScreen", "🔄 Firestore Listener Triggered")
                Log.d("NotificationsScreen", "────────────────────────────────────────")

                if (error != null) {
                    Log.e("NotificationsScreen", "❌ Listen failed", error)
                    Log.e("NotificationsScreen", "   Error type: ${error.javaClass.simpleName}")
                    Log.e("NotificationsScreen", "   Error message: ${error.message}")
                    errorMessage = "Error loading notifications: ${error.message}"
                    isLoading = false
                    return@addSnapshotListener
                }

                if (snapshot == null) {
                    Log.w("NotificationsScreen", "⚠️ Snapshot is null")
                    isLoading = false
                    return@addSnapshotListener
                }

                Log.d("NotificationsScreen", "📱 Snapshot received:")
                Log.d("NotificationsScreen", "   Documents count: ${snapshot.size()}")
                Log.d("NotificationsScreen", "   Is empty: ${snapshot.isEmpty}")
                Log.d("NotificationsScreen", "   Metadata: ${snapshot.metadata}")

                if (snapshot.isEmpty) {
                    Log.w("NotificationsScreen", "📭 No notification documents found")
                    Log.w("NotificationsScreen", "   Check Firestore console at:")
                    Log.w("NotificationsScreen", "   /$notificationsPath")
                    notifications = emptyList()
                    isLoading = false
                    errorMessage = null
                    return@addSnapshotListener
                }

                val loadedNotifications = snapshot.documents.mapNotNull { doc ->
                    try {
                        Log.d("NotificationsScreen", "")
                        Log.d("NotificationsScreen", "   📄 Processing document: ${doc.id}")
                        Log.d("NotificationsScreen", "      All fields: ${doc.data}")

                        // Smart latitude/longitude parsing - handles both String and Double types
                        val latitude = when (val latValue = doc.get("latitude")) {
                            is String -> if (latValue.isNotEmpty()) latValue.toDoubleOrNull() else null
                            is Double -> latValue
                            is Long -> latValue.toDouble()
                            is Number -> latValue.toDouble()
                            else -> {
                                Log.w("NotificationsScreen", "      ⚠️ Latitude is unexpected type: ${latValue?.javaClass?.simpleName}")
                                null
                            }
                        }

                        val longitude = when (val lonValue = doc.get("longitude")) {
                            is String -> if (lonValue.isNotEmpty()) lonValue.toDoubleOrNull() else null
                            is Double -> lonValue
                            is Long -> lonValue.toDouble()
                            is Number -> lonValue.toDouble()
                            else -> {
                                Log.w("NotificationsScreen", "      ⚠️ Longitude is unexpected type: ${lonValue?.javaClass?.simpleName}")
                                null
                            }
                        }

                        SafetyNotification(
                            id = doc.id,
                            title = doc.getString("title") ?: "Alert",
                            body = doc.getString("body") ?: "",
                            type = doc.getString("type") ?: "UNKNOWN",
                            latitude = latitude,
                            longitude = longitude,
                            timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                            read = doc.getBoolean("read") ?: false,
                            childUid = doc.getString("childUid") ?: ""
                        ).also {
                            Log.d("NotificationsScreen", "      ✅ Created: ${it.title} (${formatTimestamp(it.timestamp)})")
                            Log.d("NotificationsScreen", "         Type: ${it.type}")
                            Log.d("NotificationsScreen", "         Location: ${it.latitude}, ${it.longitude}")
                            Log.d("NotificationsScreen", "         Read: ${it.read}")
                        }
                    } catch (e: Exception) {
                        Log.e("NotificationsScreen", "      ❌ Error parsing document ${doc.id}", e)
                        e.printStackTrace()
                        null
                    }
                }

                Log.d("NotificationsScreen", "")
                Log.d("NotificationsScreen", "📊 Processing Complete:")
                Log.d("NotificationsScreen", "   Total documents: ${snapshot.size()}")
                Log.d("NotificationsScreen", "   Successfully parsed: ${loadedNotifications.size}")
                Log.d("NotificationsScreen", "   Failed to parse: ${snapshot.size() - loadedNotifications.size}")

                notifications = loadedNotifications
                isLoading = false
                errorMessage = null

                Log.d("NotificationsScreen", "✅ Notifications list updated with ${loadedNotifications.size} items")
                Log.d("NotificationsScreen", "════════════════════════════════════════")
            }

        kotlinx.coroutines.awaitCancellation()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Safety Notifications", fontWeight = FontWeight.Bold)
                        if (notifications.isNotEmpty()) {
                            Text(
                                text = "${notifications.count { !it.read }} new, ${notifications.size} total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = {
                        Log.d("NotificationsScreen", "Manual refresh triggered")
                    }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }

                    if (notifications.isNotEmpty()) {
                        IconButton(onClick = {
                            val currentUser = FirebaseAuth.getInstance().currentUser
                            if (currentUser != null) {
                                clearAllNotifications(currentUser.uid)
                            }
                        }) {
                            Icon(Icons.Default.Delete, "Clear All")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> LoadingState()
                errorMessage != null -> ErrorState(errorMessage!!)
                notifications.isEmpty() -> EmptyState()
                else -> NotificationsList(
                    notifications = notifications,
                    onViewLocation = { notification ->
                        selectedNotification = notification
                        showMapDialog = true
                        markNotificationAsRead(notification.id)
                    },
                    onDismiss = { notification ->
                        dismissNotification(notification.id)
                    }
                )
            }
        }
    }

    // Dynamic Map Dialog
    if (showMapDialog && selectedNotification != null) {
        DynamicLocationMapDialog(
            notification = selectedNotification!!,
            onDismiss = {
                showMapDialog = false
                selectedNotification = null
            },
            context = context
        )
    }
}

@Composable
fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text("Loading notifications...", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ErrorState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Error Loading Notifications",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription = "No notifications",
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "All Clear!",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No safety alerts at the moment",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "You'll be notified when your child needs help",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun NotificationsList(
    notifications: List<SafetyNotification>,
    onViewLocation: (SafetyNotification) -> Unit,
    onDismiss: (SafetyNotification) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = notifications.sortedByDescending { it.timestamp },
            key = { it.id }
        ) { notification ->
            AnimatedNotificationCard(
                notification = notification,
                onViewLocation = { onViewLocation(notification) },
                onDismiss = { onDismiss(notification) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimatedNotificationCard(
    notification: SafetyNotification,
    onViewLocation: () -> Unit,
    onDismiss: () -> Unit
) {
    // Pulsing animation for unread notifications
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val icon = when (notification.type) {
        "OUTSIDE_SAFE_ZONE" -> Icons.Default.Warning
        "EMERGENCY" -> Icons.Default.NotificationImportant
        "LOCATION_DISABLED" -> Icons.Default.LocationOff
        "CHILD_LOGGED_OUT" -> Icons.Default.Logout
        else -> Icons.Default.Info
    }

    val (containerColor, contentColor) = when (notification.type) {
        "EMERGENCY" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "OUTSIDE_SAFE_ZONE" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.read) {
                containerColor.copy(alpha = 0.5f)
            } else {
                containerColor
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (!notification.read) 4.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Icon with pulsing background for unread
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                contentColor.copy(
                                    alpha = if (notification.read) 0.2f else pulseAlpha * 0.3f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = contentColor
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = notification.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                            if (!notification.read) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                                ) {
                                    Text(
                                        text = "NEW",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = contentColor.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = formatTimestamp(notification.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Dismiss button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = contentColor.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body text
            Text(
                text = notification.body,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )

            // Location section
            if (notification.latitude != null && notification.longitude != null) {
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Location Available",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${String.format("%.5f", notification.latitude)}, ${
                                        String.format("%.5f", notification.longitude)
                                    }",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }

                            FilledTonalButton(
                                onClick = onViewLocation,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Map,
                                    contentDescription = "View on map",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("View Map")
                            }
                        }

                        // Real-time update indicator
                        if (notification.type == "OUTSIDE_SAFE_ZONE") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Updates every 30 seconds",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DynamicLocationMapDialog(
    notification: SafetyNotification,
    onDismiss: () -> Unit,
    context: Context
) {
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var isMapReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
        }
    }

    // ⭐ CRITICAL: This updates the map marker dynamically when notification changes
    LaunchedEffect(notification.latitude, notification.longitude, notification.timestamp) {
        mapView?.let { map ->
            notification.latitude?.let { lat ->
                notification.longitude?.let { lon ->
                    Log.d("LocationMapDialog", "🗺️ Updating map dynamically")
                    Log.d("LocationMapDialog", "   New position: $lat, $lon")
                    Log.d("LocationMapDialog", "   Timestamp: ${notification.timestamp}")

                    // Update map center with animation
                    map.controller.animateTo(GeoPoint(lat, lon))

                    // Remove old markers
                    map.overlays.removeAll { it is Marker }

                    // Add new marker
                    val marker = Marker(map).apply {
                        position = GeoPoint(lat, lon)
                        title = "📍 Child's Current Location"
                        snippet = "Last updated: ${formatTimestamp(notification.timestamp)}"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }

                    map.overlays.add(marker)
                    map.invalidate()

                    Log.d("LocationMapDialog", "✅ Map updated successfully")
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Child's Location",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Live tracking • Updates every 30s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }
                }

                // Notification info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = notification.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Map
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                controller.setZoom(15.0)

                                notification.latitude?.let { lat ->
                                    notification.longitude?.let { lon ->
                                        controller.setCenter(GeoPoint(lat, lon))

                                        val marker = Marker(this).apply {
                                            position = GeoPoint(lat, lon)
                                            title = "📍 Child's Current Location"
                                            snippet = "Last updated: ${formatTimestamp(notification.timestamp)}"
                                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        }
                                        overlays.add(marker)
                                    }
                                }

                                mapView = this
                                isMapReady = true
                            }
                        },
                        update = { map ->
                            map.onResume()
                        }
                    )

                    // Loading overlay
                    if (!isMapReady) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                // Footer with coordinates and timestamp
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        notification.latitude?.let { lat ->
                            notification.longitude?.let { lon ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MyLocation,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Coordinates: ${String.format("%.6f", lat)}, ${
                                            String.format("%.6f", lon)
                                        }",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Updated ${formatTimestamp(notification.timestamp)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    // Pulsing indicator
                                    val infiniteTransition = rememberInfiniteTransition(label = "live")
                                    val alpha by infiniteTransition.animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1000),
                                            repeatMode = RepeatMode.Reverse
                                        ),
                                        label = "live"
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "LIVE",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDetach()
        }
    }
}

// Helper functions
private fun markNotificationAsRead(notificationId: String) {
    try {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()

        db.collection("users")
            .document(currentUser.uid)
            .collection("notifications")
            .document(notificationId)
            .update("read", true)
            .addOnSuccessListener {
                Log.d("NotificationsScreen", "✅ Marked notification as read: $notificationId")
            }
            .addOnFailureListener { e ->
                Log.e("NotificationsScreen", "Failed to mark notification as read", e)
            }
    } catch (e: Exception) {
        Log.e("NotificationsScreen", "Error marking notification as read", e)
    }
}

private fun dismissNotification(notificationId: String) {
    try {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()

        db.collection("users")
            .document(currentUser.uid)
            .collection("notifications")
            .document(notificationId)
            .delete()
            .addOnSuccessListener {
                Log.d("NotificationsScreen", "✅ Dismissed notification: $notificationId")
            }
            .addOnFailureListener { e ->
                Log.e("NotificationsScreen", "Failed to dismiss notification", e)
            }
    } catch (e: Exception) {
        Log.e("NotificationsScreen", "Error dismissing notification", e)
    }
}

private fun clearAllNotifications(parentUid: String) {
    try {
        Log.d("NotificationsScreen", "Clearing all notifications for parent: ${parentUid.take(10)}...")

        val db = FirebaseFirestore.getInstance()

        db.collection("users")
            .document(parentUid)
            .collection("notifications")
            .get()
            .addOnSuccessListener { querySnapshot ->
                val batch = db.batch()

                querySnapshot.documents.forEach { doc ->
                    batch.delete(doc.reference)
                }

                batch.commit()
                    .addOnSuccessListener {
                        Log.d("NotificationsScreen", "✅ Successfully cleared ${querySnapshot.size()} notification(s)")
                    }
                    .addOnFailureListener { e ->
                        Log.e("NotificationsScreen", "Failed to clear notifications", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("NotificationsScreen", "Failed to query notifications for clearing", e)
            }
    } catch (e: Exception) {
        Log.e("NotificationsScreen", "Error clearing notifications", e)
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60000 -> "just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        diff < 604800000 -> "${diff / 86400000}d ago"
        else -> SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}