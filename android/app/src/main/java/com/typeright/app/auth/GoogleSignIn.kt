package com.typeright.app.auth

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.typeright.app.BuildConfig
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.auth.Nonce
import com.typeright.keyboard.auth.SignInResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface GoogleSignInOutcome {
    data class SignedIn(val email: String?) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Failed(val message: String) : GoogleSignInOutcome
}

/**
 * Google Sign-In (Credential Manager) → Supabase `grant_type=id_token` exchange. Needs an Activity, so it lives in
 * the host app; the IME only reads the shared session.
 *
 * Nonce: a random raw nonce is generated; Google gets its SHA-256 hex digest, Supabase gets the raw value.
 */
object GoogleSignIn {
    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    suspend fun signIn(activity: Activity, services: TypeRightServices): GoogleSignInOutcome {
        if (services.isDevAuth) {
            return GoogleSignInOutcome.Failed("개발 모드(SUPABASE_URL 미설정)에서는 로그인 없이 X-Dev-User-Id로 동작해요.")
        }
        if (!isConfigured) return GoogleSignInOutcome.Failed("Google 로그인 설정(GOOGLE_WEB_CLIENT_ID)이 없어요.")

        val rawNonce = Nonce.generateRaw()
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(Nonce.sha256Hex(rawNonce))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            return GoogleSignInOutcome.Cancelled
        } catch (e: GetCredentialException) {
            return GoogleSignInOutcome.Failed("Google 로그인에 실패했어요. (${e.type})")
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleSignInOutcome.Failed("지원하지 않는 로그인 정보예요.")
        }
        val idToken = try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GoogleIdTokenParsingException) {
            return GoogleSignInOutcome.Failed("Google 로그인 정보를 읽지 못했어요.")
        }

        return when (val r = services.auth.signInWithGoogle(idToken, rawNonce)) {
            is SignInResult.Success -> GoogleSignInOutcome.SignedIn(r.session.email)
            is SignInResult.Failure -> GoogleSignInOutcome.Failed(
                when (r.reason) {
                    SignInResult.Failure.Reason.NETWORK -> "네트워크 오류로 로그인하지 못했어요."
                    SignInResult.Failure.Reason.REJECTED -> "로그인이 거부됐어요. 잠시 후 다시 시도해 주세요."
                    SignInResult.Failure.Reason.NOT_CONFIGURED -> "로그인 서버 설정이 없어요."
                },
            )
        }
    }
}

/** UI state holder for a "Google로 로그인" button. */
class GoogleSignInState internal constructor(
    private val scope: CoroutineScope,
    private val services: TypeRightServices,
    private val activity: Activity?,
) {
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun signIn() {
        val act = activity ?: return
        if (busy) return
        busy = true
        scope.launch {
            message = when (val r = GoogleSignIn.signIn(act, services)) {
                is GoogleSignInOutcome.SignedIn -> {
                    // Cache PRO/quota and pull synced shortcuts for the new user.
                    services.account.refresh()
                    services.shortcutSync.sync(services.account.current().isPro)
                    "로그인했어요${r.email?.let { ": $it" } ?: ""}"
                }
                GoogleSignInOutcome.Cancelled -> null
                is GoogleSignInOutcome.Failed -> r.message
            }
            busy = false
        }
    }
}

@Composable
fun rememberGoogleSignIn(services: TypeRightServices): GoogleSignInState {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    return remember(activity) { GoogleSignInState(scope, services, activity) }
}
