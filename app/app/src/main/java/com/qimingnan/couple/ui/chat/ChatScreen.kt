package com.qimingnan.couple.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qimingnan.couple.R
import com.qimingnan.couple.data.chat.ChatRepository
import com.qimingnan.couple.data.chat.ReceiptStatus
import com.qimingnan.couple.data.db.ChatMessageEntity
import com.qimingnan.couple.theme.LocalCoupleTokens
import com.qimingnan.couple.ui.components.StatusDot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun chatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts))

/** 聊天 P1a（contract "Chat"）：文本、引用回复、撤回、已读、离线 outbox、上滑加载更早。 */
@Composable
fun ChatScreen(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var replyingTo by rememberSaveable { mutableStateOf<String?>(null) }
    var actionFor by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        vm.enter()
        onDispose { vm.leave() }
    }

    val byId = remember(state.rows) { state.rows.associateBy { it.id } }
    val listState = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(atEnd, state.hasMore, state.rows.size) {
        if (atEnd && state.hasMore) vm.loadOlder()
    }

    Column(modifier.fillMaxSize().imePadding()) {
        ChatHeader(name = state.partnerName, online = state.partnerOnline, typing = state.partnerTyping)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.empty) {
                ChatEmpty()
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = t.spacing.md, vertical = t.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
                ) {
                    items(state.rows.asReversed(), key = { it.id }) { row ->
                        val mine = row.senderId == state.selfId
                        MessageBubble(
                            row = row,
                            mine = mine,
                            replyTarget = row.replyTo?.let { byId[it] },
                            receipt = state.receipts[row.id],
                            onRetry = { vm.retryPending(row.id) },
                            onLongPress = { if (row.recalledAt == null) actionFor = row.id },
                        )
                    }
                }
            }
            if (state.hasMore && state.rows.isNotEmpty()) {
                Text(
                    stringResource(R.string.chat_load_older),
                    style = t.typography.caption,
                    color = t.colors.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = t.spacing.xs),
                )
            }
        }

        state.error?.let {
            Text(
                it,
                style = t.typography.caption,
                color = t.colors.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = t.spacing.md, vertical = t.spacing.xxs),
            )
        }

        ChatComposer(
            value = input,
            onValueChange = { if (it.length <= 4000) { input = it; vm.onInputChanged(it) } },
            replyText = replyingTo?.let { id -> byId[id]?.text?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_reply_missing) },
            onCancelReply = { replyingTo = null },
            onSend = {
                if (input.isNotBlank()) {
                    vm.send(input, replyingTo)
                    input = ""
                    replyingTo = null
                }
            },
        )
    }

    actionFor?.let { id ->
        val row = byId[id]
        if (row != null) {
            val mine = row.senderId == state.selfId
            val canRecall = mine && row.recalledAt == null &&
                row.createdAt != null && System.currentTimeMillis() - row.createdAt!! <= RECALL_WINDOW_MS
            MessageActionsDialog(
                canRecall = canRecall,
                onReply = { replyingTo = id; actionFor = null },
                onRecall = { vm.recall(id); actionFor = null },
                onDismiss = { actionFor = null },
            )
        }
    }
}

private const val RECALL_WINDOW_MS = 120_000L

@Composable
private fun ChatHeader(name: String, online: Boolean, typing: Boolean) {
    val t = LocalCoupleTokens.current
    Surface(color = t.colors.surfaceContainer, contentColor = t.colors.onSurface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (online) t.colors.success else t.colors.onSurfaceVariant)
            Spacer(Modifier.width(t.spacing.sm))
            Column {
                Text(name, style = t.typography.title, color = t.colors.onSurface)
                Text(
                    stringResource(
                        when {
                            typing -> R.string.chat_typing
                            online -> R.string.online
                            else -> R.string.offline
                        },
                    ),
                    style = t.typography.caption,
                    color = if (typing) t.colors.primary else t.colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatEmpty() {
    val t = LocalCoupleTokens.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
            Text(stringResource(R.string.chat_empty_title), style = t.typography.headline, color = t.colors.onBackground)
            Text(stringResource(R.string.chat_empty_hint), style = t.typography.body, color = t.colors.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: ChatMessageEntity,
    mine: Boolean,
    replyTarget: ChatMessageEntity?,
    receipt: ReceiptStatus?,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    val bg = if (mine) t.colors.primaryContainer else t.colors.surfaceContainer
    val fg = if (mine) t.colors.onPrimaryContainer else t.colors.onSurface
    val recalled = row.recalledAt != null
    val failed = row.pendingState == ChatRepository.FAILED

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMax = maxWidth * 0.78f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
            Surface(
                color = bg,
                contentColor = fg,
                shape = t.shapes.medium,
                modifier = Modifier
                    .widthIn(max = bubbleMax)
                    .combinedClickable(onClick = { if (failed) onRetry() }, onLongClick = onLongPress),
            ) {
                Column(Modifier.padding(horizontal = t.spacing.md, vertical = t.spacing.sm)) {
                    if (row.replyTo != null) {
                        Surface(color = t.colors.surface, shape = t.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                replyTarget?.let {
                                    if (it.recalledAt != null) stringResource(R.string.chat_recalled)
                                    else it.text.ifBlank { stringResource(R.string.chat_reply_missing) }
                                } ?: stringResource(R.string.chat_reply_missing),
                                style = t.typography.caption,
                                color = t.colors.onSurfaceVariant,
                                maxLines = 2,
                                modifier = Modifier.padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
                            )
                        }
                        Spacer(Modifier.height(t.spacing.xs))
                    }
                    Text(
                        if (recalled) stringResource(R.string.chat_recalled) else row.text,
                        style = if (recalled) t.typography.body.copy(fontStyle = FontStyle.Italic) else t.typography.body,
                        color = if (recalled) t.colors.onSurfaceVariant else fg,
                    )
                    Spacer(Modifier.height(t.spacing.xxs))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                        Text(chatTime(row.createdAt ?: row.sortAt), style = t.typography.caption, color = t.colors.onSurfaceVariant)
                        if (mine) {
                            when (receipt) {
                                ReceiptStatus.SENDING -> Text(
                                    stringResource(R.string.chat_sending),
                                    style = t.typography.caption,
                                    color = t.colors.onSurfaceVariant,
                                )
                                ReceiptStatus.FAILED -> Text(
                                    stringResource(R.string.chat_failed_retry),
                                    style = t.typography.caption,
                                    color = t.colors.error,
                                )
                                ReceiptStatus.READ -> Text(stringResource(R.string.chat_read), style = t.typography.caption, color = t.colors.primary)
                                ReceiptStatus.UNREAD -> Text(
                                    stringResource(R.string.chat_unread),
                                    style = t.typography.caption,
                                    color = t.colors.onSurfaceVariant,
                                )
                                null -> Unit
                            }
                        }
                        if (row.pendingState == ChatRepository.PENDING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = t.colors.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    replyText: String?,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
) {
    val t = LocalCoupleTokens.current
    Surface(color = t.colors.surfaceContainer, contentColor = t.colors.onSurface) {
        Column(Modifier.fillMaxWidth().padding(t.spacing.sm)) {
            replyText?.let {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.chat_replying_to, it),
                        style = t.typography.caption,
                        color = t.colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancelReply) { Text(stringResource(R.string.chat_cancel_reply)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    maxLines = 4,
                    shape = t.shapes.medium,
                    textStyle = t.typography.body,
                )
                IconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(t.spacing.minTouchTarget)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        tint = if (value.isNotBlank()) t.colors.primary else t.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionsDialog(canRecall: Boolean, onReply: () -> Unit, onRecall: () -> Unit, onDismiss: () -> Unit) {
    val t = LocalCoupleTokens.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = t.colors.surfaceContainer, contentColor = t.colors.onSurface, shape = t.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(t.spacing.lg), verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                TextButton(onClick = onReply, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.chat_reply), color = t.colors.primary)
                }
                if (canRecall) {
                    TextButton(onClick = onRecall, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_recall), color = t.colors.error)
                    }
                }
            }
        }
    }
}
