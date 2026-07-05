package com.terminus.edge.light.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.openid.appauth.*

class HuggingFaceAuthManager(private val context: Context) {
    private val authService = AuthorizationService(context)

    companion object {
        const val CLIENT_ID = "YOUR_CLIENT_ID_HERE" // HuggingFace OAuth App Client ID
        const val REDIRECT_URI = "edge-lite://oauth2callback"
        const val AUTHORIZATION_ENDPOINT = "https://huggingface.co/oauth/authorize"
        const val TOKEN_ENDPOINT = "https://huggingface.co/oauth/token"
    }

    fun buildAuthIntent(): Intent {
        val serviceConfiguration = AuthorizationServiceConfiguration(
            Uri.parse(AUTHORIZATION_ENDPOINT),
            Uri.parse(TOKEN_ENDPOINT)
        )

        val authRequestBuilder = AuthorizationRequest.Builder(
            serviceConfiguration,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI)
        ).setScope("read")

        val authRequest = authRequestBuilder.build()
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    fun handleAuthResponse(intent: Intent, onTokenResult: (String?, Throwable?) -> Unit) {
        val resp = AuthorizationResponse.fromIntent(intent)
        val ex = AuthorizationException.fromIntent(intent)

        if (resp != null) {
            val tokenRequest = resp.createTokenExchangeRequest()
            authService.performTokenRequest(tokenRequest) { response, exception ->
                if (response != null) {
                    onTokenResult(response.accessToken, null)
                } else {
                    onTokenResult(null, exception)
                }
            }
        } else {
            onTokenResult(null, ex ?: java.lang.Exception("Unknown authorization error"))
        }
    }
    
    fun dispose() {
        authService.dispose()
    }
}
