package com.typeright.keyboard.settings

import com.typeright.keyboard.api.HttpRequest
import com.typeright.keyboard.api.HttpTransport
import com.typeright.keyboard.auth.AuthSession
import com.typeright.keyboard.auth.AuthSessionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
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
    /** Local entries missing or different remotely (local is the source of truth). */
    val upserts: List<Shortcut>,
    /** Remote rows deleted locally (tombstoned and not re-added). */
    val deleteIds: List<String>,
    /** Remote-only entries to add locally (e.g. after reinstall / on a new device). */
    val pulled: List<Shortcut>,
)

object ShortcutSyncPlanner {
    fun plan(local: List<Shortcut>, remote: List<RemoteShortcut>, tombstones: Set<String>): ShortcutSyncPlan {
        val remoteByKey = remote.associateBy { it.key }
        val localKeys = local.mapTo(HashSet()) { it.key }
        return ShortcutSyncPlan(
            upserts = local.filter { remoteByKey[it.key]?.expansion != it.expansion },
            deleteIds = remote.filter { it.key in tombstones && it.key !in localKeys }.map { it.id },
            pulled = remote.filter { it.key !in localKeys && it.key !in tombstones }.map { Shortcut(it.key, it.expansion) },
        )
    }
}

/**
 * Syncs custom shortcuts with Supabase for PRO users. The local DataStore stays the source of truth for the keyboard
 * (works offline); sync runs on host-app open and after edits, only when PRO and SUPABASE_URL is configured.
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

    enum class Result { SYNCED, SKIPPED, FAILED }

    suspend fun sync(isPro: Boolean): Result {
        if (!configured || !isPro) return Result.SKIPPED
        val session: AuthSession = auth.validSession(forceRefresh = false) ?: return Result.FAILED
        return try {
            val listResponse = execute(requests.list(session.accessToken))
            if (listResponse.code !in 200..299) return Result.FAILED
            val remote = ShortcutSyncRequests.parseList(listResponse.body)
            val local = settings.currentShortcuts()
            val tombstones = settings.shortcutTombstones()
            val plan = ShortcutSyncPlanner.plan(local, remote, tombstones)

            if (plan.upserts.isNotEmpty()) {
                val r = execute(requests.upsert(session.accessToken, session.userId, plan.upserts, now()))
                if (r.code !in 200..299) return Result.FAILED
            }
            for (id in plan.deleteIds) {
                val r = execute(requests.delete(session.accessToken, id))
                if (r.code !in 200..299) return Result.FAILED
            }
            if (plan.pulled.isNotEmpty()) settings.addShortcutsIfAbsent(plan.pulled)
            // Every tombstone is now gone remotely (deleted above or never uploaded).
            settings.clearShortcutTombstones(tombstones)
            Result.SYNCED
        } catch (e: IOException) {
            Result.FAILED
        } catch (e: RuntimeException) {
            Result.FAILED
        }
    }

    private suspend fun execute(request: HttpRequest) = runInterruptible(ioDispatcher) { transport.execute(request) }
}
