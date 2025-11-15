package com.example.child_safety_app_version1.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.child_safety_app_version1.data.AppMode
import com.example.child_safety_app_version1.viewModels.ModeControlViewModel
import com.example.child_safety_app_version1.viewModels.ModeStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class ChildInfo(
    val childId: String,
    val childName: String,
    val childEmail: String
)

@Composable
fun ModeControlScreen() {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val parentUid = auth.currentUser?.uid

    val childrenList = remember { mutableStateOf<List<ChildInfo>>(emptyList()) }
    val selectedChild = remember { mutableStateOf<ChildInfo?>(null) }
    val isLoadingChildren = remember { mutableStateOf(true) }
    val expandedDropdown = remember { mutableStateOf(false) }

    LaunchedEffect(parentUid) {
        if (parentUid != null) {
            try {
                val db = FirebaseFirestore.getInstance()
                val snapshot = db.collection("users")
                    .document(parentUid)
                    .collection("children")
                    .get()
                    .await()

                val children = snapshot.documents.mapNotNull { doc ->
                    val childId = doc.getString("childId")
                    val childName = doc.getString("childName")
                    val childEmail = doc.getString("childEmail")

                    if (childId != null && childName != null && childEmail != null) {
                        ChildInfo(childId, childName, childEmail)
                    } else null
                }

                childrenList.value = children
                if (children.isNotEmpty()) {
                    selectedChild.value = children[0]
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingChildren.value = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
    ) {
        ModeControlHeader()

        Spacer(modifier = Modifier.height(16.dp))

        if (!isLoadingChildren.value) {
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
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedChild.value != null) {
            SelectedChildModeContent(
                childUid = selectedChild.value!!.childId,
                childName = selectedChild.value!!.childName,
                context = context
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isLoadingChildren.value) "Loading children..." else "No children linked yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF757575),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SelectedChildModeContent(
    childUid: String,
    childName: String,
    context: android.content.Context
) {
    val viewModel = remember(childUid) {
        ModeControlViewModel(childUid = childUid, context = context)
    }

    val currentMode by viewModel.currentMode.collectAsState()
    val modeStatus by viewModel.modeStatus.collectAsState()
    val pendingMode by viewModel.pendingMode.collectAsState()

    // Show Study Mode dialog when pending
    if (pendingMode == AppMode.STUDY) {
        StudyModeDialog(viewModel)
    }

    // Main mode selection screen
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        StatusMessageBar(modeStatus)

        Spacer(modifier = Modifier.height(16.dp))

        ModeSelectionSection(
            currentMode = currentMode,
            onModeSelected = { viewModel.initiateMode(it) },
            isLoading = modeStatus is ModeStatus.Loading
        )
    }
}

@Composable
private fun StudyModeDialog(viewModel: ModeControlViewModel) {
    val availableApps by viewModel.availableApps.collectAsState()
    val studyModeAllowedApps by viewModel.studyModeAllowedApps.collectAsState()
    val modeStatus by viewModel.modeStatus.collectAsState()
    val isFetchingApps by viewModel.isFetchingApps.collectAsState()

    Dialog(onDismissRequest = { viewModel.cancelPendingMode() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFF9800))
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "📚 Study Mode Setup",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Select apps allowed during study time",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        IconButton(onClick = { viewModel.cancelPendingMode() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Status message
                if (modeStatus is ModeStatus.Error) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2))
                    ) {
                        Text(
                            text = (modeStatus as ModeStatus.Error).message,
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFFB71C1C)
                        )
                    }
                }

                // Loading state
                if (isFetchingApps) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = Color(0xFFFF9800)
                            )
                            Text(
                                text = "Fetching apps from child device...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFF757575)
                            )
                            Text(
                                text = "This may take a few seconds",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9E9E9E)
                            )
                        }
                    }
                } else {
                    // Selected count
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                    ) {
                        Text(
                            text = "${studyModeAllowedApps.size} apps selected",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFFF9800)
                        )
                    }

                    // App list
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableApps) { app ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.toggleStudyModeApp(app.packageName)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (studyModeAllowedApps.contains(app.packageName))
                                        Color(0xFFFFF3E0) else Color.White
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Checkbox(
                                        checked = studyModeAllowedApps.contains(app.packageName),
                                        onCheckedChange = {
                                            viewModel.toggleStudyModeApp(app.packageName)
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.appName,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF9E9E9E)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Finalize button
                Button(
                    onClick = { viewModel.finalizeStudyMode() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    enabled = modeStatus !is ModeStatus.Loading && !isFetchingApps && studyModeAllowedApps.isNotEmpty()
                ) {
                    if (modeStatus is ModeStatus.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        text = when {
                            isFetchingApps -> "Loading Apps..."
                            studyModeAllowedApps.isEmpty() -> "Select at least one app"
                            else -> "Activate Study Mode"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeControlHeader() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF7B1FA2)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Device Modes",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Control device access modes",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun StatusMessageBar(status: ModeStatus) {
    when (status) {
        is ModeStatus.Success -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFC8E6C9)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF388E3C)
                    )
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1B5E20)
                    )
                }
            }
        }

        is ModeStatus.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFFD32F2F)
                    )
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB71C1C)
                    )
                }
            }
        }

        else -> {}
    }
}

@Composable
private fun ModeSelectionSection(
    currentMode: AppMode,
    onModeSelected: (AppMode) -> Unit,
    isLoading: Boolean
) {
    Text(
        text = "Select Device Mode",
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF212121),
        modifier = Modifier.padding(bottom = 12.dp)
    )

    val modes = listOf(AppMode.NORMAL, AppMode.STUDY, AppMode.BEDTIME)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        modes.forEach { mode ->
            ModeCard(
                mode = mode,
                isSelected = currentMode == mode,
                onClick = { onModeSelected(mode) },
                isLoading = isLoading
            )
        }
    }
}

@Composable
private fun ModeCard(
    mode: AppMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isLoading) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF7B1FA2).copy(alpha = 0.1f)
            else Color.White
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF7B1FA2))
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E0E0))
        },
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = mode.icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = Color(0xFF7B1FA2)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = mode.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF212121)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF757575),
                    modifier = Modifier.padding(start = 40.dp)
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    modifier = Modifier.size(28.dp),
                    tint = Color(0xFF7B1FA2)
                )
            }
        }
    }
}