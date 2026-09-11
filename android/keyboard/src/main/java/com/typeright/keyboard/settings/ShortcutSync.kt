package com.typeright.keyboard.settings

import com.typeright.keyboard.api.HttpRequest
import com.typeright.keyboard.api.HttpTransport
import com.typeright.keyboard.auth.AuthSessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

/** Row of `public.shortcuts` (Supabase PostgREST). */
data class RemoteShortcut(val id: String, val key: String, val expansion: String)

/**
 * Pure request builders for the Supabase PostgREST `shortcuts` table (RLS: owner reads/deletes; PRO inserts/updates).
 */
class ShortcutSyncRequests(supabaseUrl: String, private val anonKey: String) {
    private val base = supabaseUrl.trim().trimEnd('/') + "/rest/v1/shortcuts"

    private fun headers(accessToken: String): Map<String, String> = mapOf(
        "apikey" to anonKey,
        "Authorization" to "Bearer $accessToken",
    )

    fun list(accessToken: String): HttpRequest =
        HttpRequest(method = "GET", url = "$base?select=*", headers = headers(accessToken))

    fun upsert(accessToken: String, userId: String, shortcuts: List<Shortcut>, updatedAt: Instant): HttpRequest {
        val body = JsonArray(
            shortcuts.map { s ->
                buildJsonObject {
                    put("user_id", JsonPrimitive(userId))
                    put("shortcut_key", JsonPrimitive(s.key))
                    put("expansion", JsonPrimitive(s.expansion))
                    put("updated_at", JsonPrimitive(updatedAt.toString()))
                }
            },
        ).toString()
        return HttpRequest(
            method = "POST",
            url = "$base?on_conflict=user_id,shortcut_key",
            headers = headers(accessToken) + ("Prefer" to "resolution=merge-duplicates,return=minimal"),
            body = body,
        )
    }

    fun delete(accessToken: String, id: String): HttpRequest = HttpRequest(
        method = "DELETE",
        url = "$base?id=eq.${URLEncoder.encode(id, "UTF-8")}",
        headers = headers(accessToken),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(body: String): List<RemoteShortcut> = json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = str("id") ?: return@mapNotNull null
            val key = str("shortcut_key") ?: return@mapNotNull null
            val expansion = str("expansion") ?: return@mapNotNull null
            RemoteShortcut(id, key, expansion)
        }
    }
}

data class ShortcutSyncPlan(
    /** Local entries missing or different remotely (local is the source of truth). Empty unless PRO. */
    val upserts: List<Shortcut>,
    /** Remote rows deleted locally (tombstoned and not re-added). Owners may always delete. */
    val deleteIds: List<String>,
    /** Remote-only entries to add locally (reinstall, new device, re-login after PRO expired). */
    val pulled: List<Shortcut>,
)

object ShortcutSyncPlanner {
    /** @param canPush insert/update is allowed only for PRO (RLS); pull and delete are allowed for any owner. */
    fun plan(
        local: List<Shortcut>,
        remote: List<RemoteShortcut>,
        tombstones: Set<String>,
        canPush: Boolean,
    ): ShortcutSyncPlan {
        val remoteByKey = remote.associateBy { it.key }
        val localKeys = local.mapTo(HashSet()) { it.key }
        return ShortcutSyncPlan(
            upserts = if (canPush) local.filter { remoteByKey[it.key]?.expansion != it.expansion } else emptyList(),
            deleteIds = remote.filter { it.key in tombstones && it.key !in localKeys }.map { it.id },
            pulled = remote.filter { it.key !in localKeys && it.key !in tombstones }.map { Shortcut(it.key, it.expansion) },
        )
    }
}

/**
 * Syncs custom shortcuts with Supabase. The local DataStore stays the source of truth for the keyboard (works
 * offline). Runs on host-app open, after sign-in, after edits and before logout; only when SUPABASE_URL is configured
 * and the user is signed in. PULL + DELETE for any signed-in user, PUSH (insert/update) only when PRO.
 */
class ShortcutSyncRepository(
    supabaseUrl: String,
    anonKey: String,
    private val auth: AuthSessionManager,
    private val settings: SettingsRepository,
    private val transport: HttpTransport,
    private val now: () -> Instant = Instant::now,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val configured = supabaseUrl.isNotBlank()
    private val requests = ShortcutSyncRequests(supabaseUrl, anonKey)
    private val mutex = Mutex()

    data class Outcome(val status: Status, val pulled: Int = 0) {
        enum class Status { SYNCED, SKIPPED, FAILED }
    }

    suspend fun sync(isPro: Boolean): Outcome {
        if (!configured || auth.userId() == null) return Outcome(Outcome.Status.SKIPPED)
        return mutex.withLock {
            val session = auth.validSession(forceRefresh = false) ?: return@withLock Outcome(Outcome.Status.FAILED)
            try {
                val listResponse = execute(requests.list(session.accessToken))
                if (listResponse.code !in 200..299) return@withLock Outcome(Outcome.Status.FAILED)
                val remote = ShortcutSyncRequests.parseList(listResponse.body)
                val tombstones = settings.shortcutTombstones()
                val plan = ShortcutSyncPlanner.plan(settings.currentShortcuts(), remote, tombstones, canPush = isPro)

                if (plan.upserts.isNotEmpty()) {
                    val r = execute(requests.upsert(session.accessToken, session.userId, plan.upserts, now()))
                    if (r.code !in 200..299) return@withLock Outcome(Outcome.Status.FAILED)
                }
                for (id in plan.deleteIds) {
                    val r = execute(requests.delete(session.accessToken, id))
                    if (r.code !in 200..299) return@withLock Outcome(Outcome.Status.FAILED)
                }
                val pulled = if (plan.pulled.isNotEmpty()) settings.addShortcutsIfAbsent(plan.pulled) else 0
                // Every tombstone is now gone remotely (deleted above or never uploaded).
                settings.clearShortcutTombstones(tombstones)
                Outcome(Outcome.Status.SYNCED, pulled)
            } catch (e: IOException) {
                Outcome(Outcome.Status.FAILED)
            } catch (e: RuntimeException) {
                Outcome(Outcome.Status.FAILED)
            }
        }
    }

    private suspend fun execute(request: HttpRequest) = runInterruptible(ioDispatcher) { transport.execute(request) }
}
