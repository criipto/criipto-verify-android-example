package com.criipto.loginexample

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.browser.auth.AuthTabIntent
import androidx.browser.auth.AuthTabIntent.AuthResult
import androidx.browser.customtabs.CustomTabsClient
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.auth0.jwk.Jwk
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationManagementRequest
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.EndSessionResponse
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.browser.AnyBrowserMatcher
import net.openid.appauth.browser.Browsers
import net.openid.appauth.browser.VersionedBrowserMatcher
import java.security.interfaces.RSAPublicKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


const val TAG = "LoginManager"

enum class eID(val acr_value: String) {
    MOCK("urn:grn:authn:mock"), MITID("urn:grn:authn:dk:mitid:substantial"), SEBANKID("urn:grn:authn:se:bankid"),
    NOBANKID("urn:grn:authn:no:bankid")
}

enum class TabType {
    CustomTab(), AuthTab()
}

sealed class CustomTabResult {
    class CustomTabSuccess(val resultUri: Uri) : CustomTabResult()
    class CustomTabFailure(val ex: AuthorizationException) : CustomTabResult()
}

class LoginManager(
    private val activity: ComponentActivity,
) : DefaultLifecycleObserver {
    /**
     * The AppAuth authorization service, which provides helper methods for OIDC operations, and manages the browser.
     * The service needs access to the activity, so it is initialized in `onCreate`.
     */
    private lateinit var authorizationService: AuthorizationService

    /**
     * The OIDC service configuration for your Criipto domain. Loaded in `prepare()`
     */
    private lateinit var serviceConfiguration: AuthorizationServiceConfiguration

    /**
     * The type of browser tab to user, either custom tab or auth tab.
     * Determining the supported browser requires an activity, so this is set in `onCreate`.
     */
    private lateinit var tabType: TabType

    /**
     * The JWKS (JSON Web Key Set) used by Criipto to sign the returned JWT. Loaded in `prepare()`
     * */
    private var jwks: List<Jwk>? = null

    /**
     * An activity result launcher, used to a launch an auth tab intent and listen for the result.
     * See https://developer.android.com/training/basics/intents/result
     */
    private var authTabIntentLauncher: ActivityResultLauncher<Intent?>

    /**
     * An activity result launcher, used to a launch a custom tab intent and listen for the result.
     * See https://developer.android.com/training/basics/intents/result
     */
    private var customTabIntentLauncher: ActivityResultLauncher<AuthorizationManagementRequest>

    /**
     * The currently in-flight request - Either an authorization or an end session request.
     */
    private var currentRequest: AuthorizationManagementRequest? = null

    /**
     * The continuation that should be invoked when a login request completes
     */
    private var loginRequestContinuation: Continuation<DecodedJWT>? = null

    /**
     * The continuation that should be invoked when a logout request completes
     */
    private var logoutRequestContinuation: Continuation<Unit>? = null

    init {
        activity.lifecycle.addObserver(this)

        authTabIntentLauncher =
            AuthTabIntent.registerActivityResultLauncher(activity, this::handleAuthTabResult)

        customTabIntentLauncher = activity.registerForActivityResult(object :
            ActivityResultContract<AuthorizationManagementRequest, CustomTabResult>() {
            override fun createIntent(
                context: Context, input: AuthorizationManagementRequest
            ): Intent {
                Log.d(TAG, "Creating custom tab intent")

                val customTabIntent =
                    authorizationService.createCustomTabsIntentBuilder(input.toUri())
                        .setSendToExternalDefaultHandlerEnabled(true).build()

                return when (input) {
                    is AuthorizationRequest -> authorizationService.getAuthorizationRequestIntent(
                        input, customTabIntent
                    )

                    is EndSessionRequest -> authorizationService.getEndSessionRequestIntent(
                        input, customTabIntent
                    )

                    else -> throw Exception("Unsupported request type $input")
                }
            }

            override fun parseResult(
                resultCode: Int, intent: Intent?
            ): CustomTabResult {
                Log.d(TAG, "Parsing result from custom tab intent")
                val ex = AuthorizationException.fromIntent(intent)

                return if (ex != null) {
                    CustomTabResult.CustomTabFailure(ex)
                } else {
                    CustomTabResult.CustomTabSuccess(intent!!.data!!)
                }
            }
        }, this::handleCustomTabResult)
    }

    override fun onCreate(owner: LifecycleOwner) {
        prepare()

        val chromeEnabled = CustomTabsClient.getPackageName(
            activity, listOf(Browsers.Chrome.PACKAGE_NAME), true
        ) != null
        val samsungEnabled = CustomTabsClient.getPackageName(
            activity, listOf(Browsers.SBrowser.PACKAGE_NAME), true
        ) != null

        // The authorization service should be initialized here, _not_ when you want to make the login request.
        // Creating the service ahead of time allow appauth to warmup the browser, which makes it boot faster and, more importantly, makes app links work without user interaction. See https://developer.chrome.com/docs/android/custom-tabs/guide-warmup-prefetch
        authorizationService = AuthorizationService(
            activity, AppAuthConfiguration.Builder().setBrowserMatcher(
                if (chromeEnabled) {
                    VersionedBrowserMatcher.CHROME_CUSTOM_TAB
                } else if (samsungEnabled) {
                    VersionedBrowserMatcher.SAMSUNG_CUSTOM_TAB
                } else {
                    AnyBrowserMatcher.INSTANCE
                },
            ).build()
        )

        // Only useful for testing the differences in behaviour between custom tabs and auth tab. In a real-world scenario, you would always want to use auth tabs if available
        val authTabEnabled = false
        val authTabSupported = CustomTabsClient.isAuthTabSupported(
            activity, Browsers.Chrome.PACKAGE_NAME
        )
        println("Auth tab: enabled $authTabEnabled supported $authTabSupported")

        tabType = if (authTabEnabled && authTabSupported) {
            TabType.AuthTab
        } else {
            TabType.CustomTab
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        authorizationService.dispose()
    }

    private fun handleResultUri(uri: Uri) {
        val currentRequest = this.currentRequest
        if (currentRequest == null) {
            Log.d(TAG, "Got a result URI $uri, but no active request.")
            return
        }

        val response = when (currentRequest) {
            is AuthorizationRequest -> AuthorizationResponse.Builder(currentRequest).fromUri(uri)
                .build()

            is EndSessionRequest -> EndSessionResponse.Builder(currentRequest).setState(
                uri.getQueryParameter(
                    "state"
                )
            ).build()

            else -> {
                Log.d(TAG, "Unsupported request type $currentRequest")
                return
            }
        }

        if (currentRequest.state != response.state) {
            Log.w(
                TAG,
                "State returned in authorization response (${response.state}) does not match state from request (${currentRequest.state}) - discarding response"
            )
            return
        }

        when (response) {
            is AuthorizationResponse -> {
                authorizationService.performTokenRequest(
                    response.createTokenExchangeRequest()
                ) { response, ex ->
                    if (ex != null) {
                        loginRequestContinuation?.resumeWithException(ex)
                        return@performTokenRequest
                    }

                    // From TokenResponseCallback - Exactly one of `response` or `ex` will be non-null. So
                    // when we reach this line, we know that response is not null.
                    val idToken = response!!.idToken
                    val decodedJWT = JWT.decode(idToken)

                    val keyId = decodedJWT.getHeaderClaim("kid").asString()
                    val key = jwks?.find { it.id == keyId }

                    if (key == null) {
                        loginRequestContinuation?.resumeWithException(Exception("Unknown key $keyId"))
                        return@performTokenRequest
                    }

                    try {
                        val algorithm = Algorithm.RSA256(key.publicKey as RSAPublicKey)
                        val verifier =
                            JWT.require(algorithm).withIssuer(Configuration.domain.toString())
                                .ignoreIssuedAt() // Do not throw on JWTs with iat "in the future". This can easily happen due to clock skew, see https://github.com/auth0/java-jwt/issues/467
                                .acceptNotBefore(5) // Add five seconds of leeway when validating nbf.
                                .build()

                        loginRequestContinuation?.resume(verifier.verify(idToken))
                    } catch (exception: JWTVerificationException) {
                        loginRequestContinuation?.resumeWithException(exception)
                    }
                }
            }

            is EndSessionResponse -> logoutRequestContinuation?.resume(Unit)
        }

        this.currentRequest = null
    }

    private fun handleException(ex: Exception) {
        if (currentRequest is AuthorizationRequest) {
            loginRequestContinuation?.resumeWithException(ex)
        } else if (currentRequest is EndSessionRequest) {
            logoutRequestContinuation?.resumeWithException(ex)
        }
    }

    private fun handleCustomTabResult(result: CustomTabResult) {
        Log.i(TAG, "Handling custom tab result $result")

        when (result) {
            is CustomTabResult.CustomTabFailure -> handleException(result.ex)
            is CustomTabResult.CustomTabSuccess -> handleResultUri(result.resultUri)
        }
    }

    private fun handleAuthTabResult(result: AuthResult) {
        Log.i(TAG, "Handling auth tab result. Code: ${result.resultCode}")

        when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> handleResultUri(result.resultUri!!)
            AuthTabIntent.RESULT_CANCELED -> handleException(Exception("RESULT_CANCELED"))
            AuthTabIntent.RESULT_UNKNOWN_CODE -> handleException(Exception("RESULT_UNKNOWN_CODE"))
            AuthTabIntent.RESULT_VERIFICATION_FAILED -> handleException(Exception("RESULT_VERIFICATION_FAILED"))
            AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> handleException(Exception("RESULT_VERIFICATION_TIMED_OUT"))
        }
    }

    suspend fun login(eid: eID) = suspendCoroutine { continuation ->
        loginRequestContinuation = continuation
        Log.i(TAG, "Starting login with $eid")

        val loginHints = listOf(
            "appswitch:android",
            "appswitch:resumeUrl:${Configuration.appSwitchUri}",
            "mobile:continue_button:never"
        )

        launchBrowser(
            AuthorizationRequest.Builder(
                serviceConfiguration,
                Configuration.CLIENT_ID,
                ResponseTypeValues.CODE,
                Configuration.redirectUri
            ).setScope("openid").setPrompt("login")
                .setAdditionalParameters(mapOf("acr_values" to eid.acr_value))
                .setLoginHint(loginHints.joinToString(" ")).build()
        )
    }

    suspend fun logout(idToken: String?) = suspendCoroutine { continuation ->
        logoutRequestContinuation = continuation

        launchBrowser(
            EndSessionRequest.Builder(serviceConfiguration).setIdTokenHint(idToken)
                .setPostLogoutRedirectUri(Configuration.redirectUri).build()
        )
    }

    private fun launchBrowser(request: AuthorizationManagementRequest) {
        this.currentRequest = request

        if (tabType == TabType.AuthTab) {
            // Open the Authorization URI in an Auth Tab if supported by chrome
            val authTabIntent = AuthTabIntent.Builder().build()
            // Auth tab will use the default browser, but we force it to use chrome.
            // In the future, other browser _could_ support the auth tab API (like they support custom tabs). But at the time of writing, only chrome supports it.
            authTabIntent.intent.`package` = Browsers.Chrome.PACKAGE_NAME
            authTabIntent.launch(
                authTabIntentLauncher,
                request.toUri(),
                Configuration.redirectUri.host!!,
                Configuration.redirectUri.path!!
            )
        } else {
            // Fall back to a Custom Tab.
            customTabIntentLauncher.launch(request)
        }
    }

    fun prepare(): Job = MainScope().launch {
        launch { fetchCriiptoOIDCConfiguration() }
        launch { fetchCriiptoJWKS() }
    }

    private suspend fun fetchCriiptoJWKS() = withContext(Dispatchers.IO) {
        jwks = UrlJwkProvider(Configuration.domain.toString()).getAll()
    }

    private suspend fun fetchCriiptoOIDCConfiguration() = withContext(Dispatchers.IO) {
        AuthorizationServiceConfiguration.fetchFromIssuer(
            Configuration.domain
        ) { _serviceConfiguration, ex ->
            if (ex != null) {
                Log.e(TAG, "Failed to fetch OIDC configuration", ex)
                return@fetchFromIssuer
            }
            if (_serviceConfiguration != null) {
                Log.d(TAG, "Fetched OIDC configuration")
                serviceConfiguration = _serviceConfiguration
            }
        }
    }
}

