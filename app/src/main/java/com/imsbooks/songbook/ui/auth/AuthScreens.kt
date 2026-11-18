package com.imsbooks.songbook.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imsbooks.songbook.ui.appViewModel
import com.imsbooks.songbook.ui.components.MobileField
import com.imsbooks.songbook.ui.components.Notice
import com.imsbooks.songbook.ui.components.PasswordField
import com.imsbooks.songbook.util.Mobile
import com.imsbooks.songbook.util.isCompleteCode
import com.imsbooks.songbook.util.passwordProblem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScaffold(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------- login

@Composable
fun LoginScreen(
    notice: String?,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    val vm = appViewModel { LoginViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var mobile by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }
    val e164 = Mobile.normalize(mobile)

    LaunchedEffect(state.done) { if (state.done) onSignedIn() }

    val submit = {
        touched = true
        if (e164 != null && password.isNotEmpty()) vm.login(e164, password)
    }

    AuthScaffold("Log in", "Sign in with your mobile number.", onBack) {
        notice?.let { Notice(it) }
        state.error?.let { e ->
            val text = when (e.code) {
                "INVALID_CREDENTIALS" -> "Wrong mobile number or password."
                "ACCOUNT_LOCKED" -> "Too many failed attempts. Your account is locked for a while; try again later or reset your password."
                "ACCOUNT_DISABLED" -> "This account is disabled. Please contact an administrator."
                else -> e.message ?: ""
            }
            Notice(text, isError = true)
        }
        MobileField(mobile, { mobile = it }, showError = touched)
        PasswordField(
            password,
            { password = it },
            label = "Password",
            isError = touched && password.isEmpty(),
            imeAction = ImeAction.Done,
        )
        Button(onClick = submit, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "Signing in…" else "Log in")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onForgotPassword) { Text("Forgot password?") }
            TextButton(onClick = onRegister) { Text("Create an account") }
        }
    }
}

// ---------------------------------------------------------------------------------------------- register

@Composable
fun RegisterScreen(onBack: () -> Unit, onRegistered: () -> Unit, onLogin: () -> Unit, onForgotPassword: () -> Unit) {
    val vm = appViewModel { RegisterViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var fullName by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onRegistered() }

    val e164 = Mobile.normalize(mobile)
    val pwProblem = passwordProblem(password, e164)
    val mismatch = confirm.isNotEmpty() && confirm != password
    val fields = state.error?.fieldMap.orEmpty()
    val alreadyRegistered = state.error?.code == "MOBILE_ALREADY_REGISTERED"

    AuthScaffold("Create an account", "Register to read and download song books.", onBack) {
        if (alreadyRegistered) {
            Notice("This mobile number is already registered. Log in or reset your password.")
            Row {
                TextButton(onClick = onLogin) { Text("Log in") }
                TextButton(onClick = onForgotPassword) { Text("Reset password") }
            }
        } else if (state.error != null && fields.isEmpty()) {
            Notice(state.error?.message ?: "", isError = true)
        }
        OutlinedTextField(
            value = fullName,
            onValueChange = { fullName = it.take(150) },
            label = { Text("Full name") },
            singleLine = true,
            isError = (touched && fullName.isBlank()) || fields["fullName"] != null,
            supportingText = { Text(fields["fullName"] ?: if (touched && fullName.isBlank()) "Enter your name." else "") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        MobileField(mobile, { mobile = it }, showError = touched, serverError = fields["mobile"])
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.take(254) },
            label = { Text("Email (optional)") },
            singleLine = true,
            isError = fields["email"] != null,
            supportingText = { Text(fields["email"] ?: "Only used to contact you. You sign in with your mobile number.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            password,
            { password = it },
            label = "Password",
            isError = ((touched || password.isNotEmpty()) && pwProblem != null) || fields["password"] != null,
            supportingText = fields["password"] ?: if ((touched || password.isNotEmpty()) && pwProblem != null) pwProblem else "At least 10 characters.",
        )
        PasswordField(
            confirm,
            { confirm = it },
            label = "Confirm password",
            isError = mismatch || (touched && confirm.isEmpty()),
            supportingText = if (mismatch) "Passwords do not match." else null,
            imeAction = ImeAction.Done,
        )
        Button(
            onClick = {
                touched = true
                if (fullName.isNotBlank() && e164 != null && pwProblem == null && confirm == password) {
                    vm.register(fullName.trim(), e164, email.trim().ifEmpty { null }, password)
                }
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "Creating account…" else "Create account") }
        TextButton(onClick = onLogin, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Already a member? Log in")
        }
    }
}

// ---------------------------------------------------------------------------------------------- forgot password

@Composable
fun ForgotPasswordScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val vm = appViewModel { ForgotPasswordViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var mobile by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }
    val e164 = Mobile.normalize(mobile)

    LaunchedEffect(state.form.done) { if (state.form.done) onDone() }

    if (!state.codeSent) {
        AuthScaffold("Forgot password", "Enter your mobile number and we will send you a code by SMS.", onBack) {
            state.sendError?.let { Notice(it.message ?: "", isError = true) }
            MobileField(mobile, { mobile = it }, showError = touched, imeAction = ImeAction.Done)
            Button(
                onClick = {
                    touched = true
                    if (e164 != null) vm.sendCode(e164)
                },
                enabled = !state.sending && mobile.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.sending) "Sending…" else "Send code") }
        }
        return
    }

    val problem = if (password.isNotEmpty()) passwordProblem(password, e164) else null
    val mismatch = confirm.isNotEmpty() && confirm != password
    val form = state.form
    val fields = form.error?.fieldMap.orEmpty()

    AuthScaffold("Choose a new password", null, onBack) {
        Text(
            "If an account exists for ${Mobile.format(e164)}, we have sent it a 6-digit code. It is valid for 10 minutes.",
        )
        state.sendError?.let { Notice(it.message ?: "", isError = true) }
        if (form.error != null && fields.isEmpty()) {
            Notice(
                if (form.error.code == "INVALID_CODE") "The code is wrong or has expired. Check the SMS or request a new code."
                else form.error.message ?: "",
                isError = true,
            )
        }
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
            label = { Text("6-digit code") },
            singleLine = true,
            isError = touched && !isCompleteCode(code),
            supportingText = { if (touched && !isCompleteCode(code)) Text("Enter the 6 digits from the SMS.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            password,
            { password = it },
            label = "New password",
            isError = problem != null || fields["newPassword"] != null,
            supportingText = fields["newPassword"] ?: problem ?: "At least 10 characters.",
        )
        PasswordField(
            confirm,
            { confirm = it },
            label = "Confirm new password",
            isError = mismatch,
            supportingText = if (mismatch) "Passwords do not match." else null,
            imeAction = ImeAction.Done,
        )
        Button(
            onClick = {
                touched = true
                if (e164 != null && isCompleteCode(code) && password.isNotEmpty() && problem == null && confirm == password) {
                    vm.reset(e164, code, password)
                }
            },
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (form.busy) "Saving…" else "Set new password") }
        TextButton(
            onClick = { if (e164 != null) vm.sendCode(e164) },
            enabled = state.resendSeconds == 0 && !state.sending,
        ) { Text(if (state.resendSeconds == 0) "Resend code" else "Resend code in ${state.resendSeconds}s") }
    }
}
