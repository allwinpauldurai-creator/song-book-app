package com.imsbooks.songbook.ui.member

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.ApiException
import com.imsbooks.songbook.data.ChangeMobileRequest
import com.imsbooks.songbook.data.ChangePasswordRequest
import com.imsbooks.songbook.data.ConfirmMobileRequest
import com.imsbooks.songbook.data.UpdateProfileRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.orThrow
import com.imsbooks.songbook.data.session.SessionState
import com.imsbooks.songbook.ui.appContainer
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.auth.FormState
import com.imsbooks.songbook.ui.components.MobileField
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.ui.components.PasswordField
import com.imsbooks.songbook.util.Mobile
import com.imsbooks.songbook.util.isCompleteCode
import com.imsbooks.songbook.util.passwordProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileState(
    val details: FormState = FormState(),
    val password: FormState = FormState(),
    val mobileStep: Int = 0, // 0 = closed, 1 = new number + password, 2 = SMS code
    val mobile: FormState = FormState(),
    val message: String? = null,
)

class ProfileViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    fun saveDetails(fullName: String, email: String?) = run(
        set = { s, f -> s.copy(details = f) },
        success = "Profile saved.",
    ) { c.session.userUpdated(c.api.updateProfile(UpdateProfileRequest(fullName, email))) }

    /** The backend revokes every session after a password change, so the member signs in again. */
    fun changePassword(current: String, new: String) = run(
        set = { s, f -> s.copy(password = f) },
        success = null,
    ) {
        c.api.changePassword(ChangePasswordRequest(current, new)).orThrow(c.json)
        c.session.clearLocal()
    }

    fun openMobileChange(open: Boolean) = _state.update { it.copy(mobileStep = if (open) 1 else 0, mobile = FormState()) }

    fun requestMobileChange(newMobile: String, password: String) = run(
        set = { s, f -> s.copy(mobile = f, mobileStep = if (f.done) 2 else s.mobileStep) },
        success = null,
    ) { c.api.requestMobileChange(ChangeMobileRequest(newMobile, password)) }

    fun confirmMobileChange(code: String) = run(
        set = { s, f -> s.copy(mobile = f, mobileStep = if (f.done) 0 else s.mobileStep) },
        success = "Mobile number changed.",
    ) { c.session.userUpdated(c.api.confirmMobileChange(ConfirmMobileRequest(code))) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun run(set: (ProfileState, FormState) -> ProfileState, success: String?, block: suspend () -> Unit) {
        _state.update { set(it, FormState(busy = true)) }
        viewModelScope.launch {
            try {
                apiCall(c.json) { block() }
                _state.update { set(it, FormState(done = true)).copy(message = success) }
            } catch (e: ApiException) {
                _state.update { set(it, FormState(error = e)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, onSignedOut: () -> Unit) {
    val vm = appViewModel { ProfileViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by appContainer().session.state.collectAsStateWithLifecycle()
    val user = (session as? SessionState.SignedIn)?.user
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(user == null) { if (user == null) onSignedOut() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    if (user == null) return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ------------------------------------------------ details
            Section("Your details") {
                var name by rememberSaveable { mutableStateOf(user.fullName) }
                var email by rememberSaveable { mutableStateOf(user.email.orEmpty()) }
                val fields = state.details.error?.fieldMap.orEmpty()
                if (state.details.error != null && fields.isEmpty()) Notice(state.details.error?.message ?: "", isError = true)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(150) },
                    label = { Text("Full name") },
                    singleLine = true,
                    isError = name.isBlank() || fields["fullName"] != null,
                    supportingText = fields["fullName"]?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(254) },
                    label = { Text("Email (optional)") },
                    singleLine = true,
                    isError = fields["email"] != null || state.details.error?.code == "EMAIL_IN_USE",
                    supportingText = {
                        Text(
                            fields["email"]
                                ?: if (state.details.error?.code == "EMAIL_IN_USE") "This email is used by another account." else "Leave empty to remove it.",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { if (name.isNotBlank()) vm.saveDetails(name.trim(), email.trim().ifEmpty { null }) },
                    enabled = !state.details.busy,
                ) { Text(if (state.details.busy) "Saving…" else "Save") }
            }

            // ------------------------------------------------ mobile number
            Section("Mobile number") {
                Text(Mobile.format(user.mobile).ifEmpty { "No mobile number" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    "You sign in with this number. Changing it needs a code sent by SMS to the new number.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MobileChange(state, vm)
            }

            // ------------------------------------------------ password
            Section("Change password") {
                var current by rememberSaveable { mutableStateOf("") }
                var next by rememberSaveable { mutableStateOf("") }
                var confirm by rememberSaveable { mutableStateOf("") }
                val problem = if (next.isNotEmpty()) passwordProblem(next, user.mobile) else null
                val mismatch = confirm.isNotEmpty() && confirm != next
                val err = state.password.error
                Text(
                    "You will be signed out on all devices and need to log in again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (err != null) {
                    Notice(if (err.code == "INVALID_CURRENT_PASSWORD") "The current password is wrong." else err.message ?: "", isError = true)
                }
                PasswordField(current, { current = it }, label = "Current password")
                PasswordField(next, { next = it }, label = "New password", isError = problem != null, supportingText = problem ?: "At least 10 characters.")
                PasswordField(
                    confirm,
                    { confirm = it },
                    label = "Confirm new password",
                    isError = mismatch,
                    supportingText = if (mismatch) "Passwords do not match." else null,
                    imeAction = ImeAction.Done,
                )
                Button(
                    onClick = { if (current.isNotEmpty() && next.isNotEmpty() && problem == null && confirm == next) vm.changePassword(current, next) },
                    enabled = !state.password.busy,
                ) { Text(if (state.password.busy) "Saving…" else "Change password") }
            }
        }
    }
}

@Composable
private fun MobileChange(state: ProfileState, vm: ProfileViewModel) {
    val err = state.mobile.error
    val fields = err?.fieldMap.orEmpty()
    when (state.mobileStep) {
        0 -> OutlinedButton(onClick = { vm.openMobileChange(true) }) { Text("Change mobile number") }
        1 -> {
            var mobile by rememberSaveable { mutableStateOf("") }
            var password by rememberSaveable { mutableStateOf("") }
            var touched by rememberSaveable { mutableStateOf(false) }
            val e164 = Mobile.normalize(mobile)
            if (err != null && fields.isEmpty()) {
                Notice(
                    when (err.code) {
                        "MOBILE_IN_USE" -> "This number belongs to another account."
                        "SAME_MOBILE" -> "That is already your number."
                        "INVALID_CURRENT_PASSWORD" -> "The current password is wrong."
                        else -> err.message ?: ""
                    },
                    isError = true,
                )
            }
            MobileField(mobile, { mobile = it }, label = "New mobile number", showError = touched, serverError = fields["newMobile"])
            PasswordField(password, { password = it }, label = "Current password", imeAction = ImeAction.Done)
            Button(
                onClick = {
                    touched = true
                    if (e164 != null && password.isNotEmpty()) vm.requestMobileChange(e164, password)
                },
                enabled = !state.mobile.busy,
            ) { Text(if (state.mobile.busy) "Sending…" else "Send code") }
            OutlinedButton(onClick = { vm.openMobileChange(false) }) { Text("Cancel") }
        }
        else -> {
            var code by rememberSaveable { mutableStateOf("") }
            Text("We sent a 6-digit code to the new number. It is valid for 10 minutes.")
            if (err != null) {
                Notice(if (err.code == "INVALID_CODE") "The code is wrong or has expired." else err.message ?: "", isError = true)
            }
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                label = { Text("6-digit code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { if (isCompleteCode(code)) vm.confirmMobileChange(code) }, enabled = !state.mobile.busy) {
                Text(if (state.mobile.busy) "Confirming…" else "Confirm")
            }
            OutlinedButton(onClick = { vm.openMobileChange(false) }) { Text("Cancel") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
