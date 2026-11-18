package com.example.getconnected

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cloudinary.android.MediaManager
import com.example.getconnected.ui.theme.GetConnectedTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cloudinary must be initialized once, before any screen uploads
        // (Profile avatar and Employer document uploads both rely on it).
        try {
            MediaManager.init(
                this,
                mapOf("cloud_name" to "dtizvymkp", "secure" to true)
            )
        } catch (e: Exception) {
            // Already initialized.
        }

        setContent {
            GetConnectedTheme {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                val db = FirebaseFirestore.getInstance()

                // Observe Firebase auth state so sign-out (from any screen or another
                // device) always swaps the UI back to the sign-up/login screen.
                var currentUser by remember { mutableStateOf(auth.currentUser) }
                DisposableEffect(auth) {
                    val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { firebaseAuth ->
                        currentUser = firebaseAuth.currentUser
                    }
                    auth.addAuthStateListener(listener)
                    onDispose { auth.removeAuthStateListener(listener) }
                }

                // State to control the transition from Branding to App
                var showBranding by remember { mutableStateOf(true) }
                var checkingAccountStatus by remember { mutableStateOf(currentUser != null) }

                // Splash screen timer
                LaunchedEffect(Unit) {
                    delay(2000) // Show branding for 2 seconds
                    showBranding = false
                }

                // Enforce admin-disabled accounts on app launch/session restore.
                LaunchedEffect(currentUser?.uid) {
                    val uid = currentUser?.uid
                    if (uid.isNullOrBlank()) {
                        checkingAccountStatus = false
                    } else {
                        checkingAccountStatus = true
                        db.collection("users").document(uid).get()
                            .addOnSuccessListener { doc ->
                                val disabled = (doc.getBoolean("accountDisabled") == true) ||
                                    (doc.getString("accountStatus") == "disabled")

                                if (disabled) {
                                    auth.signOut()
                                    currentUser = null
                                    Toast.makeText(this@MainActivity, "Akaunti yako imezuiwa na admin.", Toast.LENGTH_LONG).show()
                                }
                                checkingAccountStatus = false
                            }
                            .addOnFailureListener {
                                // Fail open on network errors (consistent with login flow);
                                // disabled accounts are still caught whenever we are online.
                                checkingAccountStatus = false
                            }
                    }
                }

                if (showBranding) {
                    // This is your beautiful branding screen
                    BrandingSplashScreen()
                } else if (checkingAccountStatus) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    // Main App Logic
                    if (currentUser == null) {
                        SignUpScreen(onSignUpSuccess = {
                            currentUser = auth.currentUser
                        })
                    } else {
                        GetConnectedApp()
                    }
                }
            }
        }
    }
}

@Composable
fun BrandingSplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Get Connected",
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 2.sp
            )
            Text(
                text = "Home of Connections",
                fontSize = 18.sp,
                fontStyle = FontStyle.Italic,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Optional: A small loading indicator at the bottom
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
    }
}

// Global helper function
fun openWhatsApp(context: Context, phoneNumber: String, jobTitle: String) {
    if (phoneNumber.isBlank()) {
        Toast.makeText(context, "Namba ya mwajiri haipo!", Toast.LENGTH_SHORT).show()
        return
    }

    var cleanNumber = phoneNumber.replace("+", "").replace(" ", "").replace("-", "")

    if (cleanNumber.startsWith("0")) {
        cleanNumber = "255" + cleanNumber.substring(1)
    } else if (!cleanNumber.startsWith("255")) {
        cleanNumber = "255" + cleanNumber
    }

    val message = "Habari, nimeona kazi ya *$jobTitle* kwenye app ya GetConnected. Naomba maelezo zaidi."
    val url = "https://wa.me/$cleanNumber?text=${Uri.encode(message)}"

    val intent = Intent(Intent.ACTION_VIEW)
    intent.data = Uri.parse(url)

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "WhatsApp haijawekwa!", Toast.LENGTH_LONG).show()
    }
}



