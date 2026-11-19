package com.example.getconnected

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

/**
 * Single source of truth for all messaging reads/writes.
 *
 * Firestore layout:
 *   chats/{chatId}                 -> participants, last message, per-user unread counters
 *   chats/{chatId}/messages/{id}   -> individual messages
 */
object ChatRepository {
    /** Live listener window: newest messages kept in sync (older pages are one-shot). */
    private const val MESSAGES_PAGE_SIZE = 50L
    /** Older messages fetched per "scroll up" page. */
    private const val OLDER_PAGE_SIZE = 40L

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun chatRef(chatId: String) = db.collection("chats").document(chatId)

    fun listenChats(
        userId: String,
        onUpdate: (List<ChatSummary>) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration {
        return db.collection("chats")
            .whereArrayContains("users", userId)
            .orderBy("lastTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error.message ?: "Imeshindikana kupakia mazungumzo")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val chats = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(ChatSummary::class.java)?.copy(id = doc.id)
                }
                onUpdate(chats)
            }
    }

    fun listenMessages(
        chatId: String,
        currentUserId: String,
        onUpdate: (List<Message>) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration {
        return chatRef(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(MESSAGES_PAGE_SIZE)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error.message ?: "Imeshindikana kupakia ujumbe")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val messages = snapshot.documents
                    .mapNotNull { it.toObject(Message::class.java) }
                    .filter { it.isVisibleTo(currentUserId) }
                    .sortedBy { it.timestamp }

                onUpdate(messages)
            }
    }

    /**
     * One-shot page of messages older than [oldestTimestamp]. Used to backfill the
     * history above the live window without keeping another listener open (free-tier
     * friendly: a fixed read per scroll page).
     */
    fun loadOlderMessages(
        chatId: String,
        currentUserId: String,
        oldestTimestamp: Long,
        onComplete: (List<Message>, Exception?) -> Unit
    ) {
        if (oldestTimestamp <= 0L) {
            onComplete(emptyList(), null)
            return
        }
        chatRef(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .endBefore(oldestTimestamp)
            .limit(OLDER_PAGE_SIZE)
            .get()
            .addOnSuccessListener { snapshot ->
                val older = snapshot.documents
                    .mapNotNull { it.toObject(Message::class.java) }
                    .filter { it.isVisibleTo(currentUserId) }
                onComplete(older, null)
            }
            .addOnFailureListener { onComplete(emptyList(), it) }
    }

    /**
     * Writes a message (text, image, or forwarded) and updates the conversation
     * summary atomically enough for the inbox to stay in sync. The chat document is
     * created first because Firestore rules validate message creation against the
     * parent chat. Also clears the sender's own "hidden" flag so a re-sent chat
     * reappears in their inbox.
     */
    fun sendMessage(
        chatId: String,
        senderId: String,
        receiverId: String,
        content: String,
        type: String = "text",
        replyTo: Map<String, String> = emptyMap(),
        forwarded: Boolean = false,
        onComplete: (Exception?) -> Unit
    ) {
        val text = content.trim()
        if (text.isEmpty() || senderId.isBlank() || receiverId.isBlank()) {
            onComplete(IllegalArgumentException("Ujumbe si sahihi"))
            return
        }

        val chatRef = chatRef(chatId)
        val messageRef = chatRef.collection("messages").document()
        val timestamp = System.currentTimeMillis()

        val message = Message(
            id = messageRef.id,
            senderId = senderId,
            receiverId = receiverId,
            content = text,
            type = type,
            timestamp = timestamp,
            chatId = chatId,
            replyTo = replyTo,
            forwarded = forwarded
        )

        // Inbox preview must never be a raw media URL — photo messages store a
        // Cloudinary URL as content, which would flood the conversation list.
        val preview = if (type == "image") "📷 Picha" else text

        val summary = mutableMapOf<String, Any>(
            "id" to chatId,
            "users" to listOf(senderId, receiverId).sorted(),
            "lastMessage" to preview,
            "lastSenderId" to senderId,
            "lastTimestamp" to timestamp,
            "updatedAt" to FieldValue.serverTimestamp(),
            "hiddenBy.$senderId" to FieldValue.delete()
        )

        chatRef.set(summary, SetOptions.merge())
            .addOnSuccessListener {
                val batch = db.batch()
                batch.set(messageRef, message)
                batch.set(
                    chatRef,
                    mapOf(
                        "lastMessage" to preview,
                        "lastSenderId" to senderId,
                        "lastTimestamp" to timestamp,
                        "unreadCount.$receiverId" to FieldValue.increment(1)
                    ),
                    SetOptions.merge()
                )
                batch.commit()
                    .addOnSuccessListener { onComplete(null) }
                    .addOnFailureListener { onComplete(it) }
            }
            .addOnFailureListener { onComplete(it) }
    }

    /** Sets or clears the caller's reaction emoji on a message (single per user). */
    fun reactToMessage(
        chatId: String,
        messageId: String,
        userId: String,
        emoji: String?,
        onComplete: ((Exception?) -> Unit)? = null
    ) {
        val value: Any = if (emoji.isNullOrEmpty()) FieldValue.delete() else emoji
        // update() interprets the dotted key as a FIELD PATH, so this lands as a
        // nested reactions map { uid: emoji } — which is exactly what
        // Message.reactions deserialises (set(merge) stored a flat literal
        // "reactions.<uid>" key instead: rules denied retries AND the UI could
        // never render it).
        chatRef(chatId).collection("messages").document(messageId)
            .update(mapOf("reactions.$userId" to value))
            .addOnSuccessListener { onComplete?.invoke(null) }
            .addOnFailureListener { onComplete?.invoke(it) }
    }

    /** Copies [message] into the caller's chat with [otherUserId]. */
    fun forwardMessage(
        chatId: String,
        senderId: String,
        otherUserId: String,
        message: Message,
        onComplete: (Exception?) -> Unit
    ) {
        val preview = when (message.type) {
            "image" -> "📷 Picha"
            else -> message.content
        }
        sendMessage(
            chatId = chatId,
            senderId = senderId,
            receiverId = otherUserId,
            content = preview,
            type = message.type,
            forwarded = true,
            onComplete = onComplete
        )
    }

    /** Archive / mute / hide toggles: one map-field write per tap (free-tier cheap). */
    fun setChatFlag(
        chatId: String,
        userId: String,
        flag: String,
        enabled: Boolean
    ) {
        chatRef(chatId)
            .set(
                mapOf(
                    if (enabled) "$flag.$userId" to true else "$flag.$userId" to FieldValue.delete()
                ),
                SetOptions.merge()
            )
            .addOnFailureListener { }
    }

    fun setArchived(chatId: String, userId: String, archived: Boolean) =
        setChatFlag(chatId, userId, "archivedBy", archived)

    fun setMuted(chatId: String, userId: String, muted: Boolean) =
        setChatFlag(chatId, userId, "mutedBy", muted)

    fun setHidden(chatId: String, userId: String, hidden: Boolean) =
        setChatFlag(chatId, userId, "hiddenBy", hidden)

    private var lastSeenWriteAt = 0L

    /**
     * Records "last seen" for presence. Throttled to one write per minute so the
     * 5-minute heartbeat never stacks up writes while the app is foregrounded.
     */
    fun updateLastSeen(userId: String, force: Boolean = false) {
        if (userId.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSeenWriteAt < 60_000L) return
        lastSeenWriteAt = now
        db.collection("users").document(userId)
            .set(mapOf("lastSeenAt" to now), SetOptions.merge())
            .addOnFailureListener { }
    }

    /** Stores an FCM registration token (deduped by arrayUnion). */
    fun saveFcmToken(userId: String, token: String) {
        if (userId.isBlank() || token.isBlank()) return
        db.collection("users").document(userId)
            .set(mapOf("fcmTokens" to FieldValue.arrayUnion(token)), SetOptions.merge())
            .addOnFailureListener { }
    }

    /** Blocks or unblocks [otherUserId] from the caller's perspective. */
    fun setBlocked(
        currentUserId: String,
        otherUserId: String,
        blocked: Boolean,
        onComplete: ((Exception?) -> Unit)? = null
    ) {
        db.collection("users").document(currentUserId)
            .set(
                mapOf(
                    if (blocked) "blockedUsers" to FieldValue.arrayUnion(otherUserId)
                    else "blockedUsers" to FieldValue.arrayRemove(otherUserId)
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener { onComplete?.invoke(null) }
            .addOnFailureListener { onComplete?.invoke(it) }
    }

    /**
     * Marks every incoming message in [messages] as read, resets the per-chat unread
     * counter, and records how far this user has read so the sender sees a read tick
     * in their inbox. Safe to call repeatedly; already-read messages are skipped.
     */
    fun markChatRead(
        chatId: String,
        currentUserId: String,
        otherUserId: String,
        messages: List<Message>,
        onComplete: ((Exception?) -> Unit)? = null
    ) {
        val chatRef = chatRef(chatId)
        val incoming = messages.filter {
            it.senderId == otherUserId &&
                it.isVisibleTo(currentUserId) &&
                !it.readBy.contains(currentUserId)
        }

        val batch = db.batch()
        incoming.take(400).forEach { message ->
            batch.set(
                chatRef.collection("messages").document(message.id),
                mapOf("readBy" to FieldValue.arrayUnion(currentUserId)),
                SetOptions.merge()
            )
        }

        // Newest message this user has now seen; drives the ✓✓ tick in the sender's inbox.
        val readUpTo = messages.maxOfOrNull { it.timestamp } ?: 0L
        val chatUpdates = mutableMapOf<String, Any>("unreadCount.$currentUserId" to 0)
        if (readUpTo > 0L) {
            chatUpdates["lastReadBy.$currentUserId"] = readUpTo
        }
        batch.set(chatRef, chatUpdates, SetOptions.merge())

        batch.commit()
            .addOnSuccessListener { onComplete?.invoke(null) }
            .addOnFailureListener { onComplete?.invoke(it) }
    }

    /**
     * Removes a message from the caller's own view, or for everyone when the caller
     * sent it and [forEveryone] is true. Only the sender may delete the document
     * (enforced by security rules), so "delete for me" is always a local hide.
     */
    fun deleteMessage(
        chatId: String,
        message: Message,
        currentUserId: String,
        forEveryone: Boolean,
        onComplete: (Exception?) -> Unit
    ) {
        val chatRef = chatRef(chatId)
        val messageRef = chatRef.collection("messages").document(message.id)

        val isOwnMessage = message.senderId == currentUserId
        if (isOwnMessage && forEveryone) {
            messageRef.delete()
                .addOnSuccessListener {
                    chatRef.get().addOnSuccessListener { doc ->
                        if (doc.getLong("lastTimestamp") == message.timestamp) {
                            chatRef.set(
                                mapOf(
                                    "lastMessage" to "Ujumbe umefutwa",
                                    "lastSenderId" to ""
                                ),
                                SetOptions.merge()
                            )
                        }
                    }
                    onComplete(null)
                }
                .addOnFailureListener { onComplete(it) }
        } else {
            messageRef.set(
                mapOf("deletedFor" to FieldValue.arrayUnion(currentUserId)),
                SetOptions.merge()
            )
                .addOnSuccessListener { onComplete(null) }
                .addOnFailureListener { onComplete(it) }
        }
    }

    private var lastTypingWriteAt = 0L

    /**
     * Announces that [userId] is typing in [chatId]. Throttled to one write per 3s.
     * No-op until the chat document exists (it is created with the first message).
     */
    fun setTyping(chatId: String, userId: String, chatExists: Boolean) {
        if (!chatExists || userId.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastTypingWriteAt < 3000) return
        lastTypingWriteAt = now
        chatRef(chatId)
            .set(mapOf("typing.$userId" to now), SetOptions.merge())
            .addOnFailureListener { }
    }

    /** Clears the caller's typing flag when leaving the chat. */
    fun clearTyping(chatId: String, userId: String, chatExists: Boolean) {
        if (!chatExists || userId.isBlank()) return
        lastTypingWriteAt = 0L
        chatRef(chatId)
            .set(mapOf("typing.$userId" to 0L), SetOptions.merge())
            .addOnFailureListener { }
    }

    /** Loads the caller's block list (one read on chat open). */
    fun fetchBlockedUsers(
        userId: String,
        onUpdate: (List<String>) -> Unit
    ) {
        if (userId.isBlank()) return
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

    fun totalUnread(chats: List<ChatSummary>, userId: String): Int =
        chats.sumOf { it.unreadFor(userId) }

    fun observeUserProfile(
        userId: String,
        onUpdate: (fullName: String, imageUrl: String) -> Unit
    ): ListenerRegistration {
        return db.collection("users").document(userId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    onUpdate(
                        snapshot.getString("fullName") ?: "",
                        snapshot.getString("profileImage") ?: ""
                    )
                }
            }
    }

    fun fetchUserProfile(
        userId: String,
        onUpdate: (fullName: String, imageUrl: String) -> Unit,
        onFailure: (() -> Unit)? = null
    ): Task<DocumentSnapshot>? {
        if (userId.isBlank()) return null
        return db.collection("users").document(userId).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    onUpdate(
                        snapshot.getString("fullName") ?: "",
                        snapshot.getString("profileImage") ?: ""
                    )
                } else {
                    onFailure?.invoke()
                }
            }
            .addOnFailureListener { onFailure?.invoke() }
    }
}