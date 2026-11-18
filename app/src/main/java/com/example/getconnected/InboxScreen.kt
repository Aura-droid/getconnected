package com.example.getconnected

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class ProfileLite(val name: String, val image: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(onChatClick: (String, String) -> Unit, viewModel: MainViewModel) {
    val context = LocalContext.current
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    val chats = viewModel.activeChats
    val isLoading = !viewModel.chatsLoaded
    val error = viewModel.chatsError

    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val profiles = remember { mutableStateMapOf<String, ProfileLite>() }

    // Pull-to-refresh: keep the indicator up until the restarted listener delivers.
    LaunchedEffect(refreshing, isLoading) {
        if (refreshing && !isLoading) refreshing = false
    }

    // Lazily hydrate the other participant's name/avatar once per chat.
    LaunchedEffect(chats, currentUserId) {
        chats.forEach { chat ->
            val otherId = chat.otherUserId(currentUserId)
            if (otherId.isNotEmpty() && !profiles.containsKey(otherId)) {
                profiles[otherId] = ProfileLite(name = "", image = "")
                ChatRepository.fetchUserProfile(
                    otherId,
                    onUpdate = { name, image ->
                        profiles[otherId] = ProfileLite(name = name, image = image)
                    },
                    onFailure = {
                        // Drop the placeholder so the next chat update retries the fetch
                        // instead of showing "Mtumiaji" forever.
                        profiles.remove(otherId)
                    }
                )
            }
        }
    }

    val filtered = remember(chats, query, currentUserId, profiles) {
        val needle = query.trim().lowercase(Locale.getDefault())
        chats.filter { chat ->
            if (needle.isEmpty()) {
                true
            } else {
                val otherId = chat.otherUserId(currentUserId)
                val name = profiles[otherId]?.name.orEmpty()
                name.lowercase(Locale.getDefault()).contains(needle) ||
                    chat.lastMessage.lowercase(Locale.getDefault()).contains(needle)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "Inbox", style = MaterialTheme.typography.titleLarge)
                        if (chats.isNotEmpty()) {
                            Text(
                                text = "${chats.size} mazungumzo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                viewModel.restartChats(currentUserId, context)
            },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Tafuta mazungumzo...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    error != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.restartChats(currentUserId, context) }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Jaribu tena")
                            }
                        }
                    }

                    chats.isEmpty() -> {
                        EmptyInbox()
                    }

                    filtered.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Hakuna mazungumzo yanayolingana",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            itemsIndexed(filtered, key = { _, chat -> chat.id }) { index, chat ->
                                val otherId = chat.otherUserId(currentUserId)
                                val profile = profiles[otherId]
                                InboxRow(
                                    chat = chat,
                                    otherName = profile?.name?.ifBlank { "Mtumiaji" } ?: "Mtumiaji",
                                    otherImage = profile?.image.orEmpty(),
                                    unread = chat.unreadFor(currentUserId),
                                    currentUserId = currentUserId,
                                    showDivider = index < filtered.lastIndex,
                                    onClick = {
                                        onChatClick(
                                            otherId,
                                            profile?.name?.ifBlank { "Mtumiaji" } ?: "Mtumiaji"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyInbox() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Inbox,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Hakuna mazungumzo bado",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Anza mazungumzo na mwajiri au mwajiriwa kutoka kwenye kazi unayoiona.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InboxRow(
    chat: ChatSummary,
    otherName: String,
    otherImage: String,
    unread: Int,
    currentUserId: String,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val sentByMe = chat.lastSenderId == currentUserId && currentUserId.isNotBlank()
    // Photo messages used to store the raw Cloudinary URL as lastMessage (both
    // old and new clients wrote it) — render a short label for those rows.
    val lastMessage = if (chat.lastMessage.contains("res.cloudinary.com")) {
        "📷 Picha"
    } else {
        chat.lastMessage
    }
    val preview = when {
        chat.lastMessage.isBlank() -> "Anza mazungumzo"
        chat.lastSenderId == currentUserId -> "Wewe: $lastMessage"
        else -> lastMessage
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChatAvatar(imageUrl = otherImage, name = otherName, size = 48.dp)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = otherName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sentByMe) {
                        val read = chat.lastMessageReadByOther(currentUserId)
                        Icon(
                            imageVector = if (read) Icons.Default.DoneAll else Icons.Default.Done,
                            contentDescription = if (read) "Imesomwa" else "Imefika",
                            tint = if (read) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(if (read) 15.dp else 13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = relativeTime(chat.lastTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (unread > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.heightIn(min = 20.dp)
                    ) {
                        Text(
                            text = if (unread > 99) "99+" else unread.toString(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 76.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""

    val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    return when {
        sameDay(messageCalendar, today) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

        sameDay(messageCalendar, yesterday) -> "Jana"

        messageCalendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))

        else ->
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun sameDay(first: Calendar, second: Calendar): Boolean =
    first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)