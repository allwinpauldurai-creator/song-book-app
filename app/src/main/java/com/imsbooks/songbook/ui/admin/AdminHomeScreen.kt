package com.imsbooks.songbook.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.AdminStats
import com.imsbooks.songbook.data.BOOK_STATUSES
import com.imsbooks.songbook.data.Me
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.session.SessionState
import com.imsbooks.songbook.ui.appContainer
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.BookIcon
import com.imsbooks.songbook.ui.components.EmptyState
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.util.formatDate
import com.imsbooks.songbook.util.titleCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- access guard

/**
 * Shows [content] only to signed-in staff; [adminOnly] screens also exclude publishers. The server enforces
 * the same rules (403/404); this only avoids showing screens that cannot work.
 */
@Composable
fun RequireStaff(adminOnly: Boolean, onBack: (() -> Unit)?, content: @Composable (Me) -> Unit) {
    val session by appContainer().session.state.collectAsStateWithLifecycle()
    when (val s = session) {
        SessionState.Loading -> LoadingBox(Modifier.fillMaxSize())
        is SessionState.SignedIn -> if (if (adminOnly) s.user.isAdmin else s.user.isStaff) {
            content(s.user)
        } else {
            NoAccess("This area is for administrators.", onBack)
        }
        SessionState.Offline -> NoAccess("You are offline. Managing the library needs a connection.", onBack)
        SessionState.SignedOut -> NoAccess("Log in with an administrator or publisher account.", onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoAccess(message: String, onBack: (() -> Unit)?) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { p -> EmptyState("Not available", message, Modifier.padding(p)) }
}

/** Top bar with a back arrow, used by every management screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminTopBar(title: String, onBack: (() -> Unit)?, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = actions,
    )
}

// ---------------------------------------------------------------- home (web: /admin)

data class AdminHomeState(val stats: AdminStats? = null, val loading: Boolean = false, val error: String? = null)

class AdminHomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AdminHomeState())
    val state = _state.asStateFlow()

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                AdminHomeState(stats = apiCall(c.json) { c.adminApi.stats() })
            } catch (e: Exception) {
                _state.value.copy(loading = false, error = adminErrorMessage(e))
            }
        }
    }
}

/** Entry points of the management area (web: AdminLayout navigation). */
data class AdminNav(
    val onUsers: (status: String?, role: String?) -> Unit,
    val onNewUser: () -> Unit,
    val onMemberships: (status: String?) -> Unit,
    val onBooks: (status: String?) -> Unit,
    val onCategories: () -> Unit,
    val onRequests: (status: String?) -> Unit,
    val onAudit: () -> Unit,
)

/** "Manage" tab: dashboard and menu for admins; publishers manage only their books, so they get that list. */
@Composable
fun AdminHomeScreen(nav: AdminNav, publisherBooks: @Composable () -> Unit) {
    RequireStaff(adminOnly = false, onBack = null) { user ->
        if (user.isAdmin) AdminDashboard(nav) else publisherBooks()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminDashboard(nav: AdminNav) {
    val vm = appViewModel { AdminHomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { AdminTopBar("Manage", onBack = null) }) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.stats != null,
            onRefresh = vm::load,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val stats = state.stats
                when {
                    stats != null -> {
                        item {
                            Text(
                                "Counts as of ${formatDate(stats.asOf)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Stat("Users", stats.users.total, "${stats.users.unverified} unverified") { nav.onUsers(null, null) }
                                Stat("Valid memberships", stats.membershipsValidToday, "Active today") { nav.onMemberships("ACTIVE") }
                                Stat("Published books", stats.booksByStatus["PUBLISHED"] ?: 0) { nav.onBooks("PUBLISHED") }
                                Stat("Pending requests", stats.pendingRequests, "Waiting for review") { nav.onRequests("PENDING") }
                            }
                        }
                        item { SectionTitle("Books by status") }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                BOOK_STATUSES.forEach { s -> Stat(titleCase(s), stats.booksByStatus[s] ?: 0) { nav.onBooks(s) } }
                            }
                        }
                        item { SectionTitle("Accounts") }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Stat("Active", stats.users.active) { nav.onUsers("ACTIVE", null) }
                                Stat("Disabled", stats.users.disabled) { nav.onUsers("DISABLED", null) }
                                Stat("Locked", stats.users.locked) { nav.onUsers("LOCKED", null) }
                                Stat("Active admins", stats.users.activeAdmins) { nav.onUsers(null, "ADMIN") }
                            }
                        }
                    }
                    state.error != null -> item { ErrorState(state.error!!, onRetry = vm::load) }
                    else -> item { LoadingBox() }
                }
                item { SectionTitle("Manage") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        MenuRow("Users", "Accounts, roles, add a user", Icons.Filled.Person) { nav.onUsers(null, null) }
                        HorizontalDivider()
                        MenuRow("Memberships", "Who can read, and until when", Icons.Filled.DateRange) { nav.onMemberships(null) }
                        HorizontalDivider()
                        MenuRow("Books", "Upload, edit, new versions, archive", BookIcon) { nav.onBooks(null) }
                        HorizontalDivider()
                        MenuRow("Categories", "Group books in the catalogue", Icons.AutoMirrored.Filled.List) { nav.onCategories() }
                        HorizontalDivider()
                        MenuRow("Requests", "Corrections and additions from members", Icons.Filled.Edit) { nav.onRequests("PENDING") }
                        HorizontalDivider()
                        MenuRow("Audit log", "Security and administrative events", Icons.Filled.Info) { nav.onAudit() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Stat(label: String, value: Long, hint: String? = null, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(160.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
