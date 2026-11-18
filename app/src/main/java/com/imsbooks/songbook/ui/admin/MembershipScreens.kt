package com.imsbooks.songbook.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.AdminMembership
import com.imsbooks.songbook.data.AdminUserSummary
import com.imsbooks.songbook.data.CreateMembershipRequest
import com.imsbooks.songbook.data.MEMBERSHIP_STATUSES
import com.imsbooks.songbook.data.UpdateMembershipRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.util.Mobile
import com.imsbooks.songbook.util.titleCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ================================================================ list (web: /admin/memberships)

class MembershipsViewModel(private val c: AppContainer, initialStatus: String?) : ViewModel() {
    private val _status = MutableStateFlow(initialStatus)
    val status = _status.asStateFlow()
    val list = PagedLoader(viewModelScope, c.json) { page, size -> c.adminApi.memberships(null, _status.value, page, size) }

    init {
        list.refresh()
    }

    fun setStatus(s: String?) {
        _status.value = s
        list.refresh()
    }
}

@Composable
fun MembershipsScreen(status: String?, onBack: () -> Unit, onOpenUser: (String) -> Unit) {
    RequireStaff(adminOnly = true, onBack = onBack) {
        val vm = appViewModel(key = "memberships-$status") { MembershipsViewModel(it, status) }
        val current by vm.status.collectAsStateWithLifecycle()
        val list by vm.list.state.collectAsStateWithLifecycle()
        var dialog by remember { mutableStateOf<MembershipDialogTarget?>(null) }

        Scaffold(
            topBar = { AdminTopBar("Memberships", onBack) },
            floatingActionButton = {
                FloatingActionButton(onClick = { dialog = MembershipDialogTarget(null, null, null) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add membership")
                }
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Each user can have many records over time, but at most one active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DropdownField(
                            "Status", current,
                            listOf(null to "Any status") + MEMBERSHIP_STATUSES.map { it to titleCase(it) },
                            vm::setStatus,
                        )
                    }
                }
                pagedItems(list, "No memberships match these filters", vm.list::refresh, vm.list::loadMore, key = { it.id }) { m ->
                    MembershipRow(m, showUser = true, onEdit = { dialog = MembershipDialogTarget(m, m.userId, m.userFullName) }, onOpenUser = { onOpenUser(m.userId) })
                    HorizontalDivider()
                }
            }
        }

        dialog?.let { target ->
            MembershipDialog(target, onDismiss = { dialog = null }, onSaved = {
                dialog = null
                vm.list.refresh()
            })
        }
    }
}

// ================================================================ dialog (web: MembershipDialog)

/** Edit [membership], or add one for [userId] (null = pick the user in the dialog). */
data class MembershipDialogTarget(val membership: AdminMembership?, val userId: String?, val userName: String?)

class MembershipFormViewModel(private val c: AppContainer) : ViewModel() {
    private val _status = MutableStateFlow(FormStatus())
    val status = _status.asStateFlow()
    private val _matches = MutableStateFlow<List<AdminUserSummary>>(emptyList())
    val matches = _matches.asStateFlow()
    private var search: kotlinx.coroutines.Job? = null

    fun searchUsers(term: String) {
        search?.cancel()
        search = viewModelScope.launch {
            delay(300)
            _matches.value = runCatching {
                apiCall(c.json) { c.adminApi.users(term.trim().ifBlank { null }, null, null, 0, 20, "fullName,asc") }.content
            }.getOrDefault(emptyList())
        }
    }

    fun save(target: MembershipDialogTarget, userId: String, start: String, end: String?, status: String, notes: String?) {
        _status.value = FormStatus(busy = true)
        viewModelScope.launch {
            _status.value = try {
                val m = target.membership
                apiCall(c.json) {
                    if (m != null) c.adminApi.updateMembership(m.id, UpdateMembershipRequest(start, end, status, notes, m.version))
                    else c.adminApi.createMembership(CreateMembershipRequest(userId, start, end, status, notes))
                }
                FormStatus(done = true)
            } catch (e: Exception) {
                FormStatus(error = e)
            }
        }
    }
}

@Composable
fun MembershipDialog(target: MembershipDialogTarget, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val m = target.membership
    val vm = appViewModel(key = "membership-${m?.id ?: "new"}-${target.userId}") { MembershipFormViewModel(it) }
    val status by vm.status.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()

    var userId by rememberSaveable { mutableStateOf(target.userId) }
    var userName by rememberSaveable { mutableStateOf(target.userName) }
    var query by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(m?.startDate ?: todayIso()) }
    var end by rememberSaveable { mutableStateOf(m?.endDate) }
    var membershipStatus by rememberSaveable { mutableStateOf(m?.status ?: "ACTIVE") }
    var notes by rememberSaveable { mutableStateOf(m?.notes.orEmpty()) }

    LaunchedEffect(status.done) { if (status.done) onSaved() }
    LaunchedEffect(Unit) { if (target.userId == null) vm.searchUsers("") }

    val fields = status.error.fields()
    val badRange = end != null && end!! < start
    val canSave = userId != null && start.isNotBlank() && !badRange && !status.busy

    AlertDialog(
        onDismissRequest = { if (!status.busy) onDismiss() },
        title = { Text(if (m != null) "Edit membership" else "Add membership") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.error != null && fields.isEmpty()) Notice(adminErrorMessage(status.error!!), isError = true)
                if (target.userId != null) {
                    Text(userName.orEmpty(), fontWeight = FontWeight.SemiBold)
                } else {
                    // User picker: search, then tap a result.
                    OutlinedTextField(
                        value = if (userId != null) userName.orEmpty() else query,
                        onValueChange = {
                            userId = null
                            userName = null
                            query = it
                            vm.searchUsers(it)
                        },
                        label = { Text("User") },
                        placeholder = { Text("Search name or mobile") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (userId == null) {
                        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                            matches.forEach { u ->
                                Text(
                                    "${u.fullName} · ${Mobile.format(u.mobile).ifBlank { u.email.orEmpty() }}",
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        userId = u.id
                                        userName = u.fullName
                                    }.padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
                DropdownField("Status", membershipStatus, MEMBERSHIP_STATUSES.map { it to titleCase(it) }, { if (it != null) membershipStatus = it })
                if (m == null && membershipStatus == "ACTIVE") {
                    Notice("If this user already has an active membership, it will be cancelled and replaced by this one.")
                }
                DateField("Start date", start, { if (it != null) start = it }, isError = fields["startDate"] != null, supportingText = fields["startDate"])
                DateField(
                    "End date", end, { end = it }, clearable = true, isError = badRange || fields["endDate"] != null,
                    supportingText = if (badRange) "Must be on or after the start date." else fields["endDate"] ?: "Leave empty for no end date.",
                )
                OutlinedTextField(
                    notes, { notes = it.take(500) }, label = { Text("Notes") }, minLines = 2,
                    supportingText = { Text("${notes.length}/500") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.save(target, userId!!, start, end, membershipStatus, notes.trim().ifBlank { null }) },
                enabled = canSave,
            ) { Text(if (status.busy) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !status.busy) { Text("Cancel") } },
    )
}

