package com.example.child_safety_app_version1.userInterface

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.child_safety_app_version1.utils.saveLoginState
import com.example.child_safety_app_version1.utils.FcmTokenManager
import com.google.firebase.auth.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch

data class ValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

object InputValidator {
    fun validateEmail(email: String): ValidationResult {
        val trimmedEmail = email.trim()

        return when {
            trimmedEmail.isEmpty() -> ValidationResult(false, "Email is required")
            trimmedEmail.length < 5 -> ValidationResult(false, "Email is too short")
            !trimmedEmail.contains("@") -> ValidationResult(false, "Email must contain @")
            !trimmedEmail.contains(".") -> ValidationResult(false, "Email must contain a domain")
            trimmedEmail.startsWith("@") || trimmedEmail.endsWith("@") ->
                ValidationResult(false, "Invalid email format")
            trimmedEmail.count { it == '@' } != 1 ->
                ValidationResult(false, "Email must contain exactly one @")
            !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches() ->
                ValidationResult(false, "Please enter a valid email address")
            trimmedEmail.length > 254 -> ValidationResult(false, "Email is too long")
            else -> ValidationResult(true)
        }
    }

    fun validatePassword(password: String, isRegistration: Boolean = false): ValidationResult {
        return when {
            password.isEmpty() -> ValidationResult(false, "Password is required")
            password.length < 6 -> ValidationResult(false, "Password must be at least 6 characters")
            isRegistration && password.length > 128 ->
                ValidationResult(false, "Password is too long (max 128 characters)")
            isRegistration && !password.any { it.isUpperCase() } ->
                ValidationResult(false, "Password must contain at least one uppercase letter")
            isRegistration && !password.any { it.isLowerCase() } ->
                ValidationResult(false, "Password must contain at least one lowercase letter")
            isRegistration && !password.any { it.isDigit() } ->
                ValidationResult(false, "Password must contain at least one number")
            isRegistration && password.contains(" ") ->
                ValidationResult(false, "Password cannot contain spaces")
            else -> ValidationResult(true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(navController: NavController) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val firestore = remember { Firebase.firestore }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("parent") }
    var isLoading by remember { mutableStateOf(false) }
    var isLoginMode by remember { mutableStateOf(true) }
    var passwordVisible by remember { mutableStateOf(false) }

    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    var showSuccessDialog by remember { mutableStateOf(false) }

    fun validateInputs(isRegistration: Boolean = false): Boolean {
        val emailValidation = InputValidator.validateEmail(email)
        val passwordValidation = InputValidator.validatePassword(password, isRegistration)

        emailError = emailValidation.errorMessage
        passwordError = passwordValidation.errorMessage

        return emailValidation.isValid && passwordValidation.isValid
    }

    fun registerUser(userEmail: String, userPassword: String, role: String) {
        if (!validateInputs(isRegistration = true)) {
            isLoading = false
            return
        }

        val trimmedEmail = userEmail.trim()
        val trimmedPassword = userPassword.trim()

        auth.createUserWithEmailAndPassword(trimmedEmail, trimmedPassword)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid
                if (uid == null) {
                    scope.launch {
                        Toast.makeText(context, "Registration failed", Toast.LENGTH_SHORT).show()
                        isLoading = false
                    }
                    return@addOnSuccessListener
                }

                val userMap = mapOf(
                    "uid" to uid,
                    "email" to trimmedEmail,
                    "role" to role,
                    "createdAt" to System.currentTimeMillis()
                )

                firestore.collection("users").document(uid)
                    .set(userMap)
                    .addOnSuccessListener {
                        scope.launch {
                            isLoading = false

                            // Sign out the user immediately after registration
                            auth.signOut()

                            // Clear the fields
                            email = ""
                            password = ""
                            emailError = null
                            passwordError = null

                            // Show success dialog
                            showSuccessDialog = true
                        }
                    }
                    .addOnFailureListener { e ->
                        scope.launch {
                            isLoading = false
                            Toast.makeText(context, "Registration failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            }
            .addOnFailureListener { exception ->
                scope.launch {
                    isLoading = false
                    val message = when (exception) {
                        is FirebaseAuthWeakPasswordException -> "Password is too weak"
                        is FirebaseAuthUserCollisionException -> "This email is already registered"
                        is FirebaseAuthInvalidCredentialsException -> "Invalid email format"
                        else -> exception.message ?: "Registration failed"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
    }

    fun loginUser(userEmail: String, userPassword: String, role: String) {
        if (!validateInputs(isRegistration = false)) {
            isLoading = false
            return
        }

        val trimmedEmail = userEmail.trim()
        val trimmedPassword = userPassword.trim()

        auth.signInWithEmailAndPassword(trimmedEmail, trimmedPassword)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid
                if (uid == null) {
                    scope.launch {
                        isLoading = false
                        Toast.makeText(context, "Login failed", Toast.LENGTH_SHORT).show()
                    }
                    return@addOnSuccessListener
                }

                firestore.collection("users").document(uid)
                    .get()
                    .addOnSuccessListener { doc ->
                        scope.launch {
                            val storedRole = doc.getString("role")
                            if (storedRole == role) {
                                // Save FCM token after successful login
                                FcmTokenManager.saveFcmToken(uid)

                                saveLoginState(context, role)
                                isLoading = false
                                Toast.makeText(context, "Welcome back!", Toast.LENGTH_SHORT).show()

                                val destination = if (role == "parent") "parent_dashboard" else "child_dashboard"
                                navController.navigate(destination) {
                                    popUpTo("login") { inclusive = true }
                                }
                            } else {
                                isLoading = false
                                Toast.makeText(context, "Role mismatch! You registered as $storedRole", Toast.LENGTH_LONG).show()
                                auth.signOut()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        scope.launch {
                            isLoading = false
                            Toast.makeText(context, "Failed to verify user: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            }
            .addOnFailureListener { exception ->
                scope.launch {
                    isLoading = false
                    val message = when (exception) {
                        is FirebaseAuthInvalidUserException -> "No account found with this email"
                        is FirebaseAuthInvalidCredentialsException -> "Incorrect email or password"
                        else -> exception.message ?: "Login failed"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
    }

    // Success Dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { },
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    "Registration Successful!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Your account has been created successfully. Please login with your credentials to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSuccessDialog = false
                        isLoginMode = true
                    }
                ) {
                    Text("Go to Login")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Section - Logo and Title
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))

                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "App Logo",
                    modifier = Modifier.size(60.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Child Safety App",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    if (isLoginMode) "Welcome back! Please login to continue"
                    else "Create an account to get started",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Main Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    // Email Field
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            emailError = null
                        },
                        label = { Text("Email Address") },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = "Email")
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        isError = emailError != null,
                        supportingText = {
                            if (emailError != null) {
                                Text(
                                    text = emailError!!,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    // Password Field
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            passwordError = null
                        },
                        label = { Text("Password") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = "Password")
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        isError = passwordError != null,
                        supportingText = {
                            if (passwordError != null) {
                                Text(
                                    text = passwordError!!,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (!isLoginMode) {
                                Text(
                                    text = "Min 6 characters, 1 uppercase, 1 lowercase, 1 number",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(24.dp))

                    // Role Selection
                    Text(
                        "Select Your Role",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Parent Role Card
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedRole == "parent")
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            onClick = { if (!isLoading) selectedRole = "parent" }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "Parent",
                                    modifier = Modifier.size(32.dp),
                                    tint = if (selectedRole == "parent")
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Parent",
                                    fontWeight = if (selectedRole == "parent") FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedRole == "parent")
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Child Role Card
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedRole == "child")
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            ),
                            onClick = { if (!isLoading) selectedRole = "child" }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.ChildCare,
                                    contentDescription = "Child",
                                    modifier = Modifier.size(32.dp),
                                    tint = if (selectedRole == "child")
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Child",
                                    fontWeight = if (selectedRole == "child") FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedRole == "child")
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    // Action Button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            isLoading = true

                            if (isLoginMode) {
                                loginUser(email, password, selectedRole)
                            } else {
                                registerUser(email, password, selectedRole)
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Please wait...")
                        } else {
                            Icon(
                                imageVector = if (isLoginMode) Icons.Default.Login else Icons.Default.PersonAdd,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isLoginMode) "Login" else "Create Account",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Toggle Mode Button
            TextButton(
                onClick = {
                    if (!isLoading) {
                        isLoginMode = !isLoginMode
                        emailError = null
                        passwordError = null
                    }
                },
                enabled = !isLoading
            ) {
                Text(
                    if (isLoginMode)
                        "Don't have an account? Create one"
                    else
                        "Already have an account? Login here",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}