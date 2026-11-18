package com.example.getconnected

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployerDashboard() {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current
    val currentUserId = auth.currentUser?.uid ?: ""

    var verificationLevel by remember { mutableIntStateOf(0) }
    var tinInput by remember { mutableStateOf("") }
    var brelaInput by remember { mutableStateOf("") }

    var tinImageUri by remember { mutableStateOf<Uri?>(null) }
    var brelaImageUri by remember { mutableStateOf<Uri?>(null) }

    var isSubmitting by remember { mutableStateOf(false) }
    var myJobs by remember { mutableStateOf(listOf<JobPost>()) }
    var isLoadingJobs by remember { mutableStateOf(true) }

    val tinPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { tinImageUri = it }
    val brelaPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { brelaImageUri = it }

    var showAddJobForm by remember { mutableStateOf(false) }

    // Registrations are disposed with the composable so listeners never leak.
    DisposableEffect(currentUserId) {
        var userRegistration: com.google.firebase.firestore.ListenerRegistration? = null
        var jobsRegistration: com.google.firebase.firestore.ListenerRegistration? = null

        if (currentUserId.isNotEmpty()) {
            userRegistration = db.collection("users").document(currentUserId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        verificationLevel = snapshot.getLong("verificationLevel")?.toInt() ?: 0
                    }
                }

            jobsRegistration = db.collection("jobs")
                .whereEqualTo("employerId", currentUserId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot != null) {
                        // Map by document id so deletion always targets a real document.
                        myJobs = snapshot.documents.mapNotNull { doc ->
                            doc.toObject(JobPost::class.java)?.copy(id = doc.id)
                        }
                    }
                    isLoadingJobs = false
                }
        }

        onDispose {
            userRegistration?.remove()
            jobsRegistration?.remove()
        }
    }

    val totalViews = myJobs.sumOf { it.views }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        item {
            Text("Dhibiti Kazi Zako", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), "Kazi Zote", myJobs.size.toString(), Icons.Default.WorkOutline, MaterialTheme.colorScheme.primary)
                StatCard(Modifier.weight(1f), "Watazamaji", totalViews.toString(), Icons.Default.RemoveRedEye, Color(0xFF2E7D32))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            if (verificationLevel == 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Omba Uhakiki (Verification) ✅", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Pakia picha ya cheti cha TIN au BRELA hapa chini. Uhakiki huchukua hadi siku 3 za kazi.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        DocumentPickerBox("Picha ya Cheti cha TIN", tinImageUri) { tinPicker.launch("image/*") }
                        Spacer(modifier = Modifier.height(12.dp))
                        DocumentPickerBox("Picha ya Leseni ya BRELA", brelaImageUri) { brelaPicker.launch("image/*") }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                if (tinImageUri == null && brelaImageUri == null) {
                                    Toast.makeText(context, "Pakia angalau picha moja", Toast.LENGTH_SHORT).show()
                                } else {
                                    isSubmitting = true
                                    uploadDocsAndSubmit(context, currentUserId, tinImageUri, brelaImageUri) {
                                        isSubmitting = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSubmitting,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                            else Text("Tuma kwa Uhakiki")
                        }
                    }
                }
            } else {
                VerificationStatusLabel(verificationLevel)
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (showAddJobForm) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    AddJobForm(onJobAdded = { _ ->
                        showAddJobForm = false
                    }, onCancel = {
                        showAddJobForm = false
                    })
                }
            }
        } else {
            item {
                Button(
                    onClick = { showAddJobForm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Weka Kazi Mpya")
                }
            }
        }

        item {
            Text("Kazi Zako", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))
        }

        if (isLoadingJobs) {
            item { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            if (myJobs.isEmpty()) {
                item {
                    Text("Hujapakia kazi bado.", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                }
            } else {
                items(myJobs) { job ->
                    EmployerJobCard(job = job, onDelete = {
                        if (job.id.isBlank()) {
                            Toast.makeText(context, "Imeshindikana kutambua kazi hii.", Toast.LENGTH_SHORT).show()
                        } else {
                            db.collection("jobs").document(job.id).delete()
                                .addOnFailureListener {
                                    Toast.makeText(context, "Imeshindikana kufuta kazi.", Toast.LENGTH_SHORT).show()
                                }
                        }
                    })
                }
            }
        }
    }
}

@Composable
fun AddJobForm(onJobAdded: (JobPost) -> Unit, onCancel: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var salary by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedField by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    val db = FirebaseFirestore.getInstance()
    val auth = FirebaseAuth.getInstance()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Weka Kazi Mpya", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Kazi gani? (Mf: Dereva)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = company, onValueChange = { company = it }, label = { Text("Jina la Kampuni/Mwajiri") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Namba ya WhatsApp (255...)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Mkoa/Eneo") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = salary, onValueChange = { salary = it }, label = { Text("Mshahara (Mf: 500,000 - 800,000)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = description, 
            onValueChange = { description = it }, 
            label = { Text("Maelezo ya Kazi") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            maxLines = 5
        )

        // Required field tag so the job is matched to seekers' taaluma.
        Text("Aina ya kazi *", style = MaterialTheme.typography.labelLarge)
        FieldChips(
            selected = if (selectedField.isEmpty()) emptyList() else listOf(selectedField),
            onToggle = { selectedField = it },
            single = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Ghairi")
            }
            Button(
                onClick = {
                    if (title.isNotBlank() && company.isNotBlank() && phone.isNotBlank()) {
                        if (selectedField.isEmpty()) {
                            Toast.makeText(context, "Chagua aina ya kazi", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSubmitting = true
                        val uid = auth.currentUser?.uid ?: ""
                        
                        // Fetch user details for the job post
                        db.collection("users").document(uid).get()
                            .addOnSuccessListener { userDoc ->
                                val liveLevel = userDoc.getLong("verificationLevel")?.toInt() ?: 0
                                val logoUrl = userDoc.getString("profileImage") ?: ""

                                val rawPhone = phone.replace("+", "").replace(" ", "").replace("-", "")
                                val cleanPhone = if (rawPhone.startsWith("0")) "255" + rawPhone.substring(1) else rawPhone

                                val docRef = db.collection("jobs").document()
                                val newJob = JobPost(
                                    id = docRef.id,
                                    title = title,
                                    companyName = company,
                                    location = location,
                                    salary = salary,
                                    description = description,
                                    phoneNumber = cleanPhone,
                                    employerId = uid,
                                    verificationLevel = liveLevel,
                                    companyLogoUrl = logoUrl,
                                    postedDate = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date()),
                                    views = 0,
                                    field = selectedField
                                )

                                docRef.set(newJob).addOnSuccessListener {
                                    onJobAdded(newJob)
                                    isSubmitting = false
                                    Toast.makeText(context, "Kazi imewekwa!", Toast.LENGTH_SHORT).show()
                                }.addOnFailureListener {
                                    isSubmitting = false
                                    Toast.makeText(context, "Imeshindikana kuweka kazi.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .addOnFailureListener {
                                isSubmitting = false
                                Toast.makeText(context, "Imeshindikana kupakia taarifa zako.", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        Toast.makeText(context, "Tafadhali jaza vichwa, kampuni na namba.", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.weight(1f)
            ) {
                if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                else Text("Tuma Kazi")
            }
        }
    }
}

@Composable
fun DocumentPickerBox(label: String, selectedUri: Uri?, onClick: () -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color.LightGray, RoundedCornerShape(12.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (selectedUri != null) {
                AsyncImage(model = selectedUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AddPhotoAlternate, null, tint = Color.Gray)
                    Text("Gusa kupakia picha", fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun VerificationStatusLabel(level: Int) {
    val statusText = if (level == 2) "Akaunti ya Kampuni (Corporate) 🛡️" else "Mwajiri Aliyethibitishwa ✅"
    Surface(color = Color(0xFFE8F5E9), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, null, tint = Color(0xFF2E7D32))
            Spacer(modifier = Modifier.width(12.dp))
            Text(statusText, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StatCard(modifier: Modifier, title: String, value: String, icon: ImageVector, color: Color) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
fun EmployerJobCard(job: JobPost, onDelete: () -> Unit) {
    var showDelete by remember { mutableStateOf(false) }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Futa Kazi?") },
            confirmButton = { TextButton(onClick = { onDelete(); showDelete = false }) { Text("Futa", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Ghairi") } }
        )
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.title, fontWeight = FontWeight.Bold)
                Text("Views: ${job.views}", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
        }
    }
}

private fun uploadDocsAndSubmit(context: android.content.Context, userId: String, tinUri: Uri?, brelaUri: Uri?, onFinish: () -> Unit) {
    val db = FirebaseFirestore.getInstance()
    val updates = java.util.concurrent.ConcurrentHashMap<String, Any>()
    updates["verificationStatus"] = "pending"
    val total = (if (tinUri != null) 1 else 0) + (if (brelaUri != null) 1 else 0)
    var pending = total
    var failed = 0

    fun checkDone() {
        if (pending > 0) return
        if (failed >= total) {
            // Nothing was uploaded — do not claim a verification request was sent.
            onFinish()
            Toast.makeText(context, "Imeshindikana kupakia picha. Jaribu tena.", Toast.LENGTH_LONG).show()
            return
        }
        db.collection("users").document(userId).update(updates)
            .addOnSuccessListener {
                onFinish()
                Toast.makeText(context, "Imetumwa! Uhakiki umeanza.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                onFinish()
                Toast.makeText(context, "Imeshindikana kutuma ombi la uhakiki.", Toast.LENGTH_LONG).show()
            }
    }

    if (tinUri != null) {
        MediaManager.get().upload(tinUri).callback(object : UploadCallback {
            override fun onStart(requestId: String) {}
            override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
            override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                resultData["secure_url"]?.toString()?.let { updates["tinImageUrl"] = it }
                pending--; checkDone()
            }
            override fun onError(requestId: String, error: ErrorInfo) { failed++; pending--; checkDone() }
            override fun onReschedule(requestId: String, error: ErrorInfo) {}
        }).dispatch()
    }

    if (brelaUri != null) {
        MediaManager.get().upload(brelaUri).callback(object : UploadCallback {
            override fun onStart(requestId: String) {}
            override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {}
            override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                resultData["secure_url"]?.toString()?.let { updates["brelaImageUrl"] = it }
                pending--; checkDone()
            }
            override fun onError(requestId: String, error: ErrorInfo) { failed++; pending--; checkDone() }
            override fun onReschedule(requestId: String, error: ErrorInfo) {}
        }).dispatch()
    }
}