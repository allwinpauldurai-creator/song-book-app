package com.imsbooks.songbook.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.data.ApiException
import com.imsbooks.songbook.data.LoginRequest
import com.imsbooks.songbook.data.MobileRequest
import com.imsbooks.songbook.data.RegisterRequest
import com.imsbooks.songbook.data.ResetPasswordRequest
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.orThrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Common shape of a submitted form. */
data class FormState(
    val busy: Boolean = false,
    val error: ApiException? = null,
    val done: Boolean = false,
)

class LoginViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(FormState())
    val state: StateFlow<FormState> = _state.asStateFlow()

    fun login(mobile: String, password: String) {
        _state.value = FormState(busy = true)
        viewModelScope.launch {
            try {
                val res = apiCall(c.json) { c.api.login(LoginRequest(mobile, password)) }
                c.session.signedIn(res.accessToken, res.user)
                _state.value = FormState(done = true)
            } catch (e: ApiException) {
                _state.value = FormState(error = e)
            }
        }
    }
}

class RegisterViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(FormState())
    val state: StateFlow<FormState> = _state.asStateFlow()

    /** No SMS code: the account is created and signed in (same response as login). */
    fun register(fullName: String, mobile: String, email: String?, password: String) {
        _state.value = FormState(busy = true)
        viewModelScope.launch {
            try {
                val res = apiCall(c.json) { c.api.register(RegisterRequest(fullName, mobile, email, password)) }
                c.session.signedIn(res.accessToken, res.user)
                _state.value = FormState(done = true)
            } catch (e: ApiException) {
                _state.value = FormState(error = e)
            }
        }
    }
}

data class ForgotState(
    val codeSent: Boolean = false,
    val sending: Boolean = false,
    val sendError: ApiException? = null,
    val resendSeconds: Int = 0,
    val form: FormState = FormState(),
)

/** Two steps: (1) mobile number -> SMS code, (2) code + new password. */
class ForgotPasswordViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ForgotState())
    val state: StateFlow<ForgotState> = _state.asStateFlow()

    fun sendCode(mobile: String) {
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                apiCall(c.json) { c.api.forgotPassword(MobileRequest(mobile)) }
                _state.update { it.copy(sending = false, codeSent = true, resendSeconds = RESEND_SECONDS) }
                while (_state.value.resendSeconds > 0) {
                    delay(1000)
                    _state.update { it.copy(resendSeconds = it.resendSeconds - 1) }
                }
            } catch (e: ApiException) {
                _state.update { it.copy(sending = false, sendError = e) }
            }
        }
    }

    fun reset(mobile: String, code: String, newPassword: String) {
        _state.update { it.copy(form = FormState(busy = true)) }
        viewModelScope.launch {
            try {
                apiCall(c.json) { c.api.resetPassword(ResetPasswordRequest(mobile, code, newPassword)).orThrow(c.json) }
                // The backend revokes every session after a reset, so drop any local one too.
                c.session.clearLocal()
                _state.update { it.copy(form = FormState(done = true)) }
            } catch (e: ApiException) {
                _state.update { it.copy(form = FormState(error = e)) }
            }
        }
    }

    private companion object {
        const val RESEND_SECONDS = 60
    }
}
