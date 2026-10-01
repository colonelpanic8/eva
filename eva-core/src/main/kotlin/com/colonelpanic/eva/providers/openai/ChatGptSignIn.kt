package com.colonelpanic.eva.providers.openai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the sign-in screen shows while a device code is outstanding. */
sealed interface SignInState {
    data object Idle : SignInState

    data object Requesting : SignInState

    data class Waiting(
        val userCode: String,
        val verificationUrl: String,
    ) : SignInState

    data class Failed(
        val message: String,
    ) : SignInState
}

/**
 * One device-code sign-in: ask for a code, show it while the person approves it elsewhere,
 * and hand over the tokens. Cancelling is the caller's job; it simply stops collecting.
 */
class ChatGptSignIn(
    private val login: ChatGptLogin = ChatGptLogin(),
    private val save: (ChatGptTokens) -> Unit,
) {
    private val mutable = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = mutable.asStateFlow()

    /** Returns true once tokens are saved, so the caller can refresh what the account offers. */
    suspend fun run(): Boolean {
        mutable.value = SignInState.Requesting
        val code =
            try {
                login.requestCode()
            } catch (error: IllegalStateException) {
                mutable.value = SignInState.Failed(error.message ?: "ChatGPT sign-in failed.")
                return false
            } catch (error: java.io.IOException) {
                mutable.value = SignInState.Failed("ChatGPT sign-in could not reach the network.")
                return false
            }
        mutable.value = SignInState.Waiting(code.userCode, code.verificationUrl)
        return try {
            save(login.awaitApproval(code))
            mutable.value = SignInState.Idle
            true
        } catch (error: IllegalStateException) {
            mutable.value = SignInState.Failed(error.message ?: "ChatGPT sign-in failed.")
            false
        } catch (error: java.io.IOException) {
            mutable.value = SignInState.Failed("ChatGPT sign-in could not reach the network.")
            false
        }
    }

    fun reset() {
        mutable.value = SignInState.Idle
    }
}
