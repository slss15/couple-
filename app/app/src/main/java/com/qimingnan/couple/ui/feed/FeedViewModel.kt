package com.qimingnan.couple.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qimingnan.couple.data.auth.SessionManager
import com.qimingnan.couple.data.auth.SessionState
import com.qimingnan.couple.data.couple.CoupleRepository
import com.qimingnan.couple.data.couple.CoupleState
import com.qimingnan.couple.data.db.ProfileDao
import com.qimingnan.couple.data.db.PostRow
import com.qimingnan.couple.data.feed.FeedRepository
import com.qimingnan.couple.notify.Notifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    val repo: FeedRepository,
    private val profileDao: ProfileDao,
    private val couple: CoupleRepository,
    private val session: SessionManager,
    private val notifier: Notifier,
) : ViewModel() {

    /** 作者/TA 名字缓存（Room users 表）+ 帖子列表。 */
    data class UiState(
        val rows: List<PostRow> = emptyList(),
        val names: Map<String, String> = emptyMap(),
        val selfId: String? = null,
        val anniversary: Long? = null,
        val pairedAt: Long? = null,
        val refreshing: Boolean = false,
        val error: String? = null,
        val empty: Boolean = true,
    )

    private val names = MutableStateFlow<Map<String, String>>(emptyMap())

    private data class Core(
        val rows: List<PostRow> = emptyList(),
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    val state: StateFlow<UiState> = combine(
        combine(repo.posts, repo.refreshing, repo.error) { rows, refreshing, error -> Core(rows, refreshing, error) },
        names,
        couple.state,
        session.state,
    ) { core, n, c, s ->
        val paired = c as? CoupleState.Paired
        UiState(
            rows = core.rows,
            names = n,
            selfId = (s as? SessionState.LoggedIn)?.user?.id,
            anniversary = paired?.couple?.anniversary,
            pairedAt = paired?.couple?.pairedAt,
            refreshing = core.refreshing,
            error = core.error,
            empty = core.rows.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        repo.start()
        refresh()
        viewModelScope.launch {
            profileDao.observeAll().collect { list ->
                names.value = list.associate { it.id to it.displayName.ifBlank { it.username } }
            }
        }
    }

    fun refresh() = viewModelScope.launch { repo.refresh() }

    fun loadMore() = viewModelScope.launch { repo.loadMore() }

    fun publish(text: String, images: List<android.net.Uri>) = repo.publish(text, images)

    fun toggleLike(postId: String) = viewModelScope.launch { repo.toggleLike(postId) }

    fun addComment(postId: String, text: String) = viewModelScope.launch { repo.addComment(postId, text) }

    fun deleteComment(id: String) = viewModelScope.launch { repo.deleteComment(id) }

    fun deletePost(id: String) = viewModelScope.launch { repo.deletePost(id) }

    fun retryPending(id: String) = repo.retryPending(id)

    fun mediaUrl(key: String, thumb: Boolean) = repo.mediaUrl(key, thumb)

    fun setFeedVisible(visible: Boolean) = notifier.setScreenVisible("feed", visible)
}
