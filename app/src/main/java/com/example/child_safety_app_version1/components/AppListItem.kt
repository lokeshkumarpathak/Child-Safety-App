package com.example.child_safety_app_version1.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.child_safety_app_version1.data.AppUsageInfo
import com.example.child_safety_app_version1.utils.UsageStatsHelper

@Composable
fun AppListItem(
    app: AppUsageInfo,
    isBlocked: Boolean = false,
    onToggleBlocked: () -> Unit
) {
    // ⭐ PARENT APP CHECK
    val PARENT_APP_PACKAGE = "com.example.child_safety_app_version1"
    val isParentApp = app.packageName == PARENT_APP_PACKAGE

    var showConfirmDialog by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { isHovered = !isHovered }
            .background(
                color = when {
                    isParentApp -> Color(0xFFFFF3E0) // Orange tint for parent app
                    isBlocked -> Color(0xFFFFEBEE)
                    else -> Color.White
                }
            ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isHovered) 4.dp else 1.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isParentApp -> Color(0xFFFFF3E0)
                isBlocked -> Color(0xFFFFEBEE)
                else -> Color.White
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // App Icon and Name
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Placeholder icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isParentApp) Color(0xFFFF9800).copy(alpha = 0.2f)
                            else Color(0xFF1976D2).copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (isParentApp) Color(0xFFFF9800) else Color(0xFF1976D2)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // App Name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                isParentApp -> Color(0xFFFF9800)
                                isBlocked -> Color(0xFFD32F2F)
                                else -> Color(0xFF212121)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // ⭐ Parent App Badge
                        if (isParentApp) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PARENT",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier
                                    .background(
                                        color = Color(0xFFFF9800),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Package name (small)
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9E9E9E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Usage duration and last used
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Duration
                        Text(
                            text = "⏱️ ${UsageStatsHelper.formatDuration(app.totalTimeMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                isParentApp -> Color(0xFFFF9800)
                                isBlocked -> Color(0xFFD32F2F)
                                else -> Color(0xFF1976D2)
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when {
                                        isParentApp -> Color(0xFFFF9800).copy(alpha = 0.1f)
                                        isBlocked -> Color(0xFFFFCDD2)
                                        else -> Color(0xFF1976D2).copy(alpha = 0.1f)
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )

                        // Last used
                        Text(
                            text = "Last: ${getTimeAgoString(app.lastUsed)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF757575)
                        )
                    }
                }
            }

            // Block/Unblock Toggle Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(60.dp)
            ) {
                // ⭐ DISABLE BUTTON FOR PARENT APP
                IconButton(
                    onClick = {
                        if (!isParentApp) {
                            showConfirmDialog = true
                        }
                    },
                    enabled = !isParentApp, // ⭐ KEY CHANGE
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            when {
                                isParentApp -> Color(0xFFE0E0E0) // Grey for parent app
                                isBlocked -> Color(0xFFFFCDD2)
                                else -> Color(0xFFC8E6C9)
                            }
                        )
                        .size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = when {
                            isParentApp -> Color(0xFF9E9E9E)
                            isBlocked -> Color(0xFFD32F2F)
                            else -> Color(0xFF388E3C)
                        },
                        disabledContentColor = Color(0xFF9E9E9E)
                    )
                ) {
                    Icon(
                        imageVector = when {
                            isParentApp -> Icons.Default.Block // Lock icon for parent app
                            isBlocked -> Icons.Default.Block
                            else -> Icons.Default.CheckCircle
                        },
                        contentDescription = when {
                            isParentApp -> "Protected"
                            isBlocked -> "Blocked"
                            else -> "Allowed"
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = when {
                        isParentApp -> "Protected"
                        isBlocked -> "Blocked"
                        else -> "Allowed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isParentApp -> Color(0xFF9E9E9E)
                        isBlocked -> Color(0xFFD32F2F)
                        else -> Color(0xFF388E3C)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    // Confirmation Dialog (only show if not parent app)
    if (showConfirmDialog && !isParentApp) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(
                    text = if (isBlocked) "Unblock App?" else "Block App?",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = if (isBlocked) {
                        "Allow '${app.appName}' to be used on the child's device?"
                    } else {
                        "Block '${app.appName}' from the child's device?"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onToggleBlocked()
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBlocked) Color(0xFF4CAF50) else Color(0xFFD32F2F)
                    )
                ) {
                    Text(if (isBlocked) "Unblock" else "Block")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showConfirmDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Convert timestamp to "X minutes/hours/days ago" format
 */
private fun getTimeAgoString(timestamp: Long): String {
    if (timestamp == 0L) return "Never"

    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} hour${if (diff / 3_600_000 > 1) "s" else ""} ago"
        diff < 604_800_000 -> "${diff / 86_400_000} day${if (diff / 86_400_000 > 1) "s" else ""} ago"
        else -> "Long ago"
    }
}