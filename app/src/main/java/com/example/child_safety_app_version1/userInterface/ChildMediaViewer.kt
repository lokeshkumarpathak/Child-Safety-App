package com.example.child_safety_app_version1.userInterface

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// =======================================================
// DATA CLASS
// =======================================================
data class ChildMediaRecord(
    val id: String,
    val childUid: String,
    val fileName: String,
    val fileType: String,
    val remotePath: String,
    val latitude: Double?,
    val longitude: Double?,
    val locationMethod: String?,
    val fileSizeBytes: Long?,
    val createdAt: Long,
    val status: String = "uploaded"
) {
    // Mutable properties for download state
    var localFilePath: String? = null
    var downloadStatus: String = "pending" // pending, downloading, downloaded, failed
}

// =======================================================
// MAIN VIEWER
// =======================================================
// Modified ChildMediaViewer with smart preloading
@Composable
fun ChildMediaViewer(
    childUid: String,
    childName: String
) {
    val ctx = LocalContext.current
    var mediaList by remember { mutableStateOf<List<ChildMediaRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedMedia by remember { mutableStateOf<ChildMediaRecord?>(null) }

    // Use a state map to force UI updates
    // Use a state map to force UI updates
    var downloadStates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    // Load on open and preload audio files
    LaunchedEffect(childUid) {
        scope.launch {
            isLoading = true
            try {
                val records = fetchChildMediaRecords(childUid)

                // Check which audio files already exist locally
                val processedRecords = records.map { media ->
                    if (media.fileType == "audio") {
                        checkLocalFile(media, ctx)
                    } else {
                        media
                    }
                }

                mediaList = processedRecords.sortedByDescending { it.createdAt }

                // ADD THIS LINE HERE ⬇️⬇️⬇️
                downloadStates = mediaList.associate { it.id to it.downloadStatus }

                // Preload only NEW audio files (not already downloaded)
                val audioToPreload = mediaList.filter {
                    it.fileType == "audio" && it.downloadStatus == "pending"
                }

                val alreadyDownloaded = mediaList.count {
                    it.fileType == "audio" && it.downloadStatus == "downloaded"
                }

                Log.d("ChildMediaViewer", "Initial load: Total items=${mediaList.size}")
                Log.d("ChildMediaViewer", "Audio files already downloaded: $alreadyDownloaded")
                Log.d("ChildMediaViewer", "Audio files to download: ${audioToPreload.size}")

                audioToPreload.forEach { media ->
                    launch {
                        preloadAudioFile(media, ctx) { updatedMedia ->
                            // Update the media list with downloaded file info
                            mediaList = mediaList.map {
                                if (it.id == updatedMedia.id) updatedMedia else it
                            }
                            // ADD THIS LINE HERE ⬇️⬇️⬇️
                            downloadStates = downloadStates + (updatedMedia.id to updatedMedia.downloadStatus)
                        }
                    }
                }
            } catch (e: Exception) {
                error = "Failed to load media: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    if (selectedMedia != null) {
        MediaDetailScreen(
            media = selectedMedia!!,
            onClose = { selectedMedia = null },
            onDelete = { media ->
                scope.launch {
                    val success = deleteMediaRecord(media)
                    if (success) {
                        mediaList = mediaList.filter { it.id != media.id }
                        selectedMedia = null
                    }
                }
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        // =======================
        // MODERN HEADER SECTION
        // =======================
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = childName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Media Alerts",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }

                    // REFRESH ICON BUTTON
                    IconButton(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                error = null
                                try {
                                    Log.d("ChildMediaViewer", "===== REFRESH STARTED =====")
                                    val records = fetchChildMediaRecords(childUid)
                                    Log.d("ChildMediaViewer", "Fetched ${records.size} records from server")

                                    // Check which audio files already exist locally
                                    val processedRecords = records.map { media ->
                                        if (media.fileType == "audio") {
                                            checkLocalFile(media, ctx)
                                        } else {
                                            media
                                        }
                                    }

                                    mediaList = processedRecords.sortedByDescending { it.createdAt }

                                    // Update download states immediately
                                    downloadStates = mediaList.associate { it.id to it.downloadStatus }
                                    Log.d("ChildMediaViewer", "Updated mediaList with ${mediaList.size} items")

                                    // Preload only NEW audio files
                                    val audioToPreload = mediaList.filter {
                                        it.fileType == "audio" && it.downloadStatus == "pending"
                                    }

                                    val alreadyDownloaded = mediaList.count {
                                        it.fileType == "audio" && it.downloadStatus == "downloaded"
                                    }

                                    Log.d("ChildMediaViewer", "Refresh: Audio already downloaded: $alreadyDownloaded")
                                    Log.d("ChildMediaViewer", "Refresh: Audio to download: ${audioToPreload.size}")

                                    if (audioToPreload.isNotEmpty()) {
                                        Log.d("ChildMediaViewer", "Starting downloads for ${audioToPreload.size} files...")
                                        audioToPreload.forEach { media ->
                                            Log.d("ChildMediaViewer", "Launching download for: ${media.fileName}")
                                            launch {
                                                preloadAudioFile(media, ctx) { updatedMedia ->
                                                    Log.d("ChildMediaViewer", "Download callback for ${updatedMedia.fileName}: ${updatedMedia.downloadStatus}")
                                                    mediaList = mediaList.map {
                                                        if (it.id == updatedMedia.id) updatedMedia else it
                                                    }
                                                    downloadStates = downloadStates + (updatedMedia.id to updatedMedia.downloadStatus)
                                                }
                                            }
                                        }
                                    } else {
                                        Log.d("ChildMediaViewer", "No new files to download")
                                    }

                                    Log.d("ChildMediaViewer", "===== REFRESH COMPLETED =====")
                                } catch (e: Exception) {
                                    Log.e("ChildMediaViewer", "Refresh failed with exception", e)
                                    error = "Refresh failed: ${e.message}"
                                } finally {
                                    delay(500) // Smooth animation
                                    isRefreshing = false
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(24.dp)
                                .then(
                                    if (isRefreshing) Modifier else Modifier
                                )
                        )
                    }
                }

                // Media count
                if (!isLoading && mediaList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${mediaList.size} ${if (mediaList.size == 1) "item" else "items"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // =======================
        // CONTENT AREA
        // =======================

        // Error banner
        androidx.compose.animation.AnimatedVisibility(
            visible = error != null,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Loading media...", color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }

                mediaList.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "No media alerts yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                "Media captured by the child device will appear here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(mediaList, key = { it.id }) { media ->
                            val currentDownloadState = downloadStates[media.id] ?: media.downloadStatus
                            MediaCard(
                                media = media,
                                currentDownloadState = currentDownloadState,
                                onClick = { selectedMedia = media }
                            )
                        }
                    }
                }
            }

            // Refreshing overlay
            if (isRefreshing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

// =======================================================
// ENHANCED MEDIA CARD
// =======================================================
@Composable
private fun MediaCard(
    media: ChildMediaRecord,
    currentDownloadState: String,
    onClick: () -> Unit
) {
    val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(media.createdAt))
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(media.createdAt))
    val sizeKB = (media.fileSizeBytes ?: 0) / 1024

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // Icon with background and download indicator
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (media.fileType == "video")
                            Color(0xFFFFEBEE)
                        else
                            Color(0xFFE0F7FA)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (media.fileType == "video") Icons.Default.Videocam else Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (media.fileType == "video") Color(0xFFD32F2F) else Color(0xFF00ACC1),
                    modifier = Modifier.size(32.dp)
                )

                // Show download status for audio
                if (media.fileType == "audio") {
                    when (media.downloadStatus) {
                        "downloading" -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF00ACC1)
                            )
                        }
                        "downloaded" -> {
                            // Small checkmark badge
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(18.dp)
                                    .background(Color(0xFF4CAF50), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(16.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = media.fileType.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (media.fileType == "video") Color(0xFFD32F2F) else Color(0xFF00ACC1),
                        fontWeight = FontWeight.Bold
                    )

                    // "Ready" badge for downloaded audio
                    if (media.fileType == "audio" && media.downloadStatus == "downloaded") {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFF4CAF50).copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "READY",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = media.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "$date • $time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (media.latitude != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Location available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Size badge
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "$sizeKB KB",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

// =======================================================
// ENHANCED MEDIA DETAIL WITH IN-APP PLAYER
// =======================================================
@Composable
private fun MediaDetailScreen(
    media: ChildMediaRecord,
    onClose: () -> Unit,
    onDelete: (ChildMediaRecord) -> Unit
) {
    val ctx = LocalContext.current
    var mediaUrl by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Load public URL for videos
    LaunchedEffect(media) {
        loading = true
        if (media.fileType == "video") {
            mediaUrl = getPublicMediaUrl(media.remotePath)
        }
        Log.d("MediaDetailScreen", "Media URL: $mediaUrl")
        loading = false
    }

    // Cleanup media player
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {

        // HEADER
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp
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
                        text = media.fileType.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = media.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = {
                    mediaPlayer?.release()
                    onClose()
                }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        // CONTENT AREA
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Spacer(Modifier.weight(1f))

            // Media Icon
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        if (media.fileType == "video")
                            Color(0xFFFFEBEE)
                        else
                            Color(0xFFE0F7FA)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (media.fileType == "video") Icons.Default.Videocam else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = if (media.fileType == "video") Color(0xFFD32F2F) else Color(0xFF00ACC1)
                )
            }

            Spacer(Modifier.height(32.dp))

            // Info Cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoCard(
                    icon = Icons.Default.CalendarToday,
                    label = "Recorded",
                    value = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                        .format(Date(media.createdAt))
                )

                InfoCard(
                    icon = Icons.Default.Storage,
                    label = "Size",
                    value = "${(media.fileSizeBytes ?: 0) / 1024} KB"
                )

                if (media.latitude != null) {
                    InfoCard(
                        icon = Icons.Default.LocationOn,
                        label = "Location",
                        value = "${String.format("%.4f", media.latitude)}, ${String.format("%.4f", media.longitude)}"
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Error message if any
            if (errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage ?: "",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // PLAY BUTTON
            Button(
                onClick = {
                    if (media.fileType == "video") {
                        mediaUrl?.let { url ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(url), "video/*")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e("MediaDetailScreen", "Play video failed", e)
                                errorMessage = "Failed to play video: ${e.message}"
                            }
                        }
                    } else {
                        // Audio - play from already downloaded file
                        if (media.localFilePath == null) {
                            errorMessage = "Audio file not downloaded yet"
                            return@Button
                        }

                        scope.launch {
                            try {
                                if (isPlaying) {
                                    // Pause audio
                                    Log.d("MediaPlayer", "Pausing audio")
                                    mediaPlayer?.pause()
                                    isPlaying = false
                                } else {
                                    // Play audio from local file
                                    if (mediaPlayer == null) {
                                        Log.d("MediaPlayer", "Playing from local file: ${media.localFilePath}")

                                        mediaPlayer = MediaPlayer().apply {
                                            setAudioAttributes(
                                                AudioAttributes.Builder()
                                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                                    .build()
                                            )

                                            setOnPreparedListener {
                                                Log.d("MediaPlayer", "Media prepared, starting playback")
                                                start()
                                                isPlaying = true
                                            }

                                            setOnCompletionListener {
                                                Log.d("MediaPlayer", "Playback completed")
                                                isPlaying = false
                                            }

                                            setOnErrorListener { mp, what, extra ->
                                                Log.e("MediaPlayer", "Error occurred: what=$what, extra=$extra")
                                                errorMessage = "Playback error: $what"
                                                isPlaying = false
                                                true
                                            }

                                            try {
                                                setDataSource(media.localFilePath)
                                                prepareAsync()
                                                Log.d("MediaPlayer", "Preparing audio from local file...")
                                            } catch (e: Exception) {
                                                Log.e("MediaPlayer", "Error setting data source", e)
                                                errorMessage = "Failed to load audio: ${e.message}"
                                            }
                                        }
                                    } else {
                                        Log.d("MediaPlayer", "Resuming existing MediaPlayer")
                                        mediaPlayer?.start()
                                        isPlaying = true
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MediaPlayer", "Error in play logic", e)
                                errorMessage = "Error: ${e.message}"
                                isPlaying = false
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    if (media.fileType == "audio" && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (media.fileType == "audio" && isPlaying) "Pause" else "Play",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.height(12.dp))

            // DELETE BUTTON
            OutlinedButton(
                onClick = {
                    mediaPlayer?.release()
                    onDelete(media)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Delete", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
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
}

// =======================================================
// NETWORK HELPERS: FETCH + DELETE
// =======================================================

private suspend fun fetchChildMediaRecords(childUid: String): List<ChildMediaRecord> {
    return withContext(Dispatchers.IO) {
        val supabaseUrl = "https://qcqdpnsetwljcflwlxex.supabase.co"
        val serviceKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFjcWRwbnNldHdsamNmbHdseGV4Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc2MzIxODQyOSwiZXhwIjoyMDc4Nzk0NDI5fQ.MYm70X_aoHLSw_CuL3wfeL-YAaAY61yXeVaXAPv11wI"
        val url = "$supabaseUrl/rest/v1/child_media?child_uid=eq.$childUid&order=created_at.desc"

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $serviceKey")
            .addHeader("apikey", serviceKey)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (response.code != 200) return@withContext emptyList()

        val body = response.body?.string() ?: "[]"
        val arr = JSONArray(body)

        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ChildMediaRecord(
                id = o.getString("id"),
                childUid = o.getString("child_uid"),
                fileName = o.getString("file_name"),
                fileType = o.getString("file_type"),
                remotePath = o.getString("remote_path"),
                latitude = if (o.isNull("latitude")) null else o.getDouble("latitude"),
                longitude = if (o.isNull("longitude")) null else o.getDouble("longitude"),
                locationMethod = o.optString("location_method", null),
                fileSizeBytes = if (o.isNull("file_size_bytes")) null else o.getLong("file_size_bytes"),
                createdAt = o.getLong("created_at")
            )
        }
    }
}

private suspend fun deleteMediaRecord(media: ChildMediaRecord): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val supabaseUrl = "https://qcqdpnsetwljcflwlxex.supabase.co"
            val serviceKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InFjcWRwbnNldHdsamNmbHdseGV4Iiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc2MzIxODQyOSwiZXhwIjoyMDc4Nzk0NDI5fQ.MYm70X_aoHLSw_CuL3wfeL-YAaAY61yXeVaXAPv11wI"

            val client = OkHttpClient()

            // 1) DELETE FILE FROM STORAGE
            val fileUrl = "$supabaseUrl/storage/v1/object/child_safety_media/${media.remotePath}"
            val deleteFileReq = Request.Builder()
                .url(fileUrl)
                .delete()
                .addHeader("Authorization", "Bearer $serviceKey")
                .addHeader("apikey", serviceKey)
                .build()

            client.newCall(deleteFileReq).execute()

            // 2) DELETE ROW FROM DATABASE
            val metadataUrl = "$supabaseUrl/rest/v1/child_media?id=eq.${media.id}"
            val deleteMetaReq = Request.Builder()
                .url(metadataUrl)
                .delete()
                .addHeader("Authorization", "Bearer $serviceKey")
                .addHeader("apikey", serviceKey)
                .addHeader("Prefer", "return=minimal")
                .build()

            val metaResp = client.newCall(deleteMetaReq).execute()
            return@withContext metaResp.code in listOf(200, 204)

        } catch (e: Exception) {
            Log.e("DeleteMedia", "Error deleting", e)
            false
        }
    }
}

private fun getPublicMediaUrl(remotePath: String): String {
    val supabaseUrl = "https://qcqdpnsetwljcflwlxex.supabase.co"
    // ⚠️ IMPORTANT: Make sure this bucket name matches your Supabase Storage bucket name exactly
    val bucketName = "child_safety_media" // Change this if your bucket has a different name
    return "$supabaseUrl/storage/v1/object/public/$bucketName/$remotePath"
}

// =======================================================
// PRELOAD AUDIO FILE IN BACKGROUND
// =======================================================
// Modified preloadAudioFile to check for existing files first
private suspend fun preloadAudioFile(
    media: ChildMediaRecord,
    context: android.content.Context,
    onUpdate: (ChildMediaRecord) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            // Check if file already exists locally
            val cacheDir = context.cacheDir
            val audioDir = File(cacheDir, "audio_cache")
            val localFile = File(audioDir, media.fileName)

            if (localFile.exists() && localFile.length() > 0) {
                Log.d("PreloadAudio", "========================================")
                Log.d("PreloadAudio", "✅ File already exists locally!")
                Log.d("PreloadAudio", "File: ${media.fileName}")
                Log.d("PreloadAudio", "Path: ${localFile.absolutePath}")
                Log.d("PreloadAudio", "Size: ${localFile.length()} bytes")
                Log.d("PreloadAudio", "Skipping download...")
                Log.d("PreloadAudio", "========================================")

                media.localFilePath = localFile.absolutePath
                media.downloadStatus = "downloaded"
                withContext(Dispatchers.Main) {
                    onUpdate(media)
                }
                return@withContext
            }

            Log.d("PreloadAudio", "========================================")
            Log.d("PreloadAudio", "Starting preload process (file not in cache)")
            Log.d("PreloadAudio", "File: ${media.fileName}")
            Log.d("PreloadAudio", "Media ID: ${media.id}")
            Log.d("PreloadAudio", "Remote Path: ${media.remotePath}")

            // Update status to downloading
            media.downloadStatus = "downloading"
            withContext(Dispatchers.Main) {
                onUpdate(media)
            }
            Log.d("PreloadAudio", "Status updated to: downloading")

            val url = getPublicMediaUrl(media.remotePath)
            Log.d("PreloadAudio", "Generated URL: $url")

            Log.d("PreloadAudio", "Creating OkHttp client with 30s timeout...")
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            Log.d("PreloadAudio", "Building HTTP request...")
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            Log.d("PreloadAudio", "Executing HTTP request...")
            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val requestTime = System.currentTimeMillis() - startTime

            Log.d("PreloadAudio", "Response received in ${requestTime}ms")
            Log.d("PreloadAudio", "Response code: ${response.code}")
            Log.d("PreloadAudio", "Response message: ${response.message}")
            Log.d("PreloadAudio", "Content-Type: ${response.header("Content-Type")}")
            Log.d("PreloadAudio", "Content-Length: ${response.header("Content-Length")} bytes")

            if (!response.isSuccessful) {
                Log.e("PreloadAudio", "❌ Download FAILED!")
                Log.e("PreloadAudio", "HTTP ${response.code}: ${response.message}")
                Log.e("PreloadAudio", "Response body: ${response.body?.string()}")
                media.downloadStatus = "failed"
                withContext(Dispatchers.Main) {
                    onUpdate(media)
                }
                return@withContext
            }

            Log.d("PreloadAudio", "✅ Response successful, preparing to save file...")

            // Create audio cache directory if it doesn't exist
            Log.d("PreloadAudio", "Cache directory: ${cacheDir.absolutePath}")

            if (!audioDir.exists()) {
                val created = audioDir.mkdirs()
                Log.d("PreloadAudio", "Audio cache directory created: $created")
            }
            Log.d("PreloadAudio", "Audio cache directory: ${audioDir.absolutePath}")
            Log.d("PreloadAudio", "Target file path: ${localFile.absolutePath}")

            // Write to file
            Log.d("PreloadAudio", "Starting file write...")
            val writeStartTime = System.currentTimeMillis()
            var bytesWritten = 0L

            response.body?.byteStream()?.use { input ->
                FileOutputStream(localFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesWritten += bytes

                        // Log progress every 100KB
                        if (bytesWritten % (100 * 1024) == 0L) {
                            Log.d("PreloadAudio", "Progress: ${bytesWritten / 1024}KB written...")
                        }

                        bytes = input.read(buffer)
                    }
                }
            }

            val writeTime = System.currentTimeMillis() - writeStartTime
            Log.d("PreloadAudio", "File write completed in ${writeTime}ms")
            Log.d("PreloadAudio", "Total bytes written: $bytesWritten (${bytesWritten / 1024}KB)")
            Log.d("PreloadAudio", "File exists: ${localFile.exists()}")
            Log.d("PreloadAudio", "File size: ${localFile.length()} bytes")

            // Update media record with local file path
            media.localFilePath = localFile.absolutePath
            media.downloadStatus = "downloaded"

            withContext(Dispatchers.Main) {
                onUpdate(media)
            }

            Log.d("PreloadAudio", "✅ SUCCESS! Audio preloaded successfully")
            Log.d("PreloadAudio", "Local file: ${localFile.absolutePath}")
            Log.d("PreloadAudio", "Total time: ${System.currentTimeMillis() - startTime}ms")
            Log.d("PreloadAudio", "========================================")

        } catch (e: Exception) {
            Log.e("PreloadAudio", "========================================")
            Log.e("PreloadAudio", "❌ EXCEPTION occurred during preload")
            Log.e("PreloadAudio", "File: ${media.fileName}")
            Log.e("PreloadAudio", "Exception type: ${e.javaClass.simpleName}")
            Log.e("PreloadAudio", "Exception message: ${e.message}")
            Log.e("PreloadAudio", "Stack trace:", e)
            Log.e("PreloadAudio", "========================================")

            media.downloadStatus = "failed"
            withContext(Dispatchers.Main) {
                onUpdate(media)
            }
        }
    }
}

// =======================================================
// DOWNLOAD AUDIO FILE FOR LOCAL PLAYBACK (Legacy - kept for compatibility)
// =======================================================
private suspend fun downloadAudioFile(url: String, fileName: String, context: android.content.Context): File? {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e("DownloadAudio", "Failed to download: ${response.code}")
                return@withContext null
            }

            // Create temp file in cache directory
            val cacheDir = context.cacheDir
            val tempFile = File(cacheDir, fileName)

            // Write to file
            response.body?.byteStream()?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d("DownloadAudio", "Successfully downloaded to: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e("DownloadAudio", "Error downloading file", e)
            null
        }
    }
}

// Add this helper function to check if file exists locally
private fun checkLocalFile(media: ChildMediaRecord, context: android.content.Context): ChildMediaRecord {
    val cacheDir = context.cacheDir
    val audioDir = File(cacheDir, "audio_cache")
    val localFile = File(audioDir, media.fileName)

    if (localFile.exists() && localFile.length() > 0) {
        Log.d("CheckLocalFile", "Found existing file: ${localFile.absolutePath} (${localFile.length()} bytes)")
        media.localFilePath = localFile.absolutePath
        media.downloadStatus = "downloaded"
    } else {
        Log.d("CheckLocalFile", "File not found or empty: ${localFile.absolutePath}")
        media.downloadStatus = "pending"
    }

    return media
}