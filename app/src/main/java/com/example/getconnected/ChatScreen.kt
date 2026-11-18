package com.example.getconnected

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.URLUtil
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

// Helper data classes for chat features
data class MediaFile(val uri: Uri, val displayName: String, val mimeType: String)
data class LinkPreview(val title: String, val description: String, val url: String, val imageUrl: String? = null)

private sealed interface ChatItem {
    data class DateHeader(val label: String, val key: String) : ChatItem
    data class MessageItem(val message: Message) : ChatItem
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    receiverId: String, 
    receiverName: String, 
    onBack: () -> Unit,
    mainViewModel: MainViewModel
) {
    val auth = FirebaseAuth.getInstance()
    val currentUserId = auth.currentUser?.uid ?: ""
    val chatId = chatIdFor(currentUserId, receiverId)

    var messages by remember(chatId) { mutableStateOf<List<Message>>(emptyList()) }
    var pending by remember(chatId) { mutableStateOf<List<Message>>(emptyList()) }
    var textInput by remember(chatId) { mutableStateOf("") }
    var isLoading by remember(chatId) { mutableStateOf(true) }
    var loadError by remember(chatId) { mutableStateOf<String?>(null) }
    var reloadKey by remember(chatId) { mutableIntStateOf(0) }
    var chatUnread by remember(chatId) { mutableIntStateOf(0) }

    var peerName by remember(receiverId) { mutableStateOf(receiverName.ifBlank { "Mtumiaji" }) }
    var peerImage by remember(receiverId) { mutableStateOf("") }

    // New chat features state
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var showScrollDown by remember { mutableStateOf(false) }
    var firstLoad by remember(chatId) { mutableStateOf(true) }
    var reactionTarget by remember { mutableStateOf<Message?>(null) }
    var showReplyTo by remember { mutableStateOf<Message?>(null) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showForwardPicker by remember { mutableStateOf(false) }
    var showBlockMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchResults by remember { mutableStateOf(false) }
    var hasLoadedOlderMessages by remember { mutableStateOf(false) }
    var showImageViewer by remember { mutableStateOf(false) }
    var currentImageUrl by remember { mutableStateOf("") }
    var messageToForward by remember { mutableStateOf<Message?>(null) }
    var blockedUsers by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastSeenByPeer by remember { mutableStateOf<Long>(0L) }

    // Peer typing state + chat document existence (needed before writing typing flags).
    var peerTypingAt by remember(receiverId) { mutableLongStateOf(0L) }
    var chatDocExists by remember(chatId) { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { pickedUri ->
            scope.launch {
                uploadImageToCloudinary(
                    uri = pickedUri,
                    chatId = chatId,
                    senderId = currentUserId,
                    receiverId = receiverId,
                    context = context,
                    scope = this,
                    onError = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    }
                )
            }
        }
    }

    // Request notification permission for FCM
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            requestFcmToken(currentUserId)
        }
    }

    // Request storage permission for image picker
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            imagePickerLauncher.launch("image/*")
        }
    }

    // Load blocked users on startup
    DisposableEffect(currentUserId) {
        if (currentUserId.isNotBlank()) {
            mainViewModel.fetchBlockedUsers(currentUserId) { blocked ->
                blockedUsers = blocked
            }
        }
        onDispose {}
    }

    // Request notification permission on first app launch
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ActivityCompat.checkSelfPermission(context, permission) != 
                PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
        requestFcmToken(currentUserId)
    }

    // Update presence (last seen)
    LaunchedEffect(Unit) {
        while (true) {
            ChatRepository.updateLastSeen(currentUserId, force = false)
            delay(300_000) // 5 minutes
        }
    }

    // Debug: show current blocked users in console
    LaunchedEffect(blockedUsers) {
        Log.d("ChatScreen", "Blocked users: $blockedUsers")
    }

    BackHandler { onBack() }

    // Receiver profile (name + avatar) is denormalised enough for 1:1 chats.
    DisposableEffect(receiverId) {
        val registration = ChatRepository.observeUserProfile(receiverId) { name, image ->
            if (name.isNotBlank()) peerName = name
            peerImage = image
        }
        onDispose { registration.remove() }
    }

    // Live message stream (listener is always disposed with the screen).
    DisposableEffect(chatId, currentUserId, reloadKey) {
        isLoading = true
        loadError = null
        val registration = ChatRepository.listenMessages(
            chatId = chatId,
            currentUserId = currentUserId,
            onUpdate = { list ->
                messages = list
                // Drop pending bubbles once the real message lands in Firestore so a
                // message never flashes away and back (or shows twice).
                if (pending.isNotEmpty()) {
                    val unmatched = list.toMutableList()
                    pending = pending.filter { candidate ->
                        val index = unmatched.indexOfFirst {
                            it.senderId == candidate.senderId &&
                                it.content == candidate.content &&
                                abs(it.timestamp - candidate.timestamp) < 5000
                        }
                        if (index >= 0) {
                            unmatched.removeAt(index)
                            false
                        } else {
                            true
                        }
                    }
                }
                isLoading = false
                loadError = null
            },
            onError = { error ->
                loadError = error
                isLoading = false
            }
        )
        onDispose { registration.remove() }
    }

    // Keep the local unread counter in sync with the chat document, track whether the
    // chat exists yet, and pick up the peer's typing flag.
    DisposableEffect(chatId) {
        val registration = ChatRepository.chatRef(chatId).addSnapshotListener { snapshot, _ ->
            chatDocExists = snapshot?.exists() == true
            val map = snapshot?.get("unreadCount") as? Map<*, *>
            chatUnread = (map?.get(currentUserId) as? Number)?.toInt() ?: 0
            val typingMap = snapshot?.get("typing") as? Map<*, *>
            peerTypingAt = (typingMap?.get(receiverId) as? Number)?.toLong() ?: 0L
        }
        onDispose {
            registration.remove()
            ChatRepository.clearTyping(chatId, currentUserId, chatDocExists)
        }
    }

    // Auto-clear the typing flag ~4s after the peer's last keystroke write.
    LaunchedEffect(peerTypingAt) {
        if (peerTypingAt <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() - peerTypingAt < 4000) {
            delay(500)
        }
        peerTypingAt = 0L
    }
    val isPeerTyping = peerTypingAt > 0L

    val hasUnreadIncoming = remember(messages, currentUserId, receiverId) {
        messages.any { it.senderId == receiverId && !it.readBy.contains(currentUserId) }
    }

    // Mark as read when there is something unread (also resets the chat counter).
    LaunchedEffect(chatId, hasUnreadIncoming, chatUnread, messages.size) {
        if (currentUserId.isBlank() || receiverId.isBlank()) return@LaunchedEffect
        if (hasUnreadIncoming || chatUnread > 0) {
            ChatRepository.markChatRead(
                chatId = chatId,
                currentUserId = currentUserId,
                otherUserId = receiverId,
                messages = messages
            )
        }
    }

    val items = remember(messages, pending, currentUserId, searchQuery, showSearchResults) {
        val query = if (showSearchResults) searchQuery.trim() else ""
        val all = (messages + pending)
            .sortedBy { it.timestamp }
            .filter { query.isEmpty() || it.content.contains(query, ignoreCase = true) }
        buildList {
            var lastDay = ""
            all.forEach { message ->
                val day = dayKey(message.timestamp)
                if (day != lastDay) {
                    add(ChatItem.DateHeader(dayLabel(message.timestamp), day))
                    lastDay = day
                }
                add(ChatItem.MessageItem(message))
            }
        }
    }

    LaunchedEffect(items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        if (firstLoad || !showScrollDown) {
            listState.scrollToItem(items.size - 1)
            firstLoad = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                val total = listState.layoutInfo.totalItemsCount
                showScrollDown = total > 4 && lastVisible < total - 2
            }
    }

    fun sendMessage() {
        val text = textInput.trim()
        if (text.isEmpty() && currentUserId.isBlank() || receiverId.isBlank()) return

        triggerHapticFeedback(haptic)

        val tempId = "pending_${UUID.randomUUID()}"
        val timestamp = System.currentTimeMillis()
        
        // Handle reply-to
        val replySource = showReplyTo
        val replyTo = if (replySource != null) {
            mapOf("senderId" to replySource.senderId, "content" to replySource.content)
        } else {
            emptyMap()
        }
        val isForwarded = messageToForward != null

        pending = pending + Message(
            id = tempId,
            senderId = currentUserId,
            receiverId = receiverId,
            content = text,
            timestamp = timestamp,
            chatId = chatId,
            replyTo = replyTo,
            forwarded = isForwarded
        )
        textInput = ""
        // Clear reply and forward state
        showReplyTo = null
        messageToForward = null

        val sendType = if (text.startsWith("/")) "command" else "text"

        ChatRepository.sendMessage(
            chatId = chatId,
            senderId = currentUserId,
            receiverId = receiverId,
            content = text,
            type = sendType,
            replyTo = replyTo,
            forwarded = isForwarded,
            onComplete = { error ->
                if (error != null) {
                    // Remove the optimistic bubble and put the text back — but only if the
                    // composer is still empty, so we never overwrite newer typing.
                    pending = pending.filterNot { it.id == tempId }
                    if (textInput.isBlank()) textInput = text
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.chat_send_failed, error.message ?: "")
                        )
                    }
                }
                // On success the pending bubble stays until the live listener delivers the
                // real message, which avoids the bubble flashing away and reappearing.
            }
        )
    }

    fun sendImage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // For Android 9 and below, need storage permission
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ActivityCompat.checkSelfPermission(context, permission) != 
                PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(permission)
                return
            }
        }
        imagePickerLauncher.launch("image/*")
    }

    fun sendLink(link: String) {
        if (link.isBlank() || currentUserId.isBlank() || receiverId.isBlank()) return

        val preview = extractLinkPreview(link)
        if (preview == null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.chat_invalid_link)
                )
            }
            return
        }

        val tempId = "pending_${UUID.randomUUID()}"
        val timestamp = System.currentTimeMillis()
        pending = pending + Message(
            id = tempId,
            senderId = currentUserId,
            receiverId = receiverId,
            content = link,
            timestamp = timestamp,
            chatId = chatId
        )

        ChatRepository.sendMessage(
            chatId = chatId,
            senderId = currentUserId,
            receiverId = receiverId,
            content = link,
            type = "link",
            onComplete = { error ->
                if (error != null) {
                    pending = pending.filterNot { it.id == tempId }
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.chat_send_failed, error.message ?: "")
                        )
                    }
                }
            }
        )
    }

    fun reactToMessage(message: Message, emoji: String) {
        if (currentUserId.isBlank()) return
        scope.launch {
            try {
                ChatRepository.reactToMessage(
                    chatId = chatId,
                    messageId = message.id,
                    userId = currentUserId,
                    emoji = emoji,
                    onComplete = { error ->
                        if (error != null) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.chat_reaction_failed, error.message ?: "")
                                )
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("ChatScreen", "Error reacting to message", e)
            }
        }
    }

    fun forwardMessage(message: Message) {
        messageToForward = message
        showForwardPicker = true
    }

    fun blockUser() {
        scope.launch {
            try {
                ChatRepository.setBlocked(
                    currentUserId = currentUserId,
                    otherUserId = receiverId,
                    blocked = !blockedUsers.contains(receiverId),
                    onComplete = { error ->
                        if (error != null) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.chat_block_failed, error.message ?: "")
                                )
                            }
                        } else {
                            // Refresh blocked users list
                            mainViewModel.fetchBlockedUsers(currentUserId) { blocked ->
                                blockedUsers = blocked
                            }
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        if (blockedUsers.contains(receiverId)) 
                                            R.string.user_blocked 
                                        else 
                                            R.string.user_unblocked
                                    )
                                )
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("ChatScreen", "Error blocking user", e)
            }
        }
    }

    fun loadOlderMessages() {
        if (!hasLoadedOlderMessages) {
            scope.launch {
                val oldestTimestamp = messages.minOfOrNull { it.timestamp } ?: 0L
                ChatRepository.loadOlderMessages(
                    chatId = chatId,
                    currentUserId = currentUserId,
                    oldestTimestamp = oldestTimestamp,
                    onComplete = { olderMessages, error ->
                        if (error != null) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.chat_load_older_failed, error.message ?: "")
                                )
                            }
                        } else if (olderMessages.isNotEmpty()) {
                            // Prepend older messages to the current list
                            messages = (olderMessages + messages).distinctBy { it.id }
                            hasLoadedOlderMessages = true
                        }
                    }
                )
            }
        }
    }

    fun markAllAsRead() {
        scope.launch {
            ChatRepository.markChatRead(
                chatId = chatId,
                currentUserId = currentUserId,
                otherUserId = receiverId,
                messages = messages
            )
        }
    }

    // Pagination: reaching the top of the list fetches the previous page once.
    LaunchedEffect(listState, messages.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstVisible ->
                if (firstVisible <= 1 && messages.isNotEmpty() && !hasLoadedOlderMessages) {
                    loadOlderMessages()
                }
            }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(imageUrl = peerImage, name = peerName, size = 38.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = peerName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    isPeerTyping -> "Anatumia…"
                                    hasUnreadIncoming -> "Ujumbe mpya"
                                    else -> "Get Connected"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPeerTyping) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Rudi")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showSearchResults = !showSearchResults
                        if (!showSearchResults) searchQuery = ""
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Tafuta kwenye chat")
                    }
                    IconButton(onClick = { showBlockMenu = true }) {
                        Icon(Icons.Default.Block, contentDescription = "Zuia mtumiaji")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            if (showSearchResults) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Tafuta kwenye ujumbe huu...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            showSearchResults = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Funga utafutaji")
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        bottomBar = {
            Column {
                // Reply preview strip (WhatsApp-style) above the composer.
                showReplyTo?.let { replied ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(34.dp)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Jibu ${if (replied.senderId == currentUserId) "ujumbe wako" else peerName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = replied.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showReplyTo = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Ondoa jibu")
                            }
                        }
                    }
                }
                ChatInputBar(
                    value = textInput,
                    onValueChange = {
                        textInput = it
                        if (it.isNotBlank()) {
                            ChatRepository.setTyping(chatId, currentUserId, chatDocExists)
                        }
                    },
                    onSend = ::sendMessage,
                    onAttach = ::sendImage
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                isLoading && items.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                loadError != null && items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { reloadKey++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Jaribu tena")
                        }
                    }
                }

                items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Anza mazungumzo",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tuma ujumbe kwa $peerName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = 12.dp
                        )
                    ) {
                        items(items, key = { item ->
                            when (item) {
                                is ChatItem.DateHeader -> "date_${item.key}"
                                is ChatItem.MessageItem -> "msg_${item.message.id}"
                            }
                        }) { item ->
                            when (item) {
                                is ChatItem.DateHeader -> DateSeparator(item.label)
                                is ChatItem.MessageItem -> {
                                    val isMe = item.message.senderId == currentUserId
                                    MessageBubble(
                                        message = item.message,
                                        isMe = isMe,
                                        isPending = item.message.id.startsWith("pending_"),
                                        currentUserId = currentUserId,
                                        receiverId = receiverId,
                                        onLongClick = { selectedMessage = item.message },
                                        onImageClick = { url ->
                                            currentImageUrl = url
                                            showImageViewer = true
                                        },
                                        scope = scope
                                    )
                                }
                            }
                        }
                    }

                    if (showScrollDown) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem(items.size - 1)
                                    showScrollDown = false
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Nenda chini")
                        }
                    }
                }
            }
        }
    }

    val selected = selectedMessage
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedMessage = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = selected.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                HorizontalDivider()

                ListItem(
                    headlineContent = { Text("Jibu") },
                    leadingContent = { Icon(Icons.Default.Reply, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showReplyTo = selected
                        selectedMessage = null
                    }
                )

                ListItem(
                    headlineContent = { Text("Weka hisia") },
                    leadingContent = { Icon(Icons.Default.AddReaction, contentDescription = null) },
                    modifier = Modifier.clickable {
                        reactionTarget = selected
                        selectedMessage = null
                        showEmojiPicker = true
                    }
                )

                ListItem(
                    headlineContent = { Text("Tuma kwa mwingine") },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                    modifier = Modifier.clickable {
                        forwardMessage(selected)
                        selectedMessage = null
                    }
                )

                ListItem(
                    headlineContent = { Text("Nakili ujumbe") },
                    leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickable {
                        val text = selected.content
                        selectedMessage = null
                        val clipData = android.content.ClipData.newPlainText("GetConnected", text)
                        scope.launch { clipboard.setClipEntry(clipData.toClipEntry()) }
                    }
                )

                if (selected.senderId == currentUserId) {
                    ListItem(
                        headlineContent = { Text("Futa kwa wote") },
                        leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                        modifier = Modifier.clickable {
                            val message = selected
                            selectedMessage = null
                            ChatRepository.deleteMessage(
                                chatId,
                                message,
                                currentUserId,
                                forEveryone = true
                            ) { error ->
                                if (error != null) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(
                                                R.string.chat_delete_failed,
                                                error.message ?: ""
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                ListItem(
                    headlineContent = { Text("Futa kwangu") },
                    leadingContent = { Icon(Icons.Default.Delete, contentDescription = null) },
                    modifier = Modifier.clickable {
                        val message = selected
                        selectedMessage = null
                        ChatRepository.deleteMessage(
                            chatId,
                            message,
                            currentUserId,
                            forEveryone = false
                        ) { error ->
                            if (error != null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(
                                            R.string.chat_delete_failed,
                                            error.message ?: ""
                                        )
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    // Emoji reaction picker — reacts on the message captured in reactionTarget.
    if (showEmojiPicker) {
        EmojiPicker(
            onEmojiSelected = { emoji ->
                showEmojiPicker = false
                reactionTarget?.let { reactToMessage(it, emoji) }
                reactionTarget = null
            },
            onDismiss = {
                showEmojiPicker = false
                reactionTarget = null
            }
        )
    }

    // Forward target picker.
    if (showForwardPicker) {
        ForwardPicker(
            currentMessage = messageToForward,
            onForward = { message ->
                showForwardPicker = false
                // Relayed into this conversation; sendMessage reads messageToForward
                // for the forwarded flag and clears both states itself.
                textInput = message.content
                showReplyTo = null
                sendMessage()
            },
            onDismiss = {
                showForwardPicker = false
                messageToForward = null
            }
        )
    }

    // Full-screen image viewer.
    if (showImageViewer) {
        ImageViewer(
            imageUrl = currentImageUrl,
            onDismiss = { showImageViewer = false }
        )
    }

    // Block confirmation dialog.
    if (showBlockMenu) {
        AlertDialog(
            onDismissRequest = { showBlockMenu = false },
            title = { Text("Zuia $peerName?") },
            text = { Text("Hutaweza kupokea ujumbe wake kwenye chat hii hadi umfungue tena.") },
            confirmButton = {
                TextButton(onClick = {
                    showBlockMenu = false
                    blockUser()
                }) { Text("Zuia") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockMenu = false }) { Text("Ghairi") }
            }
        )
    }
}

@Composable
private fun DateSeparator(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isMe: Boolean,
    isPending: Boolean,
    currentUserId: String,
    receiverId: String,
    onLongClick: () -> Unit,
    onImageClick: (String) -> Unit,
    scope: CoroutineScope
) {
    val bubbleColor = when (message.type) {
        "image" -> if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        "link" -> if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant
        "reply" -> if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
        else -> if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isMe) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val timeColor = contentColor.copy(alpha = 0.7f)

    Column {
        // Reply quote indicator
        if (message.replyTo.isNotEmpty()) {
            ReplyQuoteIndicator(message = message, isMe = isMe)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isMe) 16.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 16.dp
                ),
                modifier = Modifier
                    .widthIn(max = if (message.type == "image") 250.dp else 300.dp)
                    .combinedClickable(
                        onClick = {
                            when (message.type) {
                                "image" -> onImageClick(message.content)
                                "link" -> {
                                    // Open link preview
                                }
                            }
                        },
                        onLongClick = onLongClick
                    )
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 10.dp,
                        top = 8.dp,
                        bottom = 6.dp
                    )
                ) {
                    // Forwarded label
                    if (message.forwarded) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = contentColor.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Forwarded",
                                color = contentColor.copy(alpha = 0.6f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Message content
                    when (message.type) {
                        "image" -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (message.content.startsWith("http")) {
                                    AsyncImage(
                                        model = message.content,
                                        contentDescription = "Picha",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    // Legacy placeholder message ("📷 Picha")
                                    Icon(
                                        imageVector = Icons.Default.Photo,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = contentColor.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        "link" -> {
                            LinkPreviewComponent(message = message, isMe = isMe)
                        }

                        else -> {
                            Text(
                                text = message.content,
                                color = contentColor,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    // Reactions
                    if (message.reactions.isNotEmpty()) {
                        ReactionsRow(message = message, isMe = isMe, scope = scope)
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = timeLabel(message.timestamp),
                            color = timeColor,
                            fontSize = 10.sp
                        )
                        if (isMe) {
                            Spacer(modifier = Modifier.width(3.dp))
                            MessageStatus(
                                message = message,
                                isPending = isPending,
                                receiverId = receiverId,
                                tint = timeColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyQuoteIndicator(message: Message, isMe: Boolean) {
    val bubbleColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(bubbleColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "💬 Reply",
                color = bubbleColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = message.replyTo["content"]?.take(30) ?: "",
                color = textColor.copy(alpha = 0.7f),
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ReactionsRow(message: Message, isMe: Boolean, scope: CoroutineScope) {
    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        message.reactions.entries.groupBy { it.value }.forEach { (emoji, users) ->
            Surface(
                modifier = Modifier.padding(end = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = emoji, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = users.size.toString(),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkPreviewComponent(message: Message, isMe: Boolean) {
    val preview = extractLinkPreview(message.content)
    if (preview != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { /* Open link */ },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = preview.title,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (preview.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = preview.description.take(60),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = preview.url.removePrefix("https://").removePrefix("http://"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                }
                if (preview.imageUrl != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = preview.imageUrl,
                        contentDescription = preview.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPicker(
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🔥", "✅", "❌", "⭐", "💯", "🙏")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Add Reaction", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            HorizontalDivider()
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(emojis) { emoji ->
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onEmojiSelected(emoji) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = emoji, fontSize = 24.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardPicker(
    onForward: (Message) -> Unit,
    onDismiss: () -> Unit,
    currentMessage: Message?
) {
    if (currentMessage != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Forward Message", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()

                // Show the message being forwarded
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = currentMessage.content.take(50) + if (currentMessage.content.length > 50) "..." else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                ListItem(
                    headlineContent = { Text("Forward") },
                    leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
                    modifier = Modifier.clickable {
                        onForward(currentMessage)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageViewer(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Picha", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Funga")
                }
            }
            HorizontalDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Picha kubwa",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                    )
                } else {
                    Text(
                        text = "Picha haipatikani",
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageStatus(
    message: Message,
    isPending: Boolean,
    receiverId: String,
    tint: Color
) {
    when {
        isPending -> Icon(
            imageVector = Icons.Default.AccessTime,
            contentDescription = "Inatuma",
            tint = tint,
            modifier = Modifier.size(13.dp)
        )

        message.readBy.contains(receiverId) -> Icon(
            imageVector = Icons.Default.DoneAll,
            contentDescription = "Imesomwa",
            tint = Color(0xFF7FDBFF),
            modifier = Modifier.size(15.dp)
        )

        else -> Icon(
            imageVector = Icons.Default.Done,
            contentDescription = "Imefika",
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { innerTextField ->
                        if (value.isEmpty()) {
                            Text(
                                text = "Andika ujumbe...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (onAttach != null) {
                IconButton(
                    onClick = onAttach,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        contentDescription = "Ambatisha picha",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Tuma"
                )
            }
        }
    }
}

@Composable
fun ChatAvatar(imageUrl: String, name: String, size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun dayKey(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

private fun dayLabel(timestamp: Long): String {
    val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    return when {
        sameDay(messageCalendar, today) -> "Leo"
        sameDay(messageCalendar, yesterday) -> "Jana"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun sameDay(first: Calendar, second: Calendar): Boolean =
    first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)

private fun timeLabel(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

/** Compresses an image before upload (free-tier friendly). */
private suspend fun compressImage(uri: Uri, context: Context): Uri {
    return withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val file = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
            val outputStream = file.outputStream()
            
            // TODO: Implement proper image compression here
            // For now, just copy the file (to be implemented later)
            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(file)
        } catch (e: Exception) {
            Log.e("ChatScreen", "Error compressing image", e)
            uri
        }
    }
}

/** Uploads an image to Cloudinary and sends it as a message. */
private suspend fun uploadImageToCloudinary(
    uri: Uri, 
    chatId: String, 
    senderId: String, 
    receiverId: String,
    context: Context,
    scope: CoroutineScope,
    onError: (String) -> Unit
) {
    try {
        // Check if Cloudinary is initialized
        MediaManager.get()
        
        // Compress image
        val compressedUri = compressImage(uri, context)
        
        // Upload to Cloudinary
        val uploadTask = MediaManager.get().upload(compressedUri)
            .unsigned("getconnected") // Use the unsigned preset from Cloudinary
            .callback(object : com.cloudinary.android.callback.UploadCallback {
                override fun onStart(requestId: String) {
                    Log.d("ChatScreen", "Image upload started: $requestId")
                }
                
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {
                    // Show upload progress if needed
                }
                
                override fun onSuccess(requestId: String, resultData: Map<*, *>?) {
                    resultData?.let { result ->
                        val imageUrl = result["secure_url"] as? String
                        if (!imageUrl.isNullOrBlank()) {
                            ChatRepository.sendMessage(
                                chatId = chatId,
                                senderId = senderId,
                                receiverId = receiverId,
                                // Store the real URL so the bubble can render/view it.
                                content = imageUrl,
                                type = "image",
                                onComplete = { error ->
                                    if (error != null) {
                                        onError(context.getString(R.string.chat_send_failed, error.message ?: ""))
                                    }
                                }
                            )
                        }
                    }
                }
                
                override fun onError(requestId: String, error: ErrorInfo) {
                    onError(context.getString(R.string.chat_upload_failed, error.description ?: ""))
                }

                override fun onReschedule(requestId: String, error: ErrorInfo) {
                    onError(context.getString(R.string.chat_upload_failed, error.description ?: ""))
                }
            })
            .dispatch()
            
    } catch (e: Exception) {
        Log.e("ChatScreen", "Error uploading image", e)
        onError(context.getString(R.string.chat_upload_failed, e.message ?: ""))
    }
}

/** Gets FCM token and saves it to Firestore. */
private fun requestFcmToken(userId: String) {
    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        if (!task.isSuccessful) return@addOnCompleteListener
        val token = task.result
        if (userId.isNotBlank() && token != null) {
            ChatRepository.saveFcmToken(userId, token)
        }
    }
}

/** Generates link preview from URL using Jsoup. */
private fun extractLinkPreview(url: String): LinkPreview? {
    return try {
        val connection = Jsoup.connect(url)
        val document = connection.get()
        val title = document.title() ?: ""
        val description = document.select("meta[name=\"description\"]")
            .first()?.attr("content") ?: ""
        val imageUrl = document.select("meta[property=\"og:image\"]")
            .attr("content") ?: ""
        
        if (title.isNotBlank() && url.isNotBlank()) {
            LinkPreview(title, description, url, if (imageUrl.isNotBlank()) imageUrl else null)
        } else {
            null
        }
    } catch (e: Exception) {
        Log.e("ChatScreen", "Error extracting link preview", e)
        null
    }
}

/** Haptic feedback utility (Compose API, not Android view constants). */
private fun triggerHapticFeedback(haptic: HapticFeedback) {
    try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    } catch (e: Exception) {
        Log.e("ChatScreen", "Haptic feedback error", e)
    }
}