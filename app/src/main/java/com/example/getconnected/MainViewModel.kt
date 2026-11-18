package com.example.getconnected

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import me.leolin.shortcutbadger.ShortcutBadger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel : ViewModel() {
    private val db = FirebaseFirestore.getInstance()
    private val jobicyService = JobicyApiService.create()

    // --- JOBS STATE ---
    var firestoreJobs = mutableStateListOf<JobPost>()
        private set
    var externalJobs = mutableStateListOf<JobPost>()
        private set
    
    var filteredFirestoreJobs = mutableStateListOf<JobPost>()
        private set
    var filteredExternalJobs = mutableStateListOf<JobPost>()
        private set

    var recommendedJobs = mutableStateListOf<JobPost>()
        private set

    // --- MESSAGING STATE ---
    var unreadMessagesCount by mutableIntStateOf(0)
        private set

    var activeChats = mutableStateListOf<ChatSummary>()
        private set

    var chatsLoaded by mutableStateOf(false)
        private set

    var chatsError by mutableStateOf<String?>(null)
        private set

    private var chatsRegistration: ListenerRegistration? = null
    private var observedUserId: String? = null
    private var lastBadgeCount = -1

    init {
        fetchJobs()
        fetchExternalJobs()
    }

    /**
     * One listener for the whole app. Unread counts are derived from the chat
     * summaries so the badge can never drift out of sync with the inbox.
     */
    fun startObservingChats(userId: String, context: Context) {
        if (userId.isEmpty()) return
        if (chatsRegistration != null && observedUserId == userId) return

        chatsRegistration?.remove()
        observedUserId = userId
        lastBadgeCount = -1

        val appContext = context.applicationContext
        chatsRegistration = ChatRepository.listenChats(
            userId = userId,
            onUpdate = { chats ->
                activeChats.clear()
                activeChats.addAll(chats)
                chatsError = null
                chatsLoaded = true

                val unread = ChatRepository.totalUnread(chats, userId)
                unreadMessagesCount = unread
                if (lastBadgeCount != unread) {
                    lastBadgeCount = unread
                    ShortcutBadger.applyCount(appContext, unread)
                }
            },
            onError = { message ->
                chatsError = message
                chatsLoaded = true
            }
        )
    }

    fun restartChats(userId: String, context: Context) {
        chatsRegistration?.remove()
        chatsRegistration = null
        chatsLoaded = false
        startObservingChats(userId, context)
    }

    override fun onCleared() {
        chatsRegistration?.remove()
        chatsRegistration = null
        super.onCleared()
    }

    fun searchJobs(query: String) {
        if (query.isBlank()) {
            resetFilters()
            return
        }
        val filter: (JobPost) -> Boolean = {
            it.title.contains(query, ignoreCase = true) ||
            it.location.contains(query, ignoreCase = true) ||
            it.companyName.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
        }
        filteredFirestoreJobs.clear()
        filteredFirestoreJobs.addAll(firestoreJobs.filter(filter))
        filteredExternalJobs.clear()
        filteredExternalJobs.addAll(externalJobs.filter(filter))
    }

    private fun resetFilters() {
        filteredFirestoreJobs.clear()
        filteredFirestoreJobs.addAll(firestoreJobs)
        filteredExternalJobs.clear()
        filteredExternalJobs.addAll(externalJobs)
    }

    fun refreshRecommendations(userRegion: String) {
        if (userRegion.isBlank()) return
        val allJobs = firestoreJobs + externalJobs
        val recommended = allJobs.filter {
            it.location.contains(userRegion, ignoreCase = true)
        }.take(5)
        recommendedJobs.clear()
        recommendedJobs.addAll(recommended)
    }

    fun fetchJobs() {
        db.collection("jobs").addSnapshotListener { snapshot, error ->
            if (error != null) return@addSnapshotListener
            if (snapshot != null) {
                try {
                    val items = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(JobPost::class.java)?.copy(id = doc.id)
                    }.sortedByDescending { it.postedDate }
                    firestoreJobs.clear()
                    firestoreJobs.addAll(items)
                    filteredFirestoreJobs.clear()
                    filteredFirestoreJobs.addAll(items)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun fetchExternalJobs() {
        viewModelScope.launch {
            try {
                val response = jobicyService.getRemoteJobs(count = 50)
                val items = response.jobs?.map { remoteJob ->
                    JobPost(
                        id = "remote_${remoteJob.id}",
                        title = remoteJob.jobTitle ?: "",
                        companyName = remoteJob.companyName ?: "",
                        location = remoteJob.jobGeo ?: "Remote",
                        description = remoteJob.jobDescription ?: "",
                        postedDate = remoteJob.pubDate ?: "",
                        jobUrl = remoteJob.url ?: "",
                        verificationLevel = 1
                    )
                } ?: emptyList()
                externalJobs.clear()
                externalJobs.addAll(items)
                filteredExternalJobs.clear()
                filteredExternalJobs.addAll(items)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error fetching jobs", e)
            }
        }
    }

    fun incrementJobViews(jobId: String, context: Context) {
        if (jobId.isEmpty() || jobId.startsWith("remote_")) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("viewed_$jobId", false)) {
            db.collection("jobs").document(jobId).update("views", FieldValue.increment(1))
                .addOnSuccessListener {
                    prefs.edit().putBoolean("viewed_$jobId", true).apply()
                }
        }
    }

    fun postJob(job: JobPost, verificationLevel: Int) {
        val rawPhone = job.phoneNumber.replace("+", "").replace(" ", "").replace("-", "")
        val cleanPhone = if (rawPhone.startsWith("0")) "255" + rawPhone.substring(1) else rawPhone
        val docRef = db.collection("jobs").document()
        val formattedJob = job.copy(
            id = docRef.id,
            phoneNumber = cleanPhone,
            postedDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date()),
            verificationLevel = verificationLevel,
            views = 0
        )
        docRef.set(formattedJob)
    }

    /** Fetches the caller's blocked users list from Firestore. */
    fun fetchBlockedUsers(
        userId: String,
        onUpdate: (List<String>) -> Unit
    ) {
        if (userId.isBlank()) {
            onUpdate(emptyList())
            return
        }
        db.collection("users").document(userId).get()
            .addOnSuccessListener { snapshot ->
                @Suppress("UNCHECKED_CAST")
                onUpdate(
                    (snapshot.get("blockedUsers") as? List<*>)
                        ?.filterIsInstance<String>() ?: emptyList()
                )
            }
            .addOnFailureListener { onUpdate(emptyList()) }
    }
}
