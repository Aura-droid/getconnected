package com.example.getconnected

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

fun isOnline(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
    return when {
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
        else -> false
    }
}

private fun enforceAccountIsActive(
    auth: FirebaseAuth,
    db: FirebaseFirestore,
    uid: String,
    context: Context,
    onAllowed: () -> Unit,
    onBlocked: () -> Unit
) {
    if (uid.isBlank()) {
        auth.signOut()
        Toast.makeText(context, "Kuna hitilafu ya akaunti. Jaribu tena.", Toast.LENGTH_LONG).show()
        onBlocked()
        return
    }

    db.collection("users").document(uid).get()
        .addOnSuccessListener { doc ->
            val disabled = (doc.getBoolean("accountDisabled") == true) ||
                (doc.getString("accountStatus") == "disabled")

            if (disabled) {
                auth.signOut()
                Toast.makeText(context, "Akaunti yako imezuiwa na admin.", Toast.LENGTH_LONG).show()
                onBlocked()
            } else {
                onAllowed()
            }
        }
        .addOnFailureListener {
            // Fail open on network/permission errors so a transient offline moment
            // never locks out a valid account (matches MainActivity's launch check).
            onAllowed()
        }
}

@Composable
fun SignUpScreen(onSignUpSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()

    // --- STATES ---
    // Changed: Default to Sign Up mode (isLoginMode = false)
    var isLoginMode by remember { mutableStateOf(false) } 
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var isEmployer by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // --- BRANDING ---
        Icon(
            imageVector = Icons.Default.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color(0xFF1A237E)
        )
        Text(
            text = "Get Connected",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF1A237E)
        )
        Text(
            text = if (isLoginMode) "Karibu Tena (Welcome Back)" else "Unganishwa na Fursa Tanzania",
            color = Color.Gray,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- ROLE PICKER (Moved here so it's visible for both Google & Email) ---
        if (!isLoginMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mimi ni Mwajiri (Employer)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text("Chagua kama unataka kuajiri watu.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Switch(checked = isEmployer, onCheckedChange = { isEmployer = it })
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- INPUT FIELDS ---
        if (!isLoginMode) {
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                label = { Text("Jina Kamili") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Nywila (Password)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (isLoginMode) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(
                    onClick = {
                        if (!isOnline(context)) {
                            Toast.makeText(context, "Hamna mtandao", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (email.isNotBlank()) {
                            auth.sendPasswordResetEmail(email).addOnSuccessListener {
                                Toast.makeText(context, "Link ya kubadilisha nywila imetumwa!", Toast.LENGTH_LONG).show()
                            }.addOnFailureListener {
                                Toast.makeText(context, "Imeshindikana kutuma link: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "Weka email kwanza", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Umesahau Nywila?", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator(color = Color(0xFF1A237E))
        } else {
            // --- PRIMARY ACTION BUTTON ---
            Button(
                onClick = {
                    if (!isOnline(context)) {
                        Toast.makeText(context, "Hamna mtandao", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (email.isNotEmpty() && password.isNotEmpty()) {
                        isLoading = true
                        if (isLoginMode) {
                            auth.signInWithEmailAndPassword(email, password)
                                .addOnSuccessListener { result ->
                                    enforceAccountIsActive(auth, db, result.user?.uid ?: "", context, {
                                        isLoading = false
                                        onSignUpSuccess()
                                    }, { isLoading = false })
                                }
                                .addOnFailureListener {
                                    isLoading = false
                                    Toast.makeText(context, "Imeshindikana: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                        } else {
                            auth.createUserWithEmailAndPassword(email, password)
                                .addOnSuccessListener { result ->
                                    val user = result.user
                                    user?.sendEmailVerification()
                                    
                                    val profile = UserProfile(
                                        uid = user?.uid ?: "",
                                        fullName = fullName,
                                        email = email,
                                        userType = if (isEmployer) "EMPLOYER" else "SEEKER",
                                        region = "Dar es Salaam"
                                    )

                                    db.collection("users").document(profile.uid).set(profile)
                                        .addOnSuccessListener {
                                            isLoading = false
                                            onSignUpSuccess()
                                        }
                                        .addOnFailureListener {
                                            isLoading = false
                                            Toast.makeText(context, "Imeshindikana kuhifadhi wasifu: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                }
                                .addOnFailureListener {
                                    isLoading = false
                                    Toast.makeText(context, "Imefeli: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                        }
                    } else {
                        Toast.makeText(context, "Jaza email na nywila", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (isLoginMode) "Ingia (Login)" else "Jisajili (Register)")
            }

            TextButton(onClick = { isLoginMode = !isLoginMode }) {
                Text(if (isLoginMode) "Huna akaunti? Jisajili hapa" else "Tayari una akaunti? Ingia hapa")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("AU", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(16.dp))

            // --- GOOGLE SIGN IN BUTTON ---
            OutlinedButton(
                onClick = {
                    if (!isOnline(context)) {
                        Toast.makeText(context, "Hamna mtandao", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    isLoading = true
                    val credentialManager = CredentialManager.create(context)

                    val googleIdOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId("833859770795-bk91nttoih9mkfn1c6bheciuqomde4c9.apps.googleusercontent.com")
                        .setAutoSelectEnabled(false)
                        .build()

                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build()

                    scope.launch {
                        try {
                            val result = credentialManager.getCredential(context, request)
                            val credential = result.credential

                            if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)

                                auth.signInWithCredential(firebaseCredential).addOnSuccessListener { authResult ->
                                    val firebaseUser = authResult.user
                                    val uid = firebaseUser?.uid ?: ""

                                    db.collection("users").document(uid).get()
                                        .addOnSuccessListener { document ->
                                            if (!document.exists()) {
                                                val newUser = UserProfile(
                                                    uid = uid,
                                                    fullName = firebaseUser?.displayName ?: "Mtumiaji",
                                                    email = firebaseUser?.email ?: "",
                                                    userType = if (isEmployer) "EMPLOYER" else "SEEKER",
                                                    region = "Dar es Salaam"
                                                )
                                                db.collection("users").document(uid).set(newUser)
                                                    .addOnSuccessListener {
                                                        isLoading = false
                                                        onSignUpSuccess()
                                                    }
                                                    .addOnFailureListener {
                                                        isLoading = false
                                                        Toast.makeText(context, "Firestore Error: ${it.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                            } else {
                                                isLoading = false
                                                onSignUpSuccess()
                                            }
                                        }
                                        .addOnFailureListener {
                                            isLoading = false
                                            Toast.makeText(context, "Imeshindikana kupakia wasifu: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                        }
                                }.addOnFailureListener {
                                    isLoading = false
                                    Toast.makeText(context, "Imeingia: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                // Unexpected credential type — never leave the spinner stuck.
                                isLoading = false
                                Toast.makeText(context, "Google Error: si aina sahihi ya akaunti.", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            isLoading = false
                            Toast.makeText(context, "Google Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Endelea na Google")
            }
        }
    }
}
