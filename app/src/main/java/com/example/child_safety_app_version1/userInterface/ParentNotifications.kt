package com.example.child_safety_app_version1.userInterface

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun ParentNotificationsScreen() {
    val db = FirebaseFirestore.getInstance()
    val currentUser = FirebaseAuth.getInstance().currentUser
    var notifications by remember { mutableStateOf(listOf<Map<String, Any>>()) }

    LaunchedEffect(currentUser?.uid) {
        currentUser?.uid?.let { uid ->
            db.collection("users").document(uid)
                .collection("notifications")
                .orderBy("timestamp")
                .addSnapshotListener { snapshot, error ->
                    if (snapshot != null) {
                        notifications = snapshot.documents.map { it.data!! }
                    }
                }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Notifications", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))

        val context = LocalContext.current

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notifications) { notif ->
                val title = notif["title"] as? String ?: ""
                val message = notif["message"] as? String ?: ""
                val lat = (notif["latitude"] as? Double)
                val lng = (notif["longitude"] as? Double)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (lat != null && lng != null) {
                                // Open Google Maps if location exists
                                val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                intent.setPackage("com.google.android.apps.maps")
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent) // Use context instead of it.context
                            }
                        },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
