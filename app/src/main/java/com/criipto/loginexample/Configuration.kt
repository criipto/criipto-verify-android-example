package com.criipto.loginexample

import androidx.core.net.toUri

data object Configuration {
    val domain = "[YOUR CRIIPTO DOMAIN]".toUri()
    const val CLIENT_ID = "[YOUR CLIENT ID]"
    var redirectUri = "$domain/android/callback".toUri()
    var appSwitchUri = "$domain/android/appswitch".toUri()
}