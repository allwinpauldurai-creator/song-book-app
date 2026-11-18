package com.imsbooks.songbook.ui.member

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.HistoryItem
import com.imsbooks.songbook.data.Me
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.session.SessionState
import com.imsbooks.songbook.data.userMessage
import com.imsbooks.songbook.ui.appContainer
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.BookCover
import com.imsbooks.songbook.ui.components.EmptyState
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.util.Mobile
import com.imsbooks.songbook.util.formatDate
import com.imsbooks.songbook.util.titleCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MeState(
    val history: List<HistoryItem> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

class MeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(MeState())
    val state: StateFlow<MeState> = _state.asStateFlow()

    /** Reloads the profile (membership may have changed) and the first page of reading history. */
    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                c.session.userUpdated(apiCall(c.json) { c.api.me() })
                val page = apiCall(c.json) { c.api.history(0, PAGE_SIZE) }
                _state.value = MeState(page.content, page.page, page.totalPages, loading = false)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.page + 1 >= s.totalPages) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val page = apiCall(c.json) { c.api.history(s.page + 1, PAGE_SIZE) }
                _state.update { it.copy(history = it.history + page.content, page = page.page, totalPages = page.totalPages, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Revokes the refresh-token family on the server; the local session is dropped regardless.
            runCatching { c.api.logout() }
            c.pdfRepository.clearCache()
            c.session.clearLocal()
        }
    }

    private companion object {
        const val PAGE_SIZE = 10
    }
}

/** "Me" tab: sign-in prompt when signed out, otherwise the member dashboard (web: /me). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    onOpenBook: (String) -> Unit,
    onRead: (String) -> Unit,
    onProfile: () -> Unit,
    onRequests: () -> Unit,
) {
    val container = appContainer()
    val session by container.session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    when (val s = session) {
        SessionState.Loading -> LoadingBox(Modifier.fillMaxSize())
        SessionState.Offline -> Scaffold(topBar = { TopAppBar(title = { Text("My library") }) }) { p ->
            ErrorState(
                "Cannot reach the server to resume your session. Check your connection.",
                Modifier.padding(p),
                onRetry = { scope.launch { container.session.restore(container.api) } },
            )
        }
        SessionState.SignedOut -> Scaffold(topBar = { TopAppBar(title = { Text("My library") }) }) { p ->
            Column(
                Modifier.padding(p).padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmptyState("Read song books on your phone", "Log in with your mobile number to read, download and pick up where you left off.")
                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("Log in") }
                OutlinedButton(onClick = onRegister, modifier = Modifier.fillMaxWidth()) { Text("Create an account") }
            }
        }
        is SessionState.SignedIn -> SignedInDashboard(s.user, onOpenBook, onRead, onProfile, onRequests)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInDashboard(
    user: Me,
    onOpenBook: (String) -> Unit,
    onRead: (String) -> Unit,
    onProfile: () -> Unit,
    onRequests: () -> Unit,
) {
    val vm = appViewModel { MeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The reader saves progress; always show the latest when coming back.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hello, ${user.fullName.substringBefore(' ')}") },
                actions = {
                    IconButton(onClick = vm::logout) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Log out")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.history.isNotEmpty(),
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { MembershipCard(user) }
                item { AccountCard(user, onProfile, onRequests) }
                val latest = state.history.firstOrNull()
                if (latest != null && user.canRead) {
                    item { ContinueReading(latest) { onRead(latest.bookId) } }
                }
                item {
                    Text("Reading history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                }
                when {
                    state.error != null && state.history.isEmpty() -> item { ErrorState(state.error!!, onRetry = vm::refresh) }
                    state.loading && state.history.isEmpty() -> item { LoadingBox() }
                    state.history.isEmpty() -> item {
                        Text("Books you open will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        items(state.history, key = { it.bookId }) { h ->
                            HistoryRow(h) { if (user.canRead) onRead(h.bookId) else onOpenBook(h.bookId) }
                            HorizontalDivider()
                        }
                        if (state.page + 1 < state.totalPages) {
                            item {
                                TextButton(onClick = vm::loadMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                                    Text("Show more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MembershipCard(user: Me) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("MEMBERSHIP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val m = user.membership
            if (m == null) {
                Text(
                    if (user.isAdmin) "Administrators can read every book without a membership." else "No membership yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val color = if (m.valid) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                Text(if (m.valid) "Active" else titleCase(m.status), color = color, fontWeight = FontWeight.Bold)
                Text(
                    "From ${formatDate(m.startDate)} " + (m.endDate?.let { "to ${formatDate(it)}" } ?: "· no end date"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!m.valid && !user.isAdmin) {
                    Text("Please contact an administrator to renew it.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AccountCard(user: Me, onProfile: () -> Unit, onRequests: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ACCOUNT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(user.fullName, fontWeight = FontWeight.SemiBold)
            Text(Mobile.format(user.mobile), color = MaterialTheme.colorScheme.onSurfaceVariant)
            user.email?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                user.roles.forEach { AssistChip(onClick = {}, label = { Text(titleCase(it)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onProfile) { Text("Profile") }
                OutlinedButton(onClick = onRequests) { Text("My requests") }
            }
        }
    }
}

@Composable
private fun ContinueReading(item: HistoryItem, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(item.coverUrl, item.title, Modifier.width(72.dp))
            Column(Modifier.weight(1f)) {
                Text("CONTINUE READING", style = MaterialTheme.typography.labelMedium)
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Page ${item.lastPage} · ${formatDate(item.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                Text("Resume", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(item.coverUrl, item.title, Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Page ${item.lastPage} · ${formatDate(item.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
