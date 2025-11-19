package com.example.child_safety_app_version1.userInterface

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class UninstallConsentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                UninstallConsentDialog(
                    onCancel = { finish() },
                    onRequestConsent = { createUninstallRequest() }
                )
            }
        }
    }

    private fun createUninstallRequest() {
        val childUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()
        val scope = this.lifecycleScope

        scope.launch {
            try {
                val parentDocs = db.collection("users")
                    .document(childUid)
                    .collection("parents")
                    .get()
                    .await()

                if (parentDocs.isEmpty) {
                    Toast.makeText(this@UninstallConsentActivity, "No parents linked", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                val approvals = mutableMapOf<String, String>()
                val parentIds = mutableListOf<String>()

                parentDocs.documents.forEach { doc ->
                    val pid = doc.getString("parentId") ?: return@forEach
                    approvals[pid] = "pending"
                    parentIds.add(pid)
                }

                val reqRef = db.collection("users")
                    .document(childUid)
                    .collection("uninstallRequests")
                    .document()

                val req = mapOf(
                    "requestId" to reqRef.id,
                    "childUid" to childUid,
                    "status" to "pending",
                    "approvals" to approvals,
                    "createdAt" to System.currentTimeMillis()
                )

                reqRef.set(req).await()

                parentIds.forEach { parentId ->
                    db.collection("users")
                        .document(parentId)
                        .collection("notifications")
                        .add(
                            mapOf(
                                "title" to "Uninstall Request",
                                "body" to "Your child wants to uninstall the app.",
                                "type" to "UNINSTALL_REQUEST",
                                "childUid" to childUid,
                                "requestId" to reqRef.id,
                                "timestamp" to System.currentTimeMillis(),
                                "read" to false
                            )
                        )
                }

                Toast.makeText(this@UninstallConsentActivity, "Request sent to parents", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(this@UninstallConsentActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }
}

@Composable
fun UninstallConsentDialog(
    onCancel: () -> Unit,
    onRequestConsent: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Uninstall App") },
        text = { Text("This app can be uninstalled only with parent permission.") },
        confirmButton = {
            Button(onClick = onRequestConsent) {
                Text("Request Consent")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
