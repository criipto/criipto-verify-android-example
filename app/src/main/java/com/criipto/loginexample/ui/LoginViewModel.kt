package com.criipto.loginexample.ui

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.criipto.loginexample.LoginManager
import com.criipto.loginexample.eID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class LoginState {
    class LoggedIn(
        val idToken: String, val name: String?, val sub: String, val identityscheme: String
    ) : LoginState()

    class NotLoggedIn(var errorMessage: String? = null) : LoginState()
    class Loading() : LoginState()
}

class LoginViewModel(initialState: LoginState) : ViewModel() {
    // The login manager cannot be instantiated until we have an activity. However, we don't have an activity in compose previews. In order to avoid null checks, we make it a lateinit property
    private lateinit var loginManager: LoginManager
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<LoginState> = _uiState.asStateFlow()

    fun setActivity(activity: ComponentActivity) {
        loginManager = LoginManager(activity)
    }

    fun login(eid: eID) = viewModelScope.launch {
        _uiState.update { LoginState.Loading() }
        try {
            val jwt = loginManager.login(eid)
            val nameClaim = jwt.getClaim("name")

            _uiState.update {
                LoginState.LoggedIn(
                    jwt.token,
                    if (nameClaim.isMissing) null else nameClaim.asString(),
                    jwt.getClaim("sub").asString(),
                    jwt.getClaim("identityscheme").asString(),
                )
            }
        } catch (ex: Exception) {
            _uiState.update { LoginState.NotLoggedIn(ex.localizedMessage) }
        }
    }

    fun logout() = viewModelScope.launch {
        _uiState.update { LoginState.Loading() }
        try {
            loginManager.logout((_uiState.value as? LoginState.LoggedIn)?.idToken)
            _uiState.update { LoginState.NotLoggedIn() }
        } catch (ex: Exception) {
            _uiState.update { LoginState.NotLoggedIn(ex.localizedMessage) }
        }
    }
}