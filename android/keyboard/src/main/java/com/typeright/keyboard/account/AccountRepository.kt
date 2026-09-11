package com.typeright.keyboard.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.typeright.keyboard.api.ApiResult
import com.typeright.keyboard.api.GrammarApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Caches `/v1/me` (PRO flag + AI quota) in the shared DataStore. Refreshed on keyboard start, after a rewarded ad
 * and after a purchase; grammar-check responses update the quota in between.
 */
class AccountRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: GrammarApiClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val account: Flow<AccountState> = dataStore.data.map { p ->
        val quota = p[REMAINING]?.let { remaining ->
            Quota(
                isPro = p[IS_PRO] ?: false,
                limit = p[LIMIT] ?: 0,
                used = p[USED] ?: 0,
                bonus = p[BONUS] ?: 0,
                remaining = remaining,
            )
        }
        AccountState(
            userId = p[USER_ID],
            isPro = p[IS_PRO] ?: false,
            quota = quota,
            fetchedAtMillis = p[FETCHED_AT] ?: 0L,
        )
    }.distinctUntilChanged()

    suspend fun current(): AccountState = account.first()

    /** Calls `/v1/me` and caches it. Returns false on failure (cache kept). */
    suspend fun refresh(): Boolean = when (val r = api.me()) {
        is ApiResult.Success -> {
            dataStore.edit { p ->
                p[USER_ID] = r.value.userId
                p[IS_PRO] = r.value.isPro
                p[FETCHED_AT] = nowMillis()
                r.value.quota?.let { writeQuota(p, it) }
            }
            true
        }
        is ApiResult.Failure -> false
    }

    /** Refreshes only if the cache is older than [maxAgeMillis]. */
    suspend fun refreshIfStale(maxAgeMillis: Long = STALE_AFTER_MS): Boolean {
        val age = nowMillis() - current().fetchedAtMillis
        return if (age >= maxAgeMillis) refresh() else true
    }

    suspend fun updateQuota(quota: Quota) {
        dataStore.edit { p ->
            writeQuota(p, quota)
            p[IS_PRO] = quota.isPro
        }
    }

    /** Drops the cached account (sign-out / account deletion). */
    suspend fun clear() {
        dataStore.edit { p ->
            p.remove(USER_ID)
            p.remove(IS_PRO)
            p.remove(FETCHED_AT)
            p.remove(LIMIT)
            p.remove(USED)
            p.remove(BONUS)
            p.remove(REMAINING)
        }
    }

    private fun writeQuota(p: androidx.datastore.preferences.core.MutablePreferences, q: Quota) {
        p[LIMIT] = q.limit
        p[USED] = q.used
        p[BONUS] = q.bonus
        p[REMAINING] = q.remaining
    }

    private companion object {
        const val STALE_AFTER_MS = 5 * 60_000L
        val USER_ID = stringPreferencesKey("account_user_id")
        val IS_PRO = booleanPreferencesKey("account_is_pro")
        val FETCHED_AT = longPreferencesKey("account_fetched_at")
        val LIMIT = intPreferencesKey("quota_limit")
        val USED = intPreferencesKey("quota_used")
        val BONUS = intPreferencesKey("quota_bonus")
        val REMAINING = intPreferencesKey("quota_remaining")
    }
}
