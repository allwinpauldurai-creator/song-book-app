package com.imsbooks.songbook.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.AUDIT_ACTIONS
import com.imsbooks.songbook.data.AUDIT_ENTITY_TYPES
import com.imsbooks.songbook.data.AdminCategory
import com.imsbooks.songbook.data.AuditLogEntry
import com.imsbooks.songbook.data.CreateCategoryRequest
import com.imsbooks.songbook.data.ModificationRequest
import com.imsbooks.songbook.data.REQUEST_STATUSES
import com.imsbooks.songbook.data.ReviewRequestBody
import com.imsbooks.songbook.data.UpdateCategoryRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.util.formatDate
import com.imsbooks.songbook.util.titleCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ================================================================ categories (web: /admin/categories)

data class CategoriesState(
    val items: List<AdminCategory> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val create: FormStatus = FormStatus(),
    /** Error of the last row update, keyed by category id. */
    val rowErrors: Map<String, String> = emptyMap(),
)

class CategoriesViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(CategoriesState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val items = apiCall(c.json) { c.adminApi.categories() }
                _state.update { it.copy(items = items, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = adminErrorMessage(e)) }
            }
        }
    }

    fun create(name: String, description: String?) {
        _state.update { it.copy(create = FormStatus(busy = true)) }
        viewModelScope.launch {
            try {
                apiCall(c.json) { c.adminApi.createCategory(CreateCategoryRequest(name, description)) }
                _state.update { it.copy(create = FormStatus(done = true)) }
                load()
            } catch (e: Exception) {
                _state.update { it.copy(create = FormStatus(error = e)) }
            }
        }
    }

    /** Returns true when saved. */
    fun update(category: AdminCategory, name: String = category.name, description: String? = category.description, active: Boolean = category.active, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val saved = apiCall(c.json) {
                    c.adminApi.updateCategory(category.id, UpdateCategoryRequest(name, description, active, category.version))
                }
                _state.update { s -> s.copy(items = s.items.map { if (it.id == saved.id) saved else it }, rowErrors = s.rowErrors - saved.id) }
                onSaved()
            } catch (e: Exception) {
                _state.update { it.copy(rowErrors = it.rowErrors + (category.id to adminErrorMessage(e))) }
            }
        }
    }
}

@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    RequireStaff(adminOnly = true, onBack = onBack) {
        val vm = appViewModel { CategoriesViewModel(it) }
        val state by vm.state.collectAsStateWithLifecycle()
        var name by rememberSaveable { mutableStateOf("") }
        var description by rememberSaveable { mutableStateOf("") }
        var editing by remember { mutableStateOf<AdminCategory?>(null) }
        LaunchedEffect(state.create.done) {
            if (state.create.done) {
                name = ""
                description = ""
            }
        }

        Scaffold(topBar = { AdminTopBar("Categories", onBack) }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "Categories are deactivated, never deleted, so books keep their history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Add category", style = MaterialTheme.typography.titleMedium)
                            state.create.error?.let { Notice(adminErrorMessage(it), isError = true) }
                            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(description, { description = it.take(2000) }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
                            Button(
                                onClick = { vm.create(name.trim(), description.trim().ifBlank { null }) },
                                enabled = name.isNotBlank() && !state.create.busy,
                            ) { Text(if (state.create.busy) "Adding…" else "Add") }
                        }
                    }
                }
                when {
                    state.error != null && state.items.isEmpty() -> item { ErrorState(state.error!!, onRetry = vm::load) }
                    state.loading && state.items.isEmpty() -> item { LoadingBox() }
                    state.items.isEmpty() -> item { Text("No categories yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> items(state.items, key = { it.id }) { cat ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cat.name, fontWeight = FontWeight.SemiBold)
                                    cat.description?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        if (cat.active) "Shown in the catalogue" else "Hidden from the catalogue",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(checked = cat.active, onCheckedChange = { vm.update(cat, active = it) })
                                IconButton(onClick = { editing = cat }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${cat.name}") }
                            }
                            state.rowErrors[cat.id]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            HorizontalDivider(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }

        editing?.let { cat ->
            var editName by rememberSaveable(cat.id) { mutableStateOf(cat.name) }
            var editDescription by rememberSaveable(cat.id) { mutableStateOf(cat.description.orEmpty()) }
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Edit category") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.rowErrors[cat.id]?.let { Notice(it, isError = true) }
                        OutlinedTextField(editName, { editName = it.take(100) }, label = { Text("Name") }, singleLine = true)
                        OutlinedTextField(editDescription, { editDescription = it.take(2000) }, label = { Text("Description") })
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            vm.update(cat, name = editName.trim(), description = editDescription.trim().ifBlank { null }) { editing = null }
                        },
                        enabled = editName.isNotBlank(),
                    ) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            )
        }
    }
}

// ================================================================ requests (web: /admin/requests)

class RequestsReviewViewModel(private val c: AppContainer, initialStatus: String?) : ViewModel() {
    private val _status = MutableStateFlow(initialStatus)
    val status = _status.asStateFlow()
    private val _review = MutableStateFlow(FormStatus())
    val review = _review.asStateFlow()
    val list = PagedLoader(viewModelScope, c.json) { page, size -> c.adminApi.requests(_status.value, page, size) }

    init {
        list.refresh()
    }

    fun setStatus(s: String?) {
        _status.value = s
        list.refresh()
    }

    fun review(request: ModificationRequest, decision: String, note: String?, onDone: () -> Unit) {
        _review.value = FormStatus(busy = true)
        viewModelScope.launch {
            try {
                apiCall(c.json) { c.adminApi.reviewRequest(request.id, ReviewRequestBody(decision, note, request.version)) }
                _review.value = FormStatus()
                onDone()
                list.refresh()
            } catch (e: Exception) {
                _review.value = FormStatus(error = e)
            }
        }
    }

    fun resetReview() {
        _review.value = FormStatus()
    }
}

private val REQUEST_TABS: List<String?> = REQUEST_STATUSES + listOf(null)

@Composable
fun RequestsReviewScreen(status: String?, onBack: () -> Unit, onOpenUser: (String) -> Unit, onOpenBook: (String) -> Unit) {
    RequireStaff(adminOnly = true, onBack = onBack) {
        val vm = appViewModel(key = "admin-requests-$status") { RequestsReviewViewModel(it, status) }
        val current by vm.status.collectAsStateWithLifecycle()
        val list by vm.list.state.collectAsStateWithLifecycle()
        val review by vm.review.collectAsStateWithLifecycle()
        var reviewing by remember { mutableStateOf<Pair<ModificationRequest, String>?>(null) }

        Scaffold(topBar = { AdminTopBar("Requests", onBack) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                PrimaryScrollableTabRow(selectedTabIndex = REQUEST_TABS.indexOf(current).coerceAtLeast(0), edgePadding = 8.dp) {
                    REQUEST_TABS.forEach { t ->
                        Tab(selected = t == current, onClick = { vm.setStatus(t) }, text = { Text(t?.let(::titleCase) ?: "All") })
                    }
                }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    pagedItems(
                        list,
                        if (current == "PENDING") "Nothing waiting for review" else "No requests",
                        vm.list::refresh, vm.list::loadMore, key = { it.id },
                    ) { r ->
                        RequestCard(
                            r,
                            onOpenUser = { r.requestedBy?.let(onOpenUser) },
                            onOpenBook = { r.bookId?.let(onOpenBook) },
                            onDecide = { decision -> vm.resetReview(); reviewing = r to decision },
                        )
                    }
                }
            }
        }

        reviewing?.let { (request, decision) ->
            var note by rememberSaveable(request.id) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!review.busy) reviewing = null },
                title = { Text(if (decision == "APPROVED") "Approve request" else "Reject request") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        review.error?.let { Notice(adminErrorMessage(it), isError = true) }
                        Text(request.description, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            note, { note = it.take(1000) }, label = { Text("Note to the member (optional)") }, minLines = 2,
                            supportingText = { Text("Shown on the member's \"My requests\" page. ${note.length}/1000") },
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { vm.review(request, decision, note.trim().ifBlank { null }) { reviewing = null } },
                        enabled = !review.busy,
                        colors = if (decision == "APPROVED") ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(if (review.busy) "Saving…" else if (decision == "APPROVED") "Approve" else "Reject") }
                },
                dismissButton = { TextButton(onClick = { reviewing = null }, enabled = !review.busy) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun RequestCard(r: ModificationRequest, onOpenUser: () -> Unit, onOpenBook: () -> Unit, onDecide: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RequestChip(r.status)
                StatusChip(titleCase(r.requestType), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                r.bookTitle ?: "General request",
                fontWeight = FontWeight.SemiBold,
                color = if (r.bookId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = if (r.bookId != null) Modifier.clickable(onClick = onOpenBook) else Modifier,
            )
            Text(r.description)
            Text(
                "${r.requesterName ?: "Member"} · ${formatDate(r.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenUser),
            )
            r.reviewNote?.let { Notice("Note: $it") }
            if (r.status == "PENDING") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDecide("APPROVED") }) { Text("Approve") }
                    OutlinedButton(onClick = { onDecide("REJECTED") }) { Text("Reject") }
                }
            }
        }
    }
}

// ================================================================ audit (web: /admin/audit)

data class AuditFilters(
    val action: String? = null,
    val entityType: String? = null,
    val entityId: String = "",
    val actorId: String = "",
    val from: String? = null,
    val to: String? = null,
)

