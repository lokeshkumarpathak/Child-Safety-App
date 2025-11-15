package com.example.child_safety_app_version1.userInterface

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewChildLocationScreen(
    latitude: Double,
    longitude: Double,
    childName: String,
    locationMethod: String,
    accuracy: Float,
    timestamp: Long,
    navController: NavHostController
) {
    val context = LocalContext.current

    // Initialize OSMDroid configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osm_prefs", Context.MODE_PRIVATE)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "$childName's Location",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Location info card
            LocationInfoCard(
                childName = childName,
                locationMethod = locationMethod,
                accuracy = accuracy,
                timestamp = timestamp,
                latitude = latitude,
                longitude = longitude
            )

            // OSM Map
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { mapContext ->
                        MapView(mapContext).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)

                            // Set initial position and zoom
                            controller.setZoom(16.0)
                            controller.setCenter(GeoPoint(latitude, longitude))

                            // Add marker for child location
                            val marker = Marker(this).apply {
                                position = GeoPoint(latitude, longitude)
                                title = childName
                                snippet = "Last seen here"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            overlays.add(marker)

                            // Add accuracy circle
                            val accuracyCircle = createAccuracyCircle(
                                mapContext,
                                latitude,
                                longitude,
                                accuracy
                            )
                            overlays.add(accuracyCircle)

                            invalidate()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Method indicator badge
                LocationMethodBadge(
                    locationMethod = locationMethod,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
fun LocationInfoCard(
    childName: String,
    locationMethod: String,
    accuracy: Float,
    timestamp: Long,
    latitude: Double,
    longitude: Double
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (locationMethod) {
                "GPS" -> MaterialTheme.colorScheme.primaryContainer
                "CELL_TOWER" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "📍 Location Update",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Status indicator
                val (statusText, statusColor) = when (locationMethod) {
                    "GPS" -> "High Accuracy" to Color(0xFF4CAF50)
                    "CELL_TOWER" -> "Approximate" to Color(0xFFFF9800)
                    else -> "Unknown" to Color.Gray
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Divider()

            // Location method
            InfoRow(
                icon = Icons.Default.SignalCellularAlt,
                label = "Method",
                value = when (locationMethod) {
                    "GPS" -> "🛰️ GPS Satellite"
                    "CELL_TOWER" -> "📡 Cell Tower"
                    else -> "❓ Unknown"
                }
            )

            // Accuracy
            InfoRow(
                icon = Icons.Default.LocationOn,
                label = "Accuracy",
                value = "±${accuracy.toInt()} meters"
            )

            // Time
            val timeText = formatTimestamp(timestamp)
            InfoRow(
                icon = Icons.Default.Schedule,
                label = "Updated",
                value = timeText
            )

            // Coordinates (small text)
            Text(
                text = "Coordinates: ${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )

        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun LocationMethodBadge(
    locationMethod: String,
    modifier: Modifier = Modifier
) {
    val (badgeText, badgeColor) = when (locationMethod) {
        "GPS" -> "🛰️ GPS" to Color(0xFF4CAF50)
        "CELL_TOWER" -> "📡 Cell Tower" to Color(0xFFFF9800)
        else -> "❓ Unknown" to Color.Gray
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = badgeColor,
        shadowElevation = 4.dp
    ) {
        Text(
            text = badgeText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Create an accuracy circle overlay for the map
 */
fun createAccuracyCircle(
    context: Context,
    latitude: Double,
    longitude: Double,
    accuracyMeters: Float
): Polygon {
    val circle = Polygon()
    circle.fillColor = Color(0x3300A6FF).hashCode() // Semi-transparent blue
    circle.strokeColor = Color(0xFF0088FF).hashCode() // Blue border
    circle.strokeWidth = 2f

    // Create circle points (approximation with 64 points)
    val points = mutableListOf<GeoPoint>()
    val earthRadius = 6371000.0 // meters
    val radiusRad = accuracyMeters / earthRadius
    val centerLat = Math.toRadians(latitude)
    val centerLon = Math.toRadians(longitude)

    for (i in 0..64) {
        val angle = Math.toRadians(i * 360.0 / 64)
        val pointLat = Math.asin(
            Math.sin(centerLat) * Math.cos(radiusRad) +
                    Math.cos(centerLat) * Math.sin(radiusRad) * Math.cos(angle)
        )
        val pointLon = centerLon + Math.atan2(
            Math.sin(angle) * Math.sin(radiusRad) * Math.cos(centerLat),
            Math.cos(radiusRad) - Math.sin(centerLat) * Math.sin(pointLat)
        )

        points.add(
            GeoPoint(
                Math.toDegrees(pointLat),
                Math.toDegrees(pointLon)
            )
        )
    }

    circle.points = points
    return circle
}

/**
 * Format timestamp to human-readable time
 */
fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000} minute(s) ago"
        diff < 86400_000 -> {
            val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
            "Today at ${formatter.format(Date(timestamp))}"
        }
        else -> {
            val formatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            formatter.format(Date(timestamp))
        }
    }
}