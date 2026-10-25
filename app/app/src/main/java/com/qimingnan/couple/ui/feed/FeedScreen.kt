package com.qimingnan.couple.ui.feed

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import com.qimingnan.couple.R
import com.qimingnan.couple.data.db.CommentEntity
import com.qimingnan.couple.data.db.PostRow
import com.qimingnan.couple.data.db.displayMedia
import com.qimingnan.couple.data.feed.FeedRepository
import com.qimingnan.couple.theme.LocalCoupleTokens
import java.util.concurrent.TimeUnit

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 日期。 */
fun relativeTime(now: Long, ts: Long): String {
    val d = now - ts
    return when {
        d < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        d < TimeUnit.HOURS.toMillis(1) -> "${d / TimeUnit.MINUTES.toMillis(1)} 分钟前"
        d < TimeUnit.DAYS.toMillis(1) -> "${d / TimeUnit.HOURS.toMillis(1)} 小时前"
        d < TimeUnit.DAYS.toMillis(7) -> "${d / TimeUnit.DAYS.toMillis(1)} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ts))
    }
}

/** 在一起天数（纪念日卡：feedHeader zone 的 P1 形态，P2 转为插件）。 */
fun daysTogether(from: Long, now: Long): Long = TimeUnit.MILLISECONDS.toDays(now - from).coerceAtLeast(0)

@Composable
fun FeedScreen(vm: FeedViewModel, modifier: Modifier = Modifier) {
    val t = LocalCoupleTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    var publishing by rememberSaveable { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) } // 全屏查看的原图 URL

    DisposableEffect(Unit) {
        vm.setFeedVisible(true)
        onDispose { vm.setFeedVisible(false) }
    }

    val listState = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(atEnd) { if (atEnd) vm.loadMore() }

    Box(modifier.fillMaxSize()) {
        if (state.empty) {
            EmptyFeed()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = t.spacing.sm, horizontal = t.spacing.md),
                verticalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                state.anniversary?.let { ann ->
                    item { AnniversaryCard(days = daysTogether(ann, System.currentTimeMillis())) }
                }
                items(state.rows, key = { it.post.id }) { row ->
                    PostCard(
                        row = row,
                        authorName = state.names[row.post.authorId] ?: "",
                        selfId = state.selfId,
                        mediaUrl = { key, thumb -> vm.mediaUrl(key, thumb) },
                        onLike = { vm.toggleLike(row.post.id) },
                        onComment = { vm.addComment(row.post.id, it) },
                        onDeleteComment = { vm.deleteComment(it) },
                        onDelete = { vm.deletePost(row.post.id) },
                        onRetry = { vm.retryPending(row.post.id) },
                        onOpenImage = { viewing = it },
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { publishing = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.feed_publish)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(t.spacing.md),
            containerColor = t.colors.primaryContainer,
            contentColor = t.colors.onPrimaryContainer,
        )

        if (state.refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.TopCenter).padding(t.spacing.md).size(28.dp),
                color = t.colors.primary,
            )
        }
        state.error?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.BottomStart).padding(t.spacing.md),
                style = t.typography.caption,
                color = t.colors.error,
            )
        }
    }

    if (publishing) {
        PublishScreen(
            onDone = { text, images ->
                vm.publish(text, images)
                publishing = false
            },
            onCancel = { publishing = false },
        )
    }
    viewing?.let { url ->
        Dialog(onDismissRequest = { viewing = null }) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().clickable { viewing = null },
            )
        }
    }
}

@Composable
private fun AnniversaryCard(days: Long) {
    val t = LocalCoupleTokens.current
    Surface(
        shape = RoundedCornerShape(t.shapes.medium),
        color = t.colors.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(t.spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = t.colors.primary)
            Spacer(Modifier.width(t.spacing.md))
            Column {
                Text(stringResource(R.string.feed_anniversary_title), style = t.typography.body, color = t.colors.onPrimaryContainer)
                Text(stringResource(R.string.feed_anniversary_days, days), style = t.typography.caption, color = t.colors.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun EmptyFeed() {
    val t = LocalCoupleTokens.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = t.colors.primary)
            Text(stringResource(R.string.feed_empty_title), style = t.typography.headline, color = t.colors.onBackground)
            Text(stringResource(R.string.feed_empty_hint), style = t.typography.body, color = t.colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PostCard(
    row: PostRow,
    authorName: String,
    selfId: String?,
    mediaUrl: (String, Boolean) -> String?,
    onLike: () -> Unit,
    onComment: (String) -> Unit,
    onDeleteComment: (String) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val t = LocalCoupleTokens.current
    var commenting by rememberSaveable(row.post.id) { mutableStateOf(false) }
    var input by rememberSaveable(row.post.id) { mutableStateOf("") }
    val pending = row.post.pendingState

    Surface(
        shape = RoundedCornerShape(t.shapes.medium),
        color = t.colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(t.spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(authorName, style = t.typography.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = t.colors.onSurface)
                    Text(
                        relativeTime(System.currentTimeMillis(), row.post.createdAt) +
                            when (pending) {
                                FeedRepository.PENDING -> " · 发送中…"
                                FeedRepository.FAILED -> " · 发送失败"
                                else -> ""
                            },
                        style = t.typography.caption,
                        color = t.colors.onSurfaceVariant,
                    )
                }
                when (pending) {
                    FeedRepository.FAILED -> TextButton(onClick = onRetry) { Text(stringResource(R.string.feed_retry)) }
                    FeedRepository.PENDING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = t.colors.primary)
                    null -> if (row.post.authorId == selfId) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.feed_delete), tint = t.colors.onSurfaceVariant)
                        }
                    }
                }
            }
            if (row.post.text.isNotEmpty()) {
                Spacer(Modifier.height(t.spacing.xs))
                Text(row.post.text, style = t.typography.body, color = t.colors.onSurface)
            }
            if (row.media.isNotEmpty()) {
                Spacer(Modifier.height(t.spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                    row.displayMedia().take(3).forEach { m ->
                        val thumb = mediaUrl(m.thumbKey ?: m.storageKey, thumb = m.thumbKey != null) ?: return@forEach
                        AsyncImage(
                            model = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .height(120.dp)
                                .clip(RoundedCornerShape(t.spacing.xs))
                                .clickable { mediaUrl(m.storageKey, false)?.let(onOpenImage) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(t.spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                    Icon(
                        if (row.post.likedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(R.string.feed_like),
                        tint = if (row.post.likedByMe) t.colors.primary else t.colors.onSurfaceVariant,
                    )
                }
                Text("${row.post.likeCount}", style = t.typography.caption, color = t.colors.onSurfaceVariant)
                Spacer(Modifier.width(t.spacing.md))
                TextButton(onClick = { commenting = !commenting }) {
                    Text(stringResource(R.string.feed_comment_count, row.comments.size))
                }
            }
            row.comments.sortedBy { it.createdAt }.forEach { c ->
                CommentLine(c, author = when (c.authorId) {
                    row.post.authorId -> authorName
                    selfId -> stringResource(R.string.feed_me)
                    else -> "TA"
                }, canDelete = c.authorId == selfId, onDelete = { onDeleteComment(c.id) })
            }
            if (commenting && pending == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.feed_comment_hint)) },
                        singleLine = true,
                        textStyle = t.typography.body,
                    )
                    IconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                onComment(input)
                                input = ""
                            }
                        },
                        modifier = Modifier.size(t.spacing.minTouchTarget),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.feed_send), tint = t.colors.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentLine(c: CommentEntity, author: String, canDelete: Boolean, onDelete: () -> Unit) {
    val t = LocalCoupleTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text("$author：", style = t.typography.caption, color = t.colors.primary)
        Text(c.text, style = t.typography.caption, color = t.colors.onSurface, modifier = Modifier.weight(1f))
        if (canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.feed_delete), tint = t.colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun PublishScreen(onDone: (String, List<Uri>) -> Unit, onCancel: () -> Unit) {
    val t = LocalCoupleTokens.current
    var text by rememberSaveable { mutableStateOf("") }
    var images by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isNotEmpty()) images = uris.take(9)
    }
    Scaffold(
        containerColor = t.colors.background,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = t.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel, modifier = Modifier.size(t.spacing.minTouchTarget)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.feed_publish), style = t.typography.headline, color = t.colors.onBackground, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                ) { Text(stringResource(R.string.feed_pick_image)) }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(t.spacing.md)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                placeholder = { Text(stringResource(R.string.feed_publish_hint)) },
                textStyle = t.typography.body,
            )
            Spacer(Modifier.height(t.spacing.sm))
            if (images.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                    images.take(3).forEach { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(t.spacing.xs)),
                        )
                    }
                    if (images.size > 3) {
                        Box(
                            Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(t.spacing.xs))
                                .background(t.colors.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) { Text("+${images.size - 3}", style = t.typography.body, color = t.colors.onSurfaceVariant) }
                    }
                }
                Text(
                    stringResource(R.string.feed_image_count, images.size),
                    style = t.typography.caption,
                    color = t.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = t.spacing.xs),
                )
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.Button(
                onClick = { onDone(text, images) },
                enabled = text.isNotBlank() || images.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = t.colors.primary,
                    contentColor = t.colors.onPrimary,
                ),
            ) { Text(stringResource(R.string.feed_publish_action)) }
        }
    }
}
