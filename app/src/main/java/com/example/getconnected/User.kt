package com.example.getconnected

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import java.util.Date


data class UserProfile(
    val uid: String = "",
    val fullName: String = "",
    val email: String = "",
    val userType: String = "SEEKER",
    val region: String = "Dar es Salaam",
    val verificationLevel: Int = 0,
    val tinNumber: String = "",
    val tinImageUrl: String = "",
    val brelaImageUrl: String = "",
    val brelaNumber: String = "",
    val profileImage: String = "",
    // Work preferences (chosen during onboarding; owner-writable, editable in settings)
    /** Selected job fields (taaluma), e.g. "Ujenzi", "Afya". */
    val jobFields: List<String> = emptyList(),
    /** "NONE" | "CERT" | "DIPLOMA" | "BACHELOR" | "MASTER" | "PHD" */
    val workLevel: String = "",
    /** "NONE" | "<1" | "1_3" | "3_PLUS" */
    val experience: String = "",
    val onboardingComplete: Boolean = false,
    val onboardingSkipped: Boolean = false,
    // Messaging / presence (owner-writable; see firestore.rules)
    val blockedUsers: List<String> = emptyList(),
    val lastSeenAt: Long = 0L,
    val fcmTokens: List<String> = emptyList()
)

data class JobPost(
    val id: String = "",
    val title: String = "",
    @get:PropertyName("company")
    @set:PropertyName("company")
    var companyName: String = "",

    val location: String = "Tanzania",
    val salary: String = "",
    val description: String = "",
    val employerId: String = "",
    val phoneNumber: String = "",
    val postedDate: String = "",
    val views: Int = 0,
    val verificationLevel: Int = 0,
    val companyLogoUrl: String = "",
    val jobUrl: String = "",
    /** Job field (taaluma) chosen by the employer when posting; drives feed matching. */
    val field: String = "",
    /** Admin-flagged promoted job; rendered under "Matangazo". */
    val promoted: Boolean = false
)

data class Message(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val content: String = "",
    val type: String = "text",
    val timestamp: Long = System.currentTimeMillis(),
    val chatId: String = "",
    val readBy: List<String> = emptyList(),
    val deletedFor: List<String> = emptyList(),
    /** True when this message was relayed from another chat. */
    val forwarded: Boolean = false,
    /** Quoted message being replied to: senderId + snippet of content. */
    val replyTo: Map<String, String> = emptyMap(),
    /** uid -> emoji reaction (single reaction per user). */
    val reactions: Map<String, String> = emptyMap()
) {
    fun isVisibleTo(userId: String): Boolean = !deletedFor.contains(userId)

    fun isSeenBy(userId: String): Boolean = readBy.contains(userId)

    /** Emoji counts for the bubble label, most-reacted first. */
    fun reactionSummary(): List<Pair<String, Int>> =
        reactions.values.groupingBy { it }.eachCount()
            .toList().sortedByDescending { it.second }
}

data class Chat(
    @DocumentId val id: String = "",
    val users: List<String> = emptyList(),
    val lastMessage: String = "",
    val lastSenderId: String = "",
    val lastTimestamp: Long = System.currentTimeMillis(),
    val unreadCount: Map<String, Long> = emptyMap()
)

data class ChatSummary(
    @DocumentId val id: String = "",
    val users: List<String> = emptyList(),
    val lastMessage: String = "",
    val lastSenderId: String = "",
    val lastTimestamp: Long = 0L,
    val unreadCount: Map<String, Long> = emptyMap(),
    val lastReadBy: Map<String, Long> = emptyMap(),
    /** uid -> epoch ms while the user is actively typing (auto-expires client side). */
    val typing: Map<String, Long> = emptyMap(),
    /** uid -> true when the user archived this chat (hidden from main list). */
    val archivedBy: Map<String, Boolean> = emptyMap(),
    /** uid -> true when the user muted notifications for this chat. */
    val mutedBy: Map<String, Boolean> = emptyMap(),
    /** uid -> true when the user deleted/hidden the chat for themselves. */
    val hiddenBy: Map<String, Boolean> = emptyMap()
) {
    fun otherUserId(currentUserId: String): String =
        users.firstOrNull { it != currentUserId } ?: ""

    fun unreadFor(currentUserId: String): Int =
        (unreadCount[currentUserId] ?: 0L).toInt().coerceAtLeast(0)

    fun isTypingByOther(currentUserId: String): Boolean {
        val otherId = otherUserId(currentUserId)
        if (otherId.isEmpty()) return false
        val at = typing[otherId] ?: return false
        return System.currentTimeMillis() - at < 6_000L
    }

    fun isArchivedBy(currentUserId: String): Boolean =
        archivedBy[currentUserId] == true

    fun isMutedBy(currentUserId: String): Boolean =
        mutedBy[currentUserId] == true

    fun isHiddenBy(currentUserId: String): Boolean =
        hiddenBy[currentUserId] == true

    /** True when the other participant has opened/read the latest message. */
    fun lastMessageReadByOther(currentUserId: String): Boolean {
        val otherId = otherUserId(currentUserId)
        if (otherId.isEmpty() || lastTimestamp <= 0L) return false
        return (lastReadBy[otherId] ?: 0L) >= lastTimestamp
    }
}

/** Stable, order-independent chat id for a 1:1 conversation. */
fun chatIdFor(userA: String, userB: String): String =
    if (userA <= userB) "${userA}_${userB}" else "${userB}_${userA}"

/**
 * Work-preference vocabulary shared by onboarding, the profile editor and the
 * feed filters. Fields are stored as their visible Swahili labels so jobs and
 * profiles always match without a translation layer.
 */
object WorkPreferences {
    /** Flat field list covering skilled and unskilled work in Tanzania. */
    val FIELDS = listOf(
        "Ujenzi",        // construction / building trades
        "Mikono",        // artisans (fundi wa maji, umeme, uchoraji…)
        "Usafi",         // cleaning / housekeeping
        "Usafiri",       // driving / deliveries / boda
        "Kilimo",        // agriculture & livestock
        "Biashara",      // business & sales
        "Fedha",         // finance & accounting
        "Elimu",         // education & training
        "Afya",          // health & community care
        "Teknolojia",    // IT & computers
        "Upishi",        // hospitality & catering
        "Ubunifu",       // creative & media
        "Nishati",       // energy & mining
        "Sheria",        // law & public administration
        "Nyingine"       // anything else
    )

    val LEVELS = listOf("NONE", "CERT", "DIPLOMA", "BACHELOR", "MASTER", "PHD")
    val EXPERIENCES = listOf("NONE", "<1", "1_3", "3_PLUS")

    fun levelLabel(level: String): String = when (level) {
        "NONE" -> "Haina kiwango rasmi"
        "CERT" -> "Cheti / VETA"
        "DIPLOMA" -> "Diploma"
        "BACHELOR" -> "Bachelor"
        "MASTER" -> "Master"
        "PHD" -> "PhD"
        else -> ""
    }

    fun experienceLabel(exp: String): String = when (exp) {
        "NONE" -> "Haina uzoefu"
        "<1" -> "Chini ya mwaka"
        "1_3" -> "Miaka 1-3"
        "3_PLUS" -> "Zaidi ya miaka 3"
        else -> ""
    }
}

data class JobicyResponse(
    val success: Any? = null, // Can be bool or string sometimes
    val jobCount: Int = 0,
    val jobs: List<RemoteJob>? = emptyList()
)

data class RemoteJob(
    val id: Any? = null,
    val jobTitle: String? = "",
    val companyName: String? = "",
    val jobDescription: String? = "",
    val jobGeo: String? = "",
    val jobLevel: String? = "",
    val jobType: Any? = null,
    val jobCategory: Any? = null,
    val pubDate: String? = "",
    val url: String? = "",
    val companyLogo: String? = ""
)