private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Calendar day in the device timezone -> instant (start of day, or start of the next day for "to"). */
private fun dayToInstant(day: String?, endOfDay: Boolean = false): String? = day?.let {
    LocalDate.parse(it).plusDays(if (endOfDay) 1 else 0).atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
}

class AuditViewModel(private val c: AppContainer) : ViewModel() {
    private val _filters = MutableStateFlow(AuditFilters())
    val filters = _filters.asStateFlow()
    val list = PagedLoader(viewModelScope, c.json, pageSize = 50) { page, size ->
        val f = _filters.value
        c.adminApi.audit(
            f.action, f.entityType,
            f.entityId.takeIf { UUID_RE.matches(it) }, f.actorId.takeIf { UUID_RE.matches(it) },
            dayToInstant(f.from), dayToInstant(f.to, endOfDay = true), page, size,
        )
    }

    init {
        list.refresh()
    }

    fun setFilters(f: AuditFilters) {
        val idsChanged = f.entityId != _filters.value.entityId || f.actorId != _filters.value.actorId
        _filters.value = f
        // Only search once an id is complete (or cleared).
        if (!idsChanged || listOf(f.entityId, f.actorId).all { it.isEmpty() || UUID_RE.matches(it) }) list.refresh()
    }
}

private val TIME_FORMAT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
fun AuditScreen(onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    RequireStaff(adminOnly = true, onBack = onBack) {
        val vm = appViewModel { AuditViewModel(it) }
        val f by vm.filters.collectAsStateWithLifecycle()
        val list by vm.list.state.collectAsStateWithLifecycle()
        var showFilters by rememberSaveable { mutableStateOf(false) }

        Scaffold(
            topBar = {
                AdminTopBar("Audit log", onBack) {
                    TextButton(onClick = { showFilters = !showFilters }) { Text(if (showFilters) "Hide filters" else "Filters") }
                }
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                if (showFilters) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            DropdownField("Action", f.action, listOf(null to "Any action") + AUDIT_ACTIONS.map { it to it }, { vm.setFilters(f.copy(action = it)) })
                            DropdownField("Entity type", f.entityType, listOf(null to "Any type") + AUDIT_ENTITY_TYPES.map { it to it }, { vm.setFilters(f.copy(entityType = it)) })
                            OutlinedTextField(
                                f.entityId, { vm.setFilters(f.copy(entityId = it.trim())) }, label = { Text("Entity ID") }, singleLine = true,
                                isError = f.entityId.isNotEmpty() && !UUID_RE.matches(f.entityId),
                                supportingText = { Text(if (f.entityId.isNotEmpty() && !UUID_RE.matches(f.entityId)) "Must be a full UUID" else "") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                f.actorId, { vm.setFilters(f.copy(actorId = it.trim())) }, label = { Text("Actor user ID") }, singleLine = true,
                                isError = f.actorId.isNotEmpty() && !UUID_RE.matches(f.actorId),
                                supportingText = { Text(if (f.actorId.isNotEmpty() && !UUID_RE.matches(f.actorId)) "Must be a full UUID" else "") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DateField("From", f.from, { vm.setFilters(f.copy(from = it)) }, Modifier.weight(1f), clearable = true)
                                DateField("To (inclusive)", f.to, { vm.setFilters(f.copy(to = it)) }, Modifier.weight(1f), clearable = true)
                            }
                            TextButton(onClick = { vm.setFilters(AuditFilters()) }) { Text("Clear filters") }
                        }
                    }
                }
                item {
                    Text(
                        "Append-only record of administrative and security events. Newest first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                pagedItems(list, "No audit entries match these filters", vm.list::refresh, vm.list::loadMore, key = { it.id }) { e ->
                    AuditRow(e, onOpenUser)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AuditRow(e: AuditLogEntry, onOpenUser: (String) -> Unit) {
    var open by rememberSaveable(e.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(e.action, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        val time = runCatching { java.time.Instant.parse(e.createdAt).atZone(ZoneId.systemDefault()).format(TIME_FORMAT) }.getOrDefault(e.createdAt)
        Text(
            listOfNotNull(time, e.entityType?.let { t -> t + (e.entityId?.let { " ${it.take(8)}…" } ?: "") }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        e.actorUserId?.let { actor ->
            Text(
                "By ${actor.take(8)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpenUser(actor) },
            )
        }
        if (open) {
            val details = buildString {
                e.entityId?.let { appendLine("Entity: $it") }
                e.actorUserId?.let { appendLine("Actor: $it") }
                e.ipAddress?.let { appendLine("IP: $it") }
                e.details?.forEach { (k, v) -> appendLine("$k: $v") }
            }.trim()
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
