package com.example.getconnected

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val user = auth.currentUser
    val context = LocalContext.current

    // State for user data
    var userRegion by remember { mutableStateOf("Dar es Salaam") }
    var userFullName by remember { mutableStateOf("Mtumiaji") }
    var userType by remember { mutableStateOf("SEEKER") }
    var verificationLevel by remember { mutableIntStateOf(0) }
    var profileImageUrl by remember { mutableStateOf("") }
    var isEmailVerified by remember { mutableStateOf(user?.isEmailVerified == true) }

    // Work preferences (Kigezo cha Kazi) — edited via WorkPreferencesEditor.
    var jobFields by remember { mutableStateOf<List<String>>(emptyList()) }
    var workLevel by remember { mutableStateOf("") }
    var experience by remember { mutableStateOf("") }
    var onboardingComplete by remember { mutableStateOf(false) }
    var showPrefsEditor by remember { mutableStateOf(false) }

    // UI States
    var isUploading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val regions = listOf("Dar es Salaam", "Mwanza", "Mbeya", "Arusha", "Kigoma", "Dodoma", "Tanga")

    // Fetch user data with a listener that is disposed with this screen.
    DisposableEffect(user?.uid) {
        val uid = user?.uid
        var registration: com.google.firebase.firestore.ListenerRegistration? = null

        // Refresh the cached email-verification flag whenever the screen appears.
        fun refreshVerification() {
            val firebaseUser = user ?: return
            firebaseUser.reload().addOnCompleteListener {
                isEmailVerified = firebaseUser.isEmailVerified
            }
        }
        refreshVerification()

        if (uid != null) {
            registration = db.collection("users").document(uid)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        userRegion = snapshot.getString("region") ?: "Dar es Salaam"
                        userFullName = snapshot.getString("fullName") ?: "Mtumiaji"
                        userType = snapshot.getString("userType") ?: "SEEKER"
                        verificationLevel = snapshot.getLong("verificationLevel")?.toInt() ?: 0
                        profileImageUrl = snapshot.getString("profileImage") ?: ""
                        jobFields = (snapshot.get("jobFields") as? List<*>)
                            ?.filterIsInstance<String>() ?: emptyList()
                        workLevel = snapshot.getString("workLevel") ?: ""
                        experience = snapshot.getString("experience") ?: ""
                        onboardingComplete = snapshot.getBoolean("onboardingComplete") == true
                    }
                }
        }

        onDispose { registration?.remove() }
    }

    // Re-check verification when returning from the email app.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val firebaseUser = user ?: return@LifecycleEventObserver
                firebaseUser.reload().addOnCompleteListener {
                    isEmailVerified = firebaseUser.isEmailVerified
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 2. Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isUploading = true
            MediaManager.get().upload(it)
                .unsigned("getconnected") // Your Preset Name
                .option("folder", "getconnected") // Your Folder Name
                .callback(object : UploadCallback {
                    override fun onStart(requestId: String?) {
                        isUploading = true
                    }
                    override fun onSuccess(requestId: String?, resultData: Map<*, *>?) {
                        val url = resultData?.get("secure_url") as? String
                        if (url.isNullOrBlank()) {
                            isUploading = false
                            Toast.makeText(context, "Imefeli kupakia picha.", Toast.LENGTH_LONG).show()
                            return
                        }
                        // 4. Save Cloudinary URL to Firestore
                        user?.uid?.let { uid ->
                            db.collection("users").document(uid).update("profileImage", url)
                                .addOnSuccessListener {
                                    isUploading = false
                                    Toast.makeText(context, "Picha imehifadhiwa!", Toast.LENGTH_SHORT).show()
                                }
                                .addOnFailureListener {
                                    isUploading = false
                                    Toast.makeText(context, "Imeshindikana kuhifadhi picha.", Toast.LENGTH_LONG).show()
                                }
                        } ?: run {
                            isUploading = false
                        }
                    }

                    override fun onError(requestId: String?, error: ErrorInfo?) {
                        isUploading = false
                        Toast.makeText(context, "Imefeli kupakia: ${error?.description}", Toast.LENGTH_LONG).show()
                    }

                    override fun onReschedule(requestId: String?, error: ErrorInfo?) {}
                    override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {}
                }).dispatch()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- PROFILE PICTURE SECTION ---
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { if (!isUploading) imagePickerLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (profileImageUrl.isNotEmpty()) {
                AsyncImage(
                    model = profileImageUrl,
                    contentDescription = "Profile Picture",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.Person, null, modifier = Modifier.size(60.dp), tint = Color.Gray)
            }

            if (isUploading) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp), color = Color.White)
            }
        }

        Text(
            text = "Gusa kubadilisha picha",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = userFullName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(text = user?.email ?: "", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(16.dp))

        // --- WORK PREFERENCES (Kigezo cha Kazi) ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (onboardingComplete)
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Kigezo cha Kazi",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showPrefsEditor = true }) {
                        Text(if (onboardingComplete) "Badilisha" else "Kamilisha")
                    }
                }

                if (!onboardingComplete) {
                    Text(
                        text = "Bado hujakamilisha utafutaji wako wa kazi — chagua taaluma zako ili tuonyeshe fursa bora.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Text(
                    text = "Ninataka: " + if (userType == "EMPLOYER") "kuajiri watu" else "kazi",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                if (jobFields.isEmpty()) {
                    Text(
                        text = "Taaluma: Haijachaguliwa",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        jobFields.take(3).forEach { field ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = field,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        if (jobFields.size > 3) {
                            Text(
                                text = "+${jobFields.size - 3}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                val levelText = WorkPreferences.levelLabel(workLevel)
                val expText = WorkPreferences.experienceLabel(experience)
                if (levelText.isNotBlank() || expText.isNotBlank()) {
                    Text(
                        text = listOf(levelText, expText).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        if (showPrefsEditor) {
            WorkPreferencesEditor(
                initialIntent = userType,
                initialFields = jobFields,
                initialLevel = workLevel,
                initialExperience = experience,
                onSave = { _, fields, level, exp ->
                    db.collection("users").document(user?.uid ?: "")
                        .update(
                            mapOf(
                                "jobFields" to fields,
                                "workLevel" to level,
                                "experience" to exp,
                                "onboardingComplete" to true,
                                "onboardingSkipped" to false
                            )
                        )
                        .addOnSuccessListener {
                            showPrefsEditor = false
                            Toast.makeText(context, "Kigezo cha kazi kimehifadhiwa.", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Imeshindikana kuhifadhi: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                },
                onDismiss = { showPrefsEditor = false }
            )
        }

        // --- REGION SELECTOR ---
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = userRegion,
                onValueChange = {},
                readOnly = true,
                label = { Text("Mkoa wako") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                regions.forEach { region ->
                    DropdownMenuItem(
                        text = { Text(region) },
                        onClick = {
                            val previous = userRegion
                            userRegion = region
                            expanded = false
                            user?.uid?.let { uid ->
                                db.collection("users").document(uid).update("region", region)
                                    .addOnFailureListener {
                                        userRegion = previous
                                        Toast.makeText(context, "Imeshindikana kuhifadhi mkoa.", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- ACCOUNT STATUS CARD ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Hali ya Akaunti", fontWeight = FontWeight.Bold)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Usalama:", color = Color.Gray)
                    Text(
                        text = if (isEmailVerified) "Imehakikiwa ✅" else "Inasubiri...",
                        color = if (isEmailVerified) Color(0xFF2E7D32) else Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                }

                // --- DYNAMIC INSTRUCTIONS ---
                if (!isEmailVerified) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        color = Color.Yellow.copy(alpha = 0.15f), // Slightly more visible
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info, // Ensure this import exists
                                    contentDescription = null,
                                    tint = Color(0xFF856404),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Hatua za Uhakiki:",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFF856404)
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            Text(
                                text = "Tumekutumia barua pepe (email). Tafadhali fungua inbox yako kisha bonyeza link ya uthibitisho. Usipoiona, angalia folder la 'Spam' au 'Junk'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF856404),
                                lineHeight = 16.sp
                            )

                            // Re-send Link Button
                            TextButton(
                                onClick = {
                                    user?.sendEmailVerification()?.addOnSuccessListener {
                                        Toast.makeText(context, "Link mpya imetumwa!", Toast.LENGTH_SHORT).show()
                                    }?.addOnFailureListener {
                                        Toast.makeText(context, "Imeshindikana kutuma link. Jaribu tena.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Tuma tena link", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    // Verified Message
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Akaunti yako imethibitishwa. Furahia huduma za Get Connected!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                // MainActivity observes FirebaseAuth auth-state changes and swaps back
                // to the sign-up screen as soon as this fires.
                auth.signOut()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text("Toka (Log Out)", color = Color.White)
        }
    }
}