package com.example.child_safety_app_version1.userInterface

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

// Data class for search results
data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val boundingBox: List<Double>? = null,
    val type: String = "",
    val osmType: String = ""
)

// Data class for saved safe zones
data class SafeZone(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val centerLat: Double,
    val centerLon: Double,
    val boundingBox: List<Double>? = null,
    val radius: Double = 100.0, // Default radius in meters
    val type: String = "custom"
) {
    // Helper property for GeoPoint (not stored in Firestore)
    val center: GeoPoint
        get() = GeoPoint(centerLat, centerLon)

    // Convert to Map for Firestore
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "centerLat" to centerLat,
            "centerLon" to centerLon,
            "boundingBox" to boundingBox,
            "radius" to radius,
            "type" to type
        )
    }

    companion object {
        // Create SafeZone from Firestore document
        fun fromMap(map: Map<String, Any>): SafeZone? {
            return try {
                SafeZone(
                    id = map["id"] as? String ?: UUID.randomUUID().toString(),
                    name = map["name"] as? String ?: "",
                    centerLat = (map["centerLat"] as? Number)?.toDouble() ?: 0.0,
                    centerLon = (map["centerLon"] as? Number)?.toDouble() ?: 0.0,
                    boundingBox = (map["boundingBox"] as? List<*>)?.mapNotNull {
                        (it as? Number)?.toDouble()
                    },
                    radius = (map["radius"] as? Number)?.toDouble() ?: 100.0,
                    type = map["type"] as? String ?: "custom"
                )
            } catch (e: Exception) {
                Log.e("SafeZone", "Error parsing SafeZone: ${e.message}")
                null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedMapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = FirebaseAuth.getInstance()
    val firestore = Firebase.firestore

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var showResults by remember { mutableStateOf(false) }

    var safeZones by remember { mutableStateOf<List<SafeZone>>(emptyList()) }
    var showSafeZonesDialog by remember { mutableStateOf(false) }
    var selectedSearchResult by remember { mutableStateOf<SearchResult?>(null) }
    var showAddSafeZoneDialog by remember { mutableStateOf(false) }
    var isLoadingSafeZones by remember { mutableStateOf(true) }
    var isSavingZone by remember { mutableStateOf(false) }

    fun addSafeZoneOverlay(map: MapView?, safeZone: SafeZone) {
        map?.let {
            val polygon = Polygon(it)

            if (safeZone.boundingBox != null && safeZone.boundingBox.size >= 4) {
                // Create rectangle from bounding box [minLat, maxLat, minLon, maxLon]
                val points = listOf(
                    GeoPoint(safeZone.boundingBox[0], safeZone.boundingBox[2]),
                    GeoPoint(safeZone.boundingBox[0], safeZone.boundingBox[3]),
                    GeoPoint(safeZone.boundingBox[1], safeZone.boundingBox[3]),
                    GeoPoint(safeZone.boundingBox[1], safeZone.boundingBox[2]),
                    GeoPoint(safeZone.boundingBox[0], safeZone.boundingBox[2])
                )
                polygon.points = points
            } else {
                // Create circular boundary
                val points = createCirclePoints(safeZone.center, safeZone.radius)
                polygon.points = points
            }

            polygon.fillColor = 0x3000FF00.toInt() // Semi-transparent green
            polygon.strokeColor = 0xFF00FF00.toInt() // Solid green
            polygon.strokeWidth = 3f
            polygon.title = safeZone.name

            it.overlays.add(polygon)
            it.invalidate()
        }
    }

    // Refresh all safe zone overlays
    fun refreshSafeZones() {
        mapView?.let { map ->
            // Remove existing polygons
            map.overlays.removeAll { it is Polygon }
            // Add all safe zones
            safeZones.forEach { addSafeZoneOverlay(map, it) }
            map.invalidate()
        }
    }

    // Initialize OSMDroid configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
        }
    }

    // Load Safe Zones from Firestore on startup
    LaunchedEffect(Unit) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            scope.launch {
                try {
                    val snapshot = firestore.collection("users")
                        .document(uid)
                        .collection("safeZones")
                        .get()
                        .await()

                    val loadedZones = snapshot.documents.mapNotNull { doc ->
                        SafeZone.fromMap(doc.data ?: emptyMap())
                    }

                    safeZones = loadedZones
                    isLoadingSafeZones = false

                    // Add overlays for loaded zones after a small delay to ensure map is ready
                    kotlinx.coroutines.delay(500)
                    loadedZones.forEach { zone ->
                        addSafeZoneOverlay(mapView, zone)
                    }
                } catch (e: Exception) {
                    Log.e("LoadSafeZones", "Error loading safe zones: ${e.message}")
                    isLoadingSafeZones = false
                }
            }
        } else {
            isLoadingSafeZones = false
        }
    }



    // Function to save Safe Zone to Firestore
    fun saveSafeZoneToFirestore(safeZone: SafeZone, onComplete: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            onComplete(false)
            return
        }

        firestore.collection("users")
            .document(uid)
            .collection("safeZones")
            .document(safeZone.id)
            .set(safeZone.toMap())
            .addOnSuccessListener {
                Log.d("SaveSafeZone", "Safe zone saved successfully")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e("SaveSafeZone", "Error saving safe zone: ${e.message}")
                onComplete(false)
            }
    }

    // Function to delete Safe Zone from Firestore
    fun deleteSafeZoneFromFirestore(zoneId: String, onComplete: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            onComplete(false)
            return
        }

        firestore.collection("users")
            .document(uid)
            .collection("safeZones")
            .document(zoneId)
            .delete()
            .addOnSuccessListener {
                Log.d("DeleteSafeZone", "Safe zone deleted successfully")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e("DeleteSafeZone", "Error deleting safe zone: ${e.message}")
                onComplete(false)
            }
    }

    // Search function using Nominatim API
    fun performSearch(query: String) {
        if (query.isBlank()) {
            showResults = false
            return
        }

        scope.launch {
            isSearching = true
            try {
                val results = withContext(Dispatchers.IO) {
                    searchLocation(query)
                }
                searchResults = results
                showResults = results.isNotEmpty()
            } catch (e: Exception) {
                Log.e("MapSearch", "Search error: ${e.message}")
                searchResults = emptyList()
                showResults = false
            } finally {
                isSearching = false
            }
        }
    }

    // Function to add polygon overlay for safe zone


    Box(modifier = Modifier.fillMaxSize()) {
        // Map View
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(12.0)
                    controller.setCenter(GeoPoint(28.6139, 77.2090))

                    val delhiMarker = Marker(this)
                    delhiMarker.position = GeoPoint(28.6139, 77.2090)
                    delhiMarker.title = "New Delhi"
                    delhiMarker.snippet = "Capital of India"
                    delhiMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    overlays.add(delhiMarker)

                    mapView = this
                }
            },
            update = { map ->
                map.onResume()
            }
        )

        // Loading indicator for safe zones
        if (isLoadingSafeZones) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
        }

        // Search Bar at the top
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary
                    )

                    TextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            if (it.length > 2) {
                                performSearch(it)
                            } else {
                                showResults = false
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        placeholder = { Text("Search locations...") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        singleLine = true
                    )

                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            showResults = false
                            searchResults = emptyList()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear"
                            )
                        }
                    }
                }

                // Search Results Dropdown
                AnimatedVisibility(visible = showResults && searchResults.isNotEmpty()) {
                    Divider()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(searchResults) { result ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedSearchResult = result
                                        mapView?.controller?.animateTo(
                                            GeoPoint(result.lat, result.lon)
                                        )
                                        mapView?.controller?.setZoom(15.0)

                                        // Add marker for searched location
                                        mapView?.let { map ->
                                            val marker = Marker(map)
                                            marker.position = GeoPoint(result.lat, result.lon)
                                            marker.title = result.displayName
                                            marker.setAnchor(
                                                Marker.ANCHOR_CENTER,
                                                Marker.ANCHOR_BOTTOM
                                            )
                                            map.overlays.add(marker)
                                            map.invalidate()
                                        }

                                        showResults = false
                                        showAddSafeZoneDialog = true
                                    }
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = result.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                if (result.type.isNotEmpty()) {
                                    Text(
                                        text = result.type,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (result != searchResults.last()) {
                                Divider()
                            }
                        }
                    }
                }
            }
        }

        // Floating Action Buttons
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = { mapView?.controller?.zoomIn() },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "Zoom In")
            }

            FloatingActionButton(
                onClick = { mapView?.controller?.zoomOut() },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Remove, "Zoom Out")
            }

            BadgedBox(
                badge = {
                    if (safeZones.isNotEmpty()) {
                        Badge { Text("${safeZones.size}") }
                    }
                }
            ) {
                FloatingActionButton(
                    onClick = { showSafeZonesDialog = true },
                    modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.tertiary
                ) {
                    Icon(Icons.Default.Shield, "Safe Zones")
                }
            }
        }

        // Information Card
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Child Safety Map",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${safeZones.size} Safe Zone${if (safeZones.size != 1) "s" else ""} Defined",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    // Add Safe Zone Dialog
    if (showAddSafeZoneDialog && selectedSearchResult != null) {
        var zoneName by remember { mutableStateOf(selectedSearchResult!!.displayName) }
        var showError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!isSavingZone) {
                    showAddSafeZoneDialog = false
                }
            },
            title = { Text("Add Safe Zone") },
            text = {
                Column {
                    Text("Add this location as a safe zone?")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = zoneName,
                        onValueChange = {
                            zoneName = it
                            showError = false
                        },
                        label = { Text("Zone Name") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSavingZone,
                        isError = showError
                    )
                    if (showError) {
                        Text(
                            text = "Please enter a zone name",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (isSavingZone) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Saving...")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (zoneName.trim().isEmpty()) {
                            showError = true
                            return@Button
                        }

                        isSavingZone = true
                        val newZone = SafeZone(
                            name = zoneName.trim(),
                            centerLat = selectedSearchResult!!.lat,
                            centerLon = selectedSearchResult!!.lon,
                            boundingBox = selectedSearchResult!!.boundingBox,
                            type = selectedSearchResult!!.type
                        )

                        saveSafeZoneToFirestore(newZone) { success ->
                            isSavingZone = false
                            if (success) {
                                safeZones = safeZones + newZone
                                addSafeZoneOverlay(mapView, newZone)
                                showAddSafeZoneDialog = false
                                selectedSearchResult = null
                            } else {
                                // Show error toast or message
                                scope.launch {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Failed to save safe zone",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    enabled = !isSavingZone
                ) {
                    Text("Add Safe Zone")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddSafeZoneDialog = false
                        selectedSearchResult = null
                    },
                    enabled = !isSavingZone
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Safe Zones Management Dialog
    if (showSafeZonesDialog) {
        Dialog(onDismissRequest = { showSafeZonesDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Safe Zones",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { showSafeZonesDialog = false }) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (safeZones.isEmpty()) {
                        Text(
                            text = "No safe zones defined yet. Search for locations to add them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(safeZones) { zone ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = zone.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (zone.type.isNotEmpty()) {
                                                Text(
                                                    text = zone.type,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    mapView?.controller?.animateTo(zone.center)
                                                    mapView?.controller?.setZoom(15.0)
                                                    showSafeZonesDialog = false
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.LocationOn,
                                                    "Show on map",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    deleteSafeZoneFromFirestore(zone.id) { success ->
                                                        if (success) {
                                                            safeZones = safeZones.filter { it.id != zone.id }
                                                            refreshSafeZones()
                                                        } else {
                                                            scope.launch {
                                                                android.widget.Toast.makeText(
                                                                    context,
                                                                    "Failed to delete safe zone",
                                                                    android.widget.Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    "Delete",
                                                    tint = MaterialTheme.colorScheme.error
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
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDetach()
        }
    }
}

// Helper function to search locations using Nominatim API
suspend fun searchLocation(query: String): List<SearchResult> {
    return withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=5&addressdetails=1"

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "ChildSafetyApp/1.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                parseSearchResults(response)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("SearchLocation", "Error: ${e.message}")
            emptyList()
        }
    }
}

// Parse JSON response from Nominatim
fun parseSearchResults(jsonResponse: String): List<SearchResult> {
    val results = mutableListOf<SearchResult>()
    try {
        val jsonArray = JSONArray(jsonResponse)
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)

            val boundingBox = if (item.has("boundingbox")) {
                val bbox = item.getJSONArray("boundingbox")
                listOf(
                    bbox.getString(0).toDouble(),
                    bbox.getString(1).toDouble(),
                    bbox.getString(2).toDouble(),
                    bbox.getString(3).toDouble()
                )
            } else null

            results.add(
                SearchResult(
                    displayName = item.getString("display_name"),
                    lat = item.getString("lat").toDouble(),
                    lon = item.getString("lon").toDouble(),
                    boundingBox = boundingBox,
                    type = item.optString("type", ""),
                    osmType = item.optString("osm_type", "")
                )
            )
        }
    } catch (e: Exception) {
        Log.e("ParseResults", "Error parsing: ${e.message}")
    }
    return results
}

// Create circular points for safe zone
fun createCirclePoints(center: GeoPoint, radiusMeters: Double): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    val earthRadius = 6371000.0 // meters

    for (i in 0..360 step 10) {
        val angle = Math.toRadians(i.toDouble())
        val dx = radiusMeters * Math.cos(angle)
        val dy = radiusMeters * Math.sin(angle)

        val deltaLat = dy / earthRadius
        val deltaLon = dx / (earthRadius * Math.cos(Math.toRadians(center.latitude)))

        val newLat = center.latitude + Math.toDegrees(deltaLat)
        val newLon = center.longitude + Math.toDegrees(deltaLon)

        points.add(GeoPoint(newLat, newLon))
    }

    return points
}