package com.example.getconnected

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.jsoup.Jsoup

// 1. Navigation Destinations Enum
enum class AppDestinations(val label: String, val icon: @Composable (Int) -> Unit) {
    HOME("Nyumbani", { _ -> Icon(Icons.Default.Home, null) }),
    INBOX("Inbox", { count ->
        BadgedBox(
            badge = {
                if (count > 0) {
                    Badge(containerColor = Color.Red) {
                        Text(text = count.toString(), color = Color.White)
                    }
                }
            }
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, null)
        }
    }),
    DASHBOARD("Kazi Zangu", { _ -> Icon(Icons.AutoMirrored.Filled.ListAlt, null) }),
    PROFILE("Wasifu", { _ -> Icon(Icons.Default.Person, null) })
}

@Composable
fun GetConnectedApp(mainViewModel: MainViewModel = viewModel()) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current

    var currentDestination by remember { mutableStateOf(AppDestinations.HOME) }
    var showPostJobDialog by remember { mutableStateOf(false) }
    var activeChatId by remember { mutableStateOf<Pair<String, String>?>(null) }

    var isEmployer by remember { mutableStateOf(false) }
    var isRoleLoading by remember { mutableStateOf(true) }
    var onboardingNeeded by remember { mutableStateOf(false) }
    var onboardingIntent by remember { mutableStateOf("SEEKER") }

    LaunchedEffect(auth.currentUser) {
        auth.currentUser?.uid?.let { uid ->
            mainViewModel.startObservingChats(uid, context)
            db.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    if (doc.exists()) {
                        val userType = doc.getString("userType") ?: "SEEKER"
                        isEmployer = userType == "EMPLOYER"
                        onboardingIntent = userType
                        // First-run wizard: shown until finished, never again once
                        // completed or explicitly skipped (Profile can reopen it).
                        onboardingNeeded =
                            doc.getBoolean("onboardingComplete") != true &&
                            doc.getBoolean("onboardingSkipped") != true
                    }
                    isRoleLoading = false
                }
                .addOnFailureListener { isRoleLoading = false }
        } ?: run { isRoleLoading = false }
    }

    fun openChat(receiverId: String, receiverName: String) {
        if (receiverId.isBlank()) {
            Toast.makeText(context, "Mtumiaji huyu hayupo.", Toast.LENGTH_SHORT).show()
            return
        }
        activeChatId = receiverId to receiverName
    }

    if (isRoleLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        if (onboardingNeeded) {
            OnboardingScreen(
                startIntent = onboardingIntent,
                onComplete = { intent, fields, level, experience ->
                    val uid = auth.currentUser?.uid
                    if (uid == null) {
                        onboardingNeeded = false
                        return@OnboardingScreen
                    }
                    db.collection("users").document(uid)
                        .update(
                            mapOf(
                                "userType" to intent,
                                "jobFields" to fields,
                                "workLevel" to level,
                                "experience" to experience,
                                "onboardingComplete" to true
                            )
                        )
                        .addOnSuccessListener {
                            isEmployer = intent == "EMPLOYER"
                            onboardingNeeded = false
                        }
                        .addOnFailureListener {
                            Toast.makeText(
                                context,
                                "Imeshindikana kuhifadhi: ${it.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                },
                onSkip = {
                    val uid = auth.currentUser?.uid
                    if (uid != null) {
                        db.collection("users").document(uid)
                            .update("onboardingSkipped", true)
                            .addOnFailureListener { }
                    }
                    onboardingNeeded = false
                }
            )
        } else if (activeChatId != null) {
            ChatScreen(
                receiverId = activeChatId!!.first,
                receiverName = activeChatId!!.second,
                onBack = { activeChatId = null },
                mainViewModel = mainViewModel
            )
        } else {
            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    AppDestinations.entries.forEach { destination ->
                        if (destination != AppDestinations.DASHBOARD || isEmployer) {
                            val badgeCount = if (destination == AppDestinations.INBOX) mainViewModel.unreadMessagesCount else 0
                            item(
                                selected = currentDestination == destination,
                                onClick = { currentDestination = destination },
                                icon = { destination.icon(badgeCount) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            ) {
                Scaffold(
                    floatingActionButton = {
                        if (isEmployer && currentDestination == AppDestinations.HOME) {
                            ExtendedFloatingActionButton(
                                onClick = { showPostJobDialog = true },
                                icon = { Icon(Icons.Default.Add, null) },
                                text = { Text("Weka Kazi") },
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentDestination) {
                            AppDestinations.HOME -> JobFeedScreen(
                                mainViewModel,
                                onMessageClick = { id, name -> openChat(id, name) }
                            )
                            AppDestinations.INBOX -> InboxScreen(
                                onChatClick = { id, name -> openChat(id, name) },
                                viewModel = mainViewModel
                            )
                            AppDestinations.DASHBOARD -> EmployerDashboard()
                            AppDestinations.PROFILE -> ProfileScreen()
                        }

                        if (showPostJobDialog) {
                            PostJobDialog(
                                onDismiss = { showPostJobDialog = false },
                                onConfirm = { newJob ->
                                    val uid = auth.currentUser?.uid ?: ""
                                    db.collection("users").document(uid).get().addOnSuccessListener { userDoc ->
                                        val liveLevel = userDoc.getLong("verificationLevel")?.toInt() ?: 0
                                        val logoUrl = userDoc.getString("profileImage") ?: ""

                                        val jobWithAuth = newJob.copy(
                                            employerId = uid,
                                            verificationLevel = liveLevel,
                                            companyLogoUrl = logoUrl
                                        )
                                        mainViewModel.postJob(jobWithAuth, liveLevel)
                                        showPostJobDialog = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JobFeedScreen(viewModel: MainViewModel, onMessageClick: (String, String) -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var userRegion by remember { mutableStateOf("") }
    var userFields by remember { mutableStateOf<List<String>>(emptyList()) }
    var fieldFilter by remember { mutableStateOf("Zote") }

    val firestoreJobs = viewModel.filteredFirestoreJobs
    val externalJobs = viewModel.filteredExternalJobs
    val recommendedJobs = viewModel.recommendedJobs

    var selectedJob by remember { mutableStateOf<JobPost?>(null) }

    LaunchedEffect(auth.currentUser) {
        auth.currentUser?.uid?.let { uid ->
            db.collection("users").document(uid).get().addOnSuccessListener { doc ->
                val region = doc.getString("region") ?: ""
                userRegion = region
                userFields = (doc.get("jobFields") as? List<*>)
                    ?.filterIsInstance<String>() ?: emptyList()
                viewModel.refreshRecommendations(region)
            }
        }
    }

    // "Zote" shows everything (old/external jobs carry no field tag); a specific
    // field only shows jobs explicitly tagged with it — so nothing disappears
    // until the user asks for it.
    val matchesFilter: (JobPost) -> Boolean = { job ->
        fieldFilter == "Zote" || (job.field.isNotBlank() && job.field == fieldFilter)
    }
    val localJobs = firestoreJobs.filter(matchesFilter)
    val promotedJobs = localJobs.filter { it.promoted }
    val normalLocalJobs = localJobs.filterNot { it.promoted }
    val visibleExternalJobs = externalJobs.filter(matchesFilter)
    val visibleRecommended = recommendedJobs.filter(matchesFilter)
    // The user's own fields come first in the chip row.
    val chipFields = remember(userFields) {
        userFields.filter { it in WorkPreferences.FIELDS } +
            WorkPreferences.FIELDS.filterNot { it in userFields }
    }

    if (selectedJob != null) {
        JobDetailDialog(
            job = selectedJob!!,
            onDismiss = { selectedJob = null },
            onMessageClick = onMessageClick
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.searchJobs(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Tafuta kazi (Dar, Dereva...)") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        // Field (taaluma) filter chips — user's own fields first, "Zote" resets.
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                item {
                    FilterChip(
                        selected = fieldFilter == "Zote",
                        onClick = { fieldFilter = "Zote" },
                        label = { Text("Zote") }
                    )
                }
                items(chipFields) { field ->
                    FilterChip(
                        selected = fieldFilter == field,
                        onClick = {
                            fieldFilter = if (fieldFilter == field) "Zote" else field
                        },
                        label = { Text(field) }
                    )
                }
            }
        }

        // Matangazo: promoted jobs flagged by the admin (simple, free-tier proof).
        if (promotedJobs.isNotEmpty() && searchQuery.isEmpty()) {
            item {
                Text(
                    "Matangazo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(promotedJobs) { job ->
                JobCard(
                    job = job,
                    onCardClick = {
                        viewModel.incrementJobViews(job.id, context)
                        selectedJob = job
                    }
                )
            }
        }

        if (visibleRecommended.isNotEmpty() && searchQuery.isEmpty()) {
            item {
                Text(
                    "Kazi Zinazopendekezwa ($userRegion)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(visibleRecommended) { job ->
                        RecommendationCard(
                            job = job,
                            onCardClick = {
                                viewModel.incrementJobViews(job.id, context)
                                selectedJob = job
                            }
                        )
                    }
                }
            }
        }

        if (normalLocalJobs.isNotEmpty()) {
            item {
                Text(
                    "Kazi za Waajiri (Priority)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            items(normalLocalJobs) { job ->
                JobCard(
                    job = job,
                    onCardClick = {
                        viewModel.incrementJobViews(job.id, context)
                        selectedJob = job
                    }
                )
            }
        }

        if (visibleExternalJobs.isNotEmpty()) {
            item {
                Text(
                    "Kazi Nyingine (Other Jobs)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            items(visibleExternalJobs) { job ->
                JobCard(
                    job = job,
                    onCardClick = {
                        viewModel.incrementJobViews(job.id, context)
                        selectedJob = job
                    }
                )
            }
        }

        if (localJobs.isEmpty() && visibleExternalJobs.isEmpty()) {
            item {
                Box(Modifier
                    .fillMaxWidth()
                    .height(200.dp), Alignment.Center) {
                    if (searchQuery.isEmpty() && fieldFilter == "Zote") CircularProgressIndicator()
                    else if (fieldFilter != "Zote") Text("Hakuna kazi ya \"$fieldFilter\" bado.")
                    else Text("Hakuna kazi iliyopatikana.")
                }
            }
        }
    }
}

@Composable
fun RecommendationCard(job: JobPost, onCardClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(250.dp)
            .clickable { onCardClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(job.title, maxLines = 1, fontWeight = FontWeight.Bold)
            Text(job.companyName, style = MaterialTheme.typography.bodySmall)
            if (job.salary.isNotEmpty()) {
                Text("💰 ${job.salary}", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }
            Text("📍 ${job.location}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
fun JobCard(
    job: JobPost,
    onCardClick: () -> Unit
) {
    // Clean HTML for preview (plain text, max 2 lines)
    val cleanDescription = remember(job.description) {
        Jsoup.parse(job.description).text()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onCardClick() },
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        if (job.companyLogoUrl.isNotEmpty()) {
                            AsyncImage(
                                model = job.companyLogoUrl,
                                contentDescription = "Logo",
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Business, null, modifier = Modifier.padding(8.dp), tint = Color.Gray)
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = job.companyName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(4.dp))
                            when (job.verificationLevel) {
                                1 -> Icon(Icons.Default.CheckCircle, "Verified", tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                                2 -> Icon(Icons.Default.Verified, "Corporate", tint = Color(0xFF1A237E), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RemoveRedEye, null, Modifier.size(14.dp), Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "${job.views}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = job.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(text = "📍 ${job.location}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

            if (job.field.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(
                        text = job.field,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            if (job.salary.isNotEmpty()) {
                Text(
                    text = "💰 ${job.salary}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = cleanDescription, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text("Soma zaidi...", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(color.toArgb())
            }
        },
        update = { textView ->
            textView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    )
}

@Composable
fun JobDetailDialog(job: JobPost, onDismiss: () -> Unit, onMessageClick: (String, String) -> Unit) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { openWhatsApp(context, job.phoneNumber, job.title) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
            ) {
                Text("WhatsApp")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = {
                onDismiss()
                onMessageClick(job.employerId, job.companyName)
            }) {
                Text("Message")
            }
        },
        title = {
            Column {
                Text(job.title, fontWeight = FontWeight.Bold)
                Text(job.companyName, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val locationLabel = if (job.id.startsWith("remote_")) "Nchi" else "Mkoa"
                Text("📍 $locationLabel: ${job.location}", style = MaterialTheme.typography.bodySmall)
                if (job.salary.isNotEmpty()) {
                    Text("💰 Mshahara: ${job.salary}", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                HtmlText(html = job.description)
                
                Spacer(Modifier.height(16.dp))
                Text("Imewekwa: ${job.postedDate}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    )
}

@Composable
fun PostJobDialog(onDismiss: () -> Unit, onConfirm: (JobPost) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var salary by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var selectedField by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weka Kazi Mpya") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Kazi gani? (Mf: Dereva)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = company, onValueChange = { company = it }, label = { Text("Jina la Kampuni/Mwajiri") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Namba ya WhatsApp (255...)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Mkoa/Eneo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = salary, onValueChange = { salary = it }, label = { Text("Mshahara (Mf: 500,000 - 800,000)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Maelezo ya Kazi") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 5
                )

                // Required field tag so the job can be matched to seekers' taaluma.
                Text(
                    text = "Aina ya kazi *",
                    style = MaterialTheme.typography.labelLarge
                )
                FieldChips(
                    selected = if (selectedField.isEmpty()) emptyList() else listOf(selectedField),
                    onToggle = { selectedField = it },
                    single = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && company.isNotBlank() && phone.isNotBlank()) {
                        if (selectedField.isEmpty()) {
                            Toast.makeText(context, "Chagua aina ya kazi", Toast.LENGTH_SHORT).show()
                        } else {
                            onConfirm(
                                JobPost(
                                    title = title,
                                    companyName = company,
                                    location = location,
                                    salary = salary,
                                    description = description,
                                    phoneNumber = phone,
                                    field = selectedField
                                )
                            )
                        }
                    } else {
                        Toast.makeText(context, "Jaza taarifa zote muhimu", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { Text("Tuma Kazi") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Ghairi") }
        }
    )
}
