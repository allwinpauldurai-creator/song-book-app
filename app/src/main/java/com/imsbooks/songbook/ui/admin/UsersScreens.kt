package com.imsbooks.songbook.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.ACCOUNT_STATUSES
import com.imsbooks.songbook.data.AdminMembership
import com.imsbooks.songbook.data.AdminUserDetail
import com.imsbooks.songbook.data.AdminUserSummary
import com.imsbooks.songbook.data.CreateUserRequest
import com.imsbooks.songbook.data.Roles
import com.imsbooks.songbook.data.UpdateUserRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.ui.appContainer
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.ui.components.MobileField
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.ui.components.PasswordField
import com.imsbooks.songbook.util.Mobile
import com.imsbooks.songbook.util.formatDate
import com.imsbooks.songbook.util.passwordProblem
import com.imsbooks.songbook.util.titleCase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun roleList(publisher: Boolean, admin: Boolean) = buildList {
    add(Roles.MEMBER)
    if (publisher) add(Roles.PUBLISHER)
    if (admin) add(Roles.ADMIN)
}

// ================================================================ list (web: /admin/users)

data class UserFilters(val q: String = "", val status: String? = null, val role: String? = null)

@OptIn(FlowPreview::class)
class UsersViewModel(private val c: AppContainer, initial: UserFilters) : ViewModel() {
    private val _filters = MutableStateFlow(initial)
    val filters = _filters.asStateFlow()
    val list = PagedLoader(viewModelScope, c.json) { page, size ->
        val f = _filters.value
        c.adminApi.users(f.q.trim().ifBlank { null }, f.status, f.role, page, size)
    }

    init {
        list.refresh()
        // Search as you type, without a request per keystroke.
        viewModelScope.launch { _filters.drop(1).debounce(300).collect { list.refresh() } }
    }

    fun setFilters(f: UserFilters) {
        _filters.value = f
    }
}

