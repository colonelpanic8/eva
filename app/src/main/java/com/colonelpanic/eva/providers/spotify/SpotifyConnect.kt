package com.colonelpanic.eva.providers.spotify

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

sealed interface SpotifyConnectState {
    data object Idle : SpotifyConnectState

    data class Waiting(
        val authorizationUrl: String,
    ) : SpotifyConnectState

    data class Failed(
        val message: String,
    ) : SpotifyConnectState
}

class SpotifyConnect(
    private val login: SpotifyLoginClient,
    private val authorizationUrl: (String, String, String) -> String,
    private val save: (SpotifyTokens, SpotifyProfile) -> Unit,
    private val verifier: () -> String = ::spotifyCodeVerifier,
    private val stateValue: () -> String = ::spotifyState,
) {
    constructor(
        login: SpotifyLogin = SpotifyLogin(),
        save: (SpotifyTokens, SpotifyProfile) -> Unit,
    ) : this(login, login::authorizationUrl, save)

    private data class Pending(
        val clientId: String,
        val verifier: String,
        val state: String,
    )

    private val mutable = MutableStateFlow<SpotifyConnectState>(SpotifyConnectState.Idle)
    private var pending: Pending? = null
    val state: StateFlow<SpotifyConnectState> = mutable.asStateFlow()

    fun begin(clientId: String): String {
        val trimmed = clientId.trim()
        require(trimmed.isNotEmpty()) { "Save a Spotify Client ID first." }
        val verifier = verifier()
        val state = stateValue()
        val url = authorizationUrl(trimmed, spotifyCodeChallenge(verifier), state)
        pending = Pending(trimmed, verifier, state)
        mutable.value = SpotifyConnectState.Waiting(url)
        return url
    }

    suspend fun complete(redirect: Uri) {
        val expected = pending
        if (expected == null || redirect.getQueryParameter("state") != expected.state) {
            pending = null
            mutable.value = SpotifyConnectState.Failed(MISMATCHED_STATE)
            return
        }
        pending = null
        redirect.getQueryParameter("error")?.let { failure ->
            mutable.value =
                SpotifyConnectState.Failed(
                    redirect.getQueryParameter("error_description")?.takeIf(String::isNotBlank)
                        ?: "Spotify sign-in failed: $failure",
                )
            return
        }
        val code = redirect.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            mutable.value = SpotifyConnectState.Failed("Spotify returned no sign-in code. Try again.")
            return
        }
        try {
            val tokens = login.exchange(expected.clientId, code, expected.verifier)
            val profile = login.profile(tokens.accessToken)
            save(tokens, profile)
            mutable.value = SpotifyConnectState.Idle
        } catch (error: IllegalStateException) {
            mutable.value = SpotifyConnectState.Failed(error.message ?: "Spotify sign-in failed.")
        } catch (_: IOException) {
            mutable.value = SpotifyConnectState.Failed("Spotify sign-in could not reach the network.")
        }
    }

    fun cancel() {
        pending = null
        mutable.value = SpotifyConnectState.Idle
    }

    companion object {
        const val MISMATCHED_STATE = "That Spotify sign-in did not match the one EVA started. Try again."
    }
}
