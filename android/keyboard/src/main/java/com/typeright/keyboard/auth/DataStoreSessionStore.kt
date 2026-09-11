package com.typeright.keyboard.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Session persisted in the shared preferences DataStore, so the IME and the host app use one Supabase user. */
internal class DataStoreSessionStore(private val dataStore: DataStore<Preferences>) : SessionStore {

    override val sessions: Flow<AuthSession?> = dataStore.data.map(::read)

    override suspend fun load(): AuthSession? = read(dataStore.data.first())

    private fun read(p: Preferences): AuthSession? {
        val access = p[ACCESS] ?: return null
        val refresh = p[REFRESH] ?: return null
        val userId = p[USER_ID] ?: return null
        return AuthSession(access, refresh, p[EXPIRES_AT] ?: 0L, userId, p[EMAIL])
    }

    override suspend fun save(session: AuthSession?) {
        dataStore.edit { p ->
            if (session == null) {
                p.remove(ACCESS)
                p.remove(REFRESH)
                p.remove(EXPIRES_AT)
                p.remove(USER_ID)
                p.remove(EMAIL)
            } else {
                p[ACCESS] = session.accessToken
                p[REFRESH] = session.refreshToken
                p[EXPIRES_AT] = session.expiresAtEpochSec
                p[USER_ID] = session.userId
                if (session.email != null) p[EMAIL] = session.email else p.remove(EMAIL)
            }
        }
    }

    override suspend fun devUserId(): String {
        dataStore.data.first()[DEV_USER_ID]?.let { return it }
        var id = ""
        dataStore.edit { p ->
            id = p[DEV_USER_ID] ?: "dev-${UUID.randomUUID()}".also { p[DEV_USER_ID] = it }
        }
        return id
    }

    private companion object {
        val ACCESS = stringPreferencesKey("auth_access_token")
        val REFRESH = stringPreferencesKey("auth_refresh_token")
        val EXPIRES_AT = longPreferencesKey("auth_expires_at")
        val USER_ID = stringPreferencesKey("auth_user_id")
        val EMAIL = stringPreferencesKey("auth_email")
        val DEV_USER_ID = stringPreferencesKey("auth_dev_user_id")
    }
}