@Composable
fun UsersScreen(status: String?, role: String?, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    RequireStaff(adminOnly = true, onBack = onBack) {
        val vm = appViewModel(key = "users-$status-$role") { UsersViewModel(it, UserFilters(status = status, role = role)) }
        val filters by vm.filters.collectAsStateWithLifecycle()
        val list by vm.list.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { AdminTopBar("Users", onBack) },
            floatingActionButton = {
                FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = "Add user") }
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = filters.q,
                            onValueChange = { vm.setFilters(filters.copy(q = it)) },
                            label = { Text("Search name, mobile or email") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DropdownField(
                                "Status", filters.status,
                                listOf(null to "Any status") + ACCOUNT_STATUSES.map { it to titleCase(it) },
                                { vm.setFilters(filters.copy(status = it)) },
                                Modifier.weight(1f),
                            )
                            DropdownField(
                                "Role", filters.role,
                                listOf(null to "Any role", Roles.MEMBER to "Member", Roles.PUBLISHER to "Publisher", Roles.ADMIN to "Admin"),
                                { vm.setFilters(filters.copy(role = it)) },
                                Modifier.weight(1f),
                            )
                        }
                        if (list.page >= 0) {
                            Text("${list.totalElements} user(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                pagedItems(list, "No users match these filters", vm.list::refresh, vm.list::loadMore, key = { it.id }) { u ->
                    UserRow(u) { onOpen(u.id) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun UserRow(u: AdminUserSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(u.fullName, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(Mobile.format(u.mobile).ifBlank { null }, u.email).joinToString(" · ").ifBlank { "No contact details" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AccountChip(u.accountStatus)
            u.roles.filter { it != Roles.MEMBER }.forEach { StatusChip(titleCase(it), MaterialTheme.colorScheme.primary) }
            if (!u.mobileVerified) StatusChip("Unverified", MaterialTheme.colorScheme.tertiary)
        }
    }
}

// ================================================================ create (web: UsersPage "Add user" dialog)

data class FormStatus(val busy: Boolean = false, val error: Throwable? = null, val done: Boolean = false)

class NewUserViewModel(private val c: AppContainer) : ViewModel() {
    private val _status = MutableStateFlow(FormStatus())
    val status = _status.asStateFlow()
    var createdId: String? = null
        private set

    fun create(body: CreateUserRequest) {
        _status.value = FormStatus(busy = true)
        viewModelScope.launch {
            _status.value = try {
                createdId = apiCall(c.json) { c.adminApi.createUser(body) }.id
                FormStatus(done = true)
            } catch (e: Exception) {
                FormStatus(error = e)
            }
        }
    }
}

@Composable
fun NewUserScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    RequireStaff(adminOnly = true, onBack = onBack) {
        val vm = appViewModel { NewUserViewModel(it) }
        val status by vm.status.collectAsStateWithLifecycle()
        var fullName by rememberSaveable { mutableStateOf("") }
        var mobile by rememberSaveable { mutableStateOf("") }
        var email by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var verified by rememberSaveable { mutableStateOf(true) }
        var publisher by rememberSaveable { mutableStateOf(false) }
        var admin by rememberSaveable { mutableStateOf(false) }
        var submitted by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(status.done) { if (status.done) vm.createdId?.let(onCreated) }

        val fields = status.error.fields()
        val e164 = Mobile.normalize(mobile)
        val pwProblem = passwordProblem(password, e164).takeIf { password.isNotEmpty() }
        val valid = fullName.isNotBlank() && e164 != null && password.isNotEmpty() && pwProblem == null

        Scaffold(topBar = { AdminTopBar("Add user", onBack) }) { padding ->
            Column(
                Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (status.error != null && fields.isEmpty()) Notice(adminErrorMessage(status.error!!), isError = true)
                OutlinedTextField(
                    fullName, { fullName = it.take(150) }, label = { Text("Full name") }, singleLine = true,
                    isError = fields["fullName"] != null, supportingText = fields["fullName"]?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                MobileField(mobile, { mobile = it }, showError = submitted, serverError = fields["mobile"])
                OutlinedTextField(
                    email, { email = it.take(254) }, label = { Text("Email (optional)") }, singleLine = true,
                    isError = fields["email"] != null, supportingText = { Text(fields["email"] ?: "Contact only.") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    password, { password = it }, "Initial password",
                    isError = pwProblem != null || fields["password"] != null,
                    supportingText = fields["password"] ?: pwProblem ?: "Share it securely; the user can change it in their profile.",
                    imeAction = ImeAction.Done,
                )
                CheckRow("Mobile number already verified (you vouch for it; no SMS code needed)", verified) { verified = it }
                CheckRow("Publisher (can create books and edit their own)", publisher) { publisher = it }
                CheckRow("Administrator", admin) { admin = it }
                Notice("Admin-created users get no membership automatically. Add one on the user page.")
                Button(
                    onClick = {
                        submitted = true
                        if (valid) {
                            vm.create(
                                CreateUserRequest(
                                    fullName = fullName.trim(),
                                    mobile = e164!!,
                                    email = email.trim().ifBlank { null },
                                    password = password,
                                    roles = roleList(publisher, admin),
                                    mobileVerified = verified,
                                ),
                            )
                        }
                    },
                    enabled = !status.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (status.busy) "Creating…" else "Create user") }
            }
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ================================================================ detail (web: /admin/users/:id)

data class UserDetailState(
    val user: AdminUserDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val save: FormStatus = FormStatus(),
)

class UserDetailViewModel(private val c: AppContainer, private val userId: String) : ViewModel() {
    private val _state = MutableStateFlow(UserDetailState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val user = apiCall(c.json) { c.adminApi.user(userId) }
                _state.update { it.copy(user = user, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = adminErrorMessage(e)) }
            }
        }
    }

    fun save(body: UpdateUserRequest) {
        _state.update { it.copy(save = FormStatus(busy = true)) }
        viewModelScope.launch {
            try {
                val user = apiCall(c.json) { c.adminApi.updateUser(userId, body) }
                _state.update { it.copy(user = user, save = FormStatus(done = true)) }
                // Editing yourself: refresh the session's profile (roles may have changed).
                if (user.id == c.session.currentUser?.id) runCatching { c.session.userUpdated(apiCall(c.json) { c.api.me() }) }
            } catch (e: Exception) {
                _state.update { it.copy(save = FormStatus(error = e)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(userId: String, onBack: () -> Unit) {
    RequireStaff(adminOnly = true, onBack = onBack) { me ->
        val vm = appViewModel(key = "user-$userId") { UserDetailViewModel(it, userId) }
        val state by vm.state.collectAsStateWithLifecycle()
        var membershipDialog by remember { mutableStateOf<MembershipDialogTarget?>(null) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.save.done) { if (state.save.done) snackbar.showSnackbar("Saved.") }

        Scaffold(topBar = { AdminTopBar(state.user?.fullName ?: "User", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            val user = state.user
            when {
                user == null && state.loading -> LoadingBox(Modifier.padding(padding))
                user == null -> ErrorState(state.error ?: "", Modifier.padding(padding), onRetry = vm::load)
                else -> Column(
                    Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Re-key the form on every saved version so it shows the server's values.
                    androidx.compose.runtime.key(user.version) {
                        UserForm(user, isSelf = user.id == me.id, status = state.save, onSave = vm::save)
                    }
                    MembershipsCard(
                        memberships = user.memberships,
                        onAdd = { membershipDialog = MembershipDialogTarget(null, user.id, user.fullName) },
                        onEdit = { membershipDialog = MembershipDialogTarget(it, user.id, user.fullName) },
                    )
                    Text(
                        "Created ${formatDate(user.createdAt)} · updated ${formatDate(user.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        membershipDialog?.let { target ->
            MembershipDialog(target, onDismiss = { membershipDialog = null }, onSaved = {
                membershipDialog = null
                vm.load()
            })
        }
    }
}

@Composable
private fun UserForm(user: AdminUserDetail, isSelf: Boolean, status: FormStatus, onSave: (UpdateUserRequest) -> Unit) {
    var fullName by rememberSaveable { mutableStateOf(user.fullName) }
    var mobile by rememberSaveable { mutableStateOf(Mobile.format(user.mobile)) }
    var verified by rememberSaveable { mutableStateOf(user.mobileVerified) }
    var email by rememberSaveable { mutableStateOf(user.email.orEmpty()) }
    var accountStatus by rememberSaveable { mutableStateOf(user.accountStatus) }
    var publisher by rememberSaveable { mutableStateOf(Roles.PUBLISHER in user.roles) }
    var admin by rememberSaveable { mutableStateOf(Roles.ADMIN in user.roles) }

    val e164 = Mobile.normalize(mobile)
    val fields = status.error.fields()
    val dirty = fullName.trim() != user.fullName || e164 != user.mobile || verified != user.mobileVerified ||
        email.trim() != user.email.orEmpty() || accountStatus != user.accountStatus ||
        publisher != (Roles.PUBLISHER in user.roles) || admin != (Roles.ADMIN in user.roles)
    val valid = fullName.isNotBlank() && e164 != null

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            if (status.error != null && fields.isEmpty()) Notice(adminErrorMessage(status.error), isError = true)
            OutlinedTextField(
                fullName, { fullName = it.take(150) }, label = { Text("Full name") }, singleLine = true,
                isError = fullName.isBlank() || fields["fullName"] != null, supportingText = fields["fullName"]?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            MobileField(mobile, { mobile = it }, showError = true, serverError = fields["mobile"])
            CheckRow("Mobile verified (you vouch for it; otherwise the user confirms it by SMS)", verified) { verified = it }
            OutlinedTextField(
                email, { email = it.take(254) }, label = { Text("Email (optional)") }, singleLine = true,
                isError = fields["email"] != null, supportingText = { Text(fields["email"] ?: "Contact only.") },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField("Account status", accountStatus, ACCOUNT_STATUSES.map { it to titleCase(it) }, { if (it != null) accountStatus = it })
            if (user.lockedUntil != null) {
                Text("Locked until ${formatDate(user.lockedUntil)} after failed log-ins.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Roles", style = MaterialTheme.typography.labelLarge)
            CheckRow("Member", true, enabled = false) {}
            CheckRow("Publisher", publisher) { publisher = it }
            CheckRow("Administrator", admin) { admin = it }
            if (isSelf && (accountStatus != "ACTIVE" || !admin)) {
                Notice("You are editing your own account. Disabling it or removing your admin role takes effect immediately.", isError = true)
            }
            Button(
                onClick = {
                    onSave(
                        UpdateUserRequest(
                            fullName = fullName.trim(),
                            mobile = e164!!,
                            mobileVerified = verified,
                            email = email.trim().ifBlank { null },
                            accountStatus = accountStatus,
                            roles = roleList(publisher, admin),
                            version = user.version,
                        ),
                    )
                },
                enabled = dirty && valid && !status.busy,
            ) { Text(if (status.busy) "Saving…" else "Save changes") }
        }
    }
}

@Composable
private fun MembershipsCard(memberships: List<AdminMembership>, onAdd: () -> Unit, onEdit: (AdminMembership) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Memberships", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onAdd) { Text("Add") }
            }
            if (memberships.isEmpty()) {
                Text("No memberships. Without one, this user cannot read books (admins can).", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            memberships.forEach { m ->
                HorizontalDivider()
                MembershipRow(m, showUser = false, onEdit = { onEdit(m) })
            }
        }
    }
}

@Composable
fun MembershipRow(m: AdminMembership, showUser: Boolean, onEdit: () -> Unit, onOpenUser: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showUser) {
                Text(
                    m.userFullName,
                    fontWeight = FontWeight.SemiBold,
                    modifier = if (onOpenUser != null) Modifier.clickable(onClick = onOpenUser) else Modifier,
                    color = if (onOpenUser != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            MembershipChip(m.status, m.validToday)
            Text(
                "${formatDate(m.startDate)} – ${m.endDate?.let { formatDate(it) } ?: "no end date"}",
                style = MaterialTheme.typography.bodySmall,
            )
            m.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit membership") }
    }
}
