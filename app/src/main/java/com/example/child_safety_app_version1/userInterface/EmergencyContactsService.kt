package com.example.child_safety_app_version1.userInterface

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.*
import android.util.Base64
import com.example.child_safety_app_version1.utils.EmailOtpHelper
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class EmergencyContact(
    val id: String = "",
    val name: String = "",
    val phoneNumberHash: String = "",
    val phoneNumberEncrypted: String = "",
    val email: String = "",
    val displayNumber: String = "",
    val type: String = "",
    val verified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

object PhoneEncryption {
    private const val SECRET_KEY = "MySecretKey12345"

    fun encrypt(data: String): String {
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            val secretKey = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encryptedBytes = cipher.doFinal(data.toByteArray())
            return Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun decrypt(encryptedData: String): String {
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            val secretKey = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedBytes = cipher.doFinal(Base64.decode(encryptedData, Base64.DEFAULT))
            return String(decryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyContactsScreen(navController: NavController) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val currentUser = auth.currentUser
    val scope = rememberCoroutineScope()

    var contacts by remember { mutableStateOf<List<EmergencyContact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Call permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(currentUser?.uid) {
        if (currentUser != null) {
            loadContacts(db, currentUser.uid) { loadedContacts ->
                contacts = loadedContacts
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emergency Contacts") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "Add Contact")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                contacts.isEmpty() -> {
                    EmptyContactsView(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(contacts) { contact ->
                            ContactCard(
                                contact = contact,
                                onCall = { encryptedPhone ->
                                    val actualPhone = PhoneEncryption.decrypt(encryptedPhone)
                                    makePhoneCall(context, actualPhone, contact.name, callPermissionLauncher)
                                },
                                onDelete = {
                                    scope.launch {
                                        deleteContact(db, currentUser!!.uid, contact.id)
                                        loadContacts(db, currentUser.uid) { contacts = it }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AddContactDialog(
                onDismiss = { showAddDialog = false },
                onContactAdded = {
                    scope.launch {
                        loadContacts(db, currentUser!!.uid) { contacts = it }
                    }
                }
            )
        }
    }
}

@Composable
fun ContactCard(
    contact: EmergencyContact,
    onCall: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (contact.type == "person") Icons.Default.Person else Icons.Default.Business,
                contentDescription = contact.type,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "****${contact.displayNumber}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (contact.email.isNotEmpty()) {
                    Text(
                        text = contact.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (contact.type == "person") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (contact.verified) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (contact.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (contact.verified) "Email Verified" else "Not Verified",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (contact.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            FilledTonalButton(
                onClick = { onCall(contact.phoneNumberEncrypted) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Call, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Call")
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Contact") },
            text = { Text("Are you sure you want to delete ${contact.name}?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onContactAdded: () -> Unit
) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val scope = rememberCoroutineScope()

    var contactType by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var generatedOtp by remember { mutableStateOf("") }
    var showOtpField by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Emergency Contact") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    contactType == null -> {
                        Text("Select contact type:", fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { contactType = "person" },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Person, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Person")
                            }
                            Button(
                                onClick = { contactType = "organization" },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Business, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Organization")
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !showOtpField
                        )

                        OutlinedTextField(
                            value = phoneNumber,
                            onValueChange = { phoneNumber = it },
                            label = { Text("Phone Number") },
                            placeholder = { Text("+1234567890") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            enabled = !showOtpField,
                            supportingText = { Text("Required for emergency calls") }
                        )

                        if (contactType == "person") {
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Email Address") },
                                placeholder = { Text("contact@example.com") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                                enabled = !showOtpField,
                                supportingText = { Text("We'll send a verification code here") }
                            )

                            if (showOtpField) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "OTP sent to $email",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = otp,
                                    onValueChange = { if (it.length <= 6) otp = it },
                                    label = { Text("Enter 6-digit OTP") },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    supportingText = { Text("Check your email for the verification code") }
                                )
                            }
                        }

                        if (errorMessage != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    text = errorMessage!!,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        errorMessage = null

                        when {
                            contactType == null -> {
                                errorMessage = "Please select contact type"
                            }
                            name.isBlank() -> {
                                errorMessage = "Please enter a name"
                            }
                            phoneNumber.isBlank() -> {
                                errorMessage = "Please enter a phone number"
                            }
                            !phoneNumber.matches(Regex("^\\+?[1-9]\\d{1,14}$")) -> {
                                errorMessage = "Please enter a valid phone number with country code"
                            }
                            contactType == "person" && email.isBlank() -> {
                                errorMessage = "Please enter an email address"
                            }
                            contactType == "person" && !email.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) -> {
                                errorMessage = "Please enter a valid email address"
                            }
                            contactType == "person" && !showOtpField -> {
                                // Check for duplicate phone or email
                                val phoneHash = hashPhoneNumber(phoneNumber)

                                val phoneCheck = db.collection("users")
                                    .document(auth.currentUser!!.uid)
                                    .collection("contacts")
                                    .whereEqualTo("phoneNumberHash", phoneHash)
                                    .get()
                                    .await()

                                val emailCheck = db.collection("users")
                                    .document(auth.currentUser!!.uid)
                                    .collection("contacts")
                                    .whereEqualTo("email", email)
                                    .get()
                                    .await()

                                when {
                                    !phoneCheck.isEmpty && !emailCheck.isEmpty -> {
                                        errorMessage = "This phone number and email are already added as an emergency contact"
                                    }
                                    !phoneCheck.isEmpty -> {
                                        errorMessage = "This phone number is already added as an emergency contact"
                                    }
                                    !emailCheck.isEmpty -> {
                                        errorMessage = "This email is already added as an emergency contact"
                                    }
                                    else -> {
                                        // No duplicates, generate OTP and send email
                                        generatedOtp = generateOtp()

                                        EmailOtpHelper.sendOtpEmail(
                                            toEmail = email,
                                            contactName = name,
                                            otp = generatedOtp,
                                            onSuccess = {
                                                showOtpField = true
                                                Toast.makeText(context, "OTP sent to $email", Toast.LENGTH_SHORT).show()
                                            },
                                            onFailure = { error ->
                                                errorMessage = "Failed to send email: $error"
                                            }
                                        )
                                    }
                                }
                            }
                            contactType == "person" && showOtpField -> {
                                // Verify OTP
                                if (otp == generatedOtp) {
                                    saveContact(
                                        db = db,
                                        uid = auth.currentUser!!.uid,
                                        name = name,
                                        phoneNumber = phoneNumber,
                                        email = email,
                                        type = contactType!!,
                                        verified = true
                                    )
                                    Toast.makeText(context, "Contact verified and added!", Toast.LENGTH_SHORT).show()
                                    onContactAdded()
                                    onDismiss()
                                } else {
                                    errorMessage = "Invalid OTP. Please check your email and try again."
                                }
                            }
                            contactType == "organization" -> {
                                // Check for duplicate phone number (organizations don't have email verification)
                                val phoneHash = hashPhoneNumber(phoneNumber)

                                val phoneCheck = db.collection("users")
                                    .document(auth.currentUser!!.uid)
                                    .collection("contacts")
                                    .whereEqualTo("phoneNumberHash", phoneHash)
                                    .get()
                                    .await()

                                if (!phoneCheck.isEmpty) {
                                    errorMessage = "This phone number is already added as an emergency contact"
                                } else {
                                    // No duplicate, save organization
                                    saveContact(
                                        db = db,
                                        uid = auth.currentUser!!.uid,
                                        name = name,
                                        phoneNumber = phoneNumber,
                                        email = "",
                                        type = contactType!!,
                                        verified = false
                                    )
                                    Toast.makeText(context, "Organization contact added", Toast.LENGTH_SHORT).show()
                                    onContactAdded()
                                    onDismiss()
                                }
                            }
                        }
                        isProcessing = false
                    }
                },
                enabled = !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        when {
                            contactType == null -> "Next"
                            showOtpField -> "Verify & Add"
                            contactType == "person" -> "Send OTP"
                            else -> "Add Contact"
                        }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isProcessing) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EmptyContactsView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ContactPhone,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Emergency Contacts",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Add emergency contacts to call them quickly in case of emergency",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Helper Functions
fun hashPhoneNumber(phoneNumber: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(phoneNumber.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }
}

fun generateOtp(): String {
    return (100000..999999).random().toString()
}

suspend fun saveContact(
    db: FirebaseFirestore,
    uid: String,
    name: String,
    phoneNumber: String,
    email: String,
    type: String,
    verified: Boolean
) {
    val contactId = UUID.randomUUID().toString()
    val hashedNumber = hashPhoneNumber(phoneNumber)
    val encryptedNumber = PhoneEncryption.encrypt(phoneNumber)

    val contactData = hashMapOf(
        "id" to contactId,
        "name" to name,
        "phoneNumberHash" to hashedNumber,
        "phoneNumberEncrypted" to encryptedNumber,
        "email" to email,
        "displayNumber" to phoneNumber.takeLast(4),
        "type" to type,
        "verified" to verified,
        "createdAt" to System.currentTimeMillis()
    )

    db.collection("users")
        .document(uid)
        .collection("contacts")
        .document(contactId)
        .set(contactData)
        .await()
}

fun loadContacts(
    db: FirebaseFirestore,
    uid: String,
    onLoaded: (List<EmergencyContact>) -> Unit
) {
    db.collection("users")
        .document(uid)
        .collection("contacts")
        .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
        .addSnapshotListener { snapshot, error ->
            if (error != null) {
                onLoaded(emptyList())
                return@addSnapshotListener
            }

            val contacts = snapshot?.documents?.mapNotNull { doc ->
                EmergencyContact(
                    id = doc.getString("id") ?: "",
                    name = doc.getString("name") ?: "",
                    phoneNumberHash = doc.getString("phoneNumberHash") ?: "",
                    phoneNumberEncrypted = doc.getString("phoneNumberEncrypted") ?: "",
                    email = doc.getString("email") ?: "",
                    displayNumber = doc.getString("displayNumber") ?: "",
                    type = doc.getString("type") ?: "",
                    verified = doc.getBoolean("verified") ?: false,
                    createdAt = doc.getLong("createdAt") ?: 0L
                )
            } ?: emptyList()

            onLoaded(contacts)
        }
}

suspend fun deleteContact(db: FirebaseFirestore, uid: String, contactId: String) {
    db.collection("users")
        .document(uid)
        .collection("contacts")
        .document(contactId)
        .delete()
        .await()
}

fun makePhoneCall(
    context: android.content.Context,
    phoneNumber: String,
    contactName: String,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to make call: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        permissionLauncher.launch(Manifest.permission.CALL_PHONE)
        Toast.makeText(context, "Please grant call permission to contact $contactName", Toast.LENGTH_LONG).show()
    }
}