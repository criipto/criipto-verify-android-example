# criipto-verify-android-example

This project shows how to integrate Criipto Verify login into your Android app. Specifically, the application acts as a [_public client_](https://docs.criipto.com/verify/getting-started/glossary/#public-clients), meaning it does not use a client secret, but instead employs [PKCE](https://docs.criipto.com/verify/getting-started/glossary/#pkce-proof-key-for-code-exchange) to ensure that a malicious actor cannot intercept the authorization code.

This project is built using Android Studio, Kotlin, and Jetpack Compose. It builds on top of the [AppAuth library](https://github.com/openid/AppAuth-android), which is maintained by the OpenID foundation. It has been tested on Android versions 10 through 16, using Chrome (both Auth Tab and Custom Tab), Samsung Internet, Brave, and Microsoft Edge.

In addition to the basic OIDC flow, this project also shows how to implement [app switching](https://docs.criipto.com/verify/guides/appswitch/) for the Danish MitID app.

## Code structure

- `LoginManager` encapsulates the majority of the OIDC logic. This class is responsible for interacting with the `AppAuth` library.
- `Configuration` contains static configuration values.
- `AndroidManifest.xml` sets up App Link handlers for the OIDC redirect URL (only required when using custom tabs), and for app switching.

## Configuration

This assumes that you will be using your Criipto domain to host your [redirect URL](https://docs.criipto.com/verify/getting-started/glossary/#redirect-uri-callback-url). If you wish to use a separate domain, see the Google documentation on [App Links](https://developer.android.com/training/app-links/about) for how to set it up.

- Configure a new application at https://dashboard.criipto.com/. You should use a separate application for each platform you intend to deploy on.
  - In the OpenID Connect section, ensure that 'Require PKCE' is checked.
  - Add `https://[YOUR CRIIPTO DOMAIN]/android/callback` as a callback URL.
  - Add `https://[YOUR CRIIPTO DOMAIN]/android/appswitch` as a callback URL, if you need to support app switching as well.
  - In the Native/Mobile section, add your package name and the SHA256 fingerprint of your signing certificate.
    - If you are using a local keystore to sign your debug build, run `./gradlew signingReport` to get the fingerprint.
    - Otherwise, follow the [Android documentation](https://developer.android.com/studio/write/app-link-indexing#associatesite).
- Update the `Configuration.kt` file with your client ID and domain.
- Update the `android:host` sections in the `AndroidManifest.xml` file with your redirect URL.

### User agent

You will want to display the login screen to the user in some way, using what [OAuth calls](https://datatracker.ietf.org/doc/html/rfc8252#section-3) an external user-agent, i.e. some form of web browser. There are multiple options, listed in preferred order:

1. [Chrome Auth Tab](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab) is a stripped down browser tab, tailored towards authentication. You specify the redirect URL when opening the tab, and once the browser is redirected to this URL, a callback is invoked, allowing you to complete the OIDC login. However, it is only supported on devices running Chrome 132 and up (released January 2025).
2. [Chrome Custom Tabs](https://developer.android.com/develop/ui/views/layout/webapps/overview-of-android-custom-tabs) (including Samsung Internet, Brave, and Microsoft Edge, all of which are based on Chromium) has previously been the preferred way to handle authorization on Android. It works by using [App Links](https://developer.android.com/training/app-links/about), to return control to your application once the browser is redirected to the redirect URL. There are some minor quirks around how browsers handle App Links, that can make them hard to work with. Namely, Custom Tabs require an unspecified amount of "user interaction" (i.e. pressing on things in the tab), before an App Link is triggered. However, these quirks can be worked around by warming up the browser before it is used, something which the `AppAuth` library will handle for you.
3. Other Custom Tab browsers (for example Firefox and Opera Mobile) _can_ also be used, but they have some bigger quirks. Most importantly, your redirect URL will not always redirect to your app automatically. The user can still open the menu on the right side and press 'Open in app', but the UX of that is significantly worse.

   <img src="https://github.com/user-attachments/assets/06aefdbc-1053-4600-a457-f56334485d1f" />

   This problem can be alleviated in Firefox by using two different Criipto domains, one for starting the OIDC flow, and a different one for hosting the redirect URL.

Which user-agents to support depends on the requirements of your application.

The easiest by far is if your app can exclusively work with Auth Tabs, since you don't have to set up App Links for it to work (except for app switching). [According to Cloudflare](https://radar.cloudflare.com/reports/browser-market-share-2025-q2#id-8-market-share-by-os), 85% of Android devices are using Chrome as their default browser. On top of that, even devices that don't have Chrome set as their default browser may still have it installed, meaning you can use it to open Auth Tabs.

If you need to support a broader range of browsers, supporting Samsung Internet, Brave, and Microsoft Edge should bring you close to 95% of all devices. `LoginManager.onCreate` shows how to detect installed browsers, and how to configure `AppAuth` to use either Chrome or Samsung Internet if they are installed.

Both Auth Tabs and Custom Tabs are launched from your application using the [`ActivityResultLancher` API](https://developer.android.com/training/basics/intents/result), which lets you start an intent, and register a callback for its completion. This works out of the box for Auth Tabs, but for Custom Tabs there is some processing happening behind the scenes. This is all handled by the `AppAuth` library, which starts a new activity, opens the Custom Tab, captures the response via an App Link, and sets the response on the original intent.

### App switching

[App switching](https://docs.criipto.com/verify/guides/appswitch/) refers to the process of switching from your app to the verification app of the eID provider, and switching back to your app, after the user has completed the verification process.

Currently, this is only supported for the Danish MitID app.

The initial switch to the MitID app is outside of your control. The MitID Core Client will present a button to the user, allowing them to switch to the MitID app, regardless of how you initiate the login. In order for the MitID app to switch back to your app, you must set the `appswitch:android` and `appswitch:resumeUrl` login hints. See `LoginManager.kt` for details. Additionally, you must register an [App Link](https://developer.android.com/training/app-links/about).

Once the user approves the login in the MitID app, the MitID app will open the app link you specified as `resumeUrl`. This link _does not_ contain any OIDC parameters, it only serves to return control to your app. You need an activity in order to receive the link, but you do not need to do anything with it. Notice how `AppSwitchActivity.kt` simply calls `finish()`, as soon as it is resumed. Once control returns to your app, the authorization flow continues in the browser, which is redirected to the redirect URL.

---

The next section explains the reasoning behind choosing HTTPS redirect URLs, and the alternatives considered.

> [!IMPORTANT]
> TL;DR? - This project uses an HTTPS redirect URL with Android App Links. Unless you have an excellent reason not to, you should too!

### Redirect URL

The redirect URL is the location where the user's browser is redirected after authentication is complete. This can either be an HTTPS URL or a URL using a custom scheme. According to the [OAuth 2.0 for Native Apps](https://datatracker.ietf.org/doc/html/rfc8252) best practices document:

> App-claimed "https" scheme redirect URIs have some advantages compared to other native app redirect options in that the identity of the destination app is guaranteed to the authorization server by the operating system. For this reason, native apps SHOULD use them over the other options where possible.

Using an HTTPS redirect URI prevents a malicious actor from impersonating your app, by using your client ID and redirect URI to start a login flow from their own app. This is because HTTPS URIs must be claimed before they can be used.
