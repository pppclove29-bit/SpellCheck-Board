package com.typeright.keyboard.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ShortcutRulesTest {
    private val shortcuts = listOf(Shortcut("ㅈㅅ", "죄송합니다"), Shortcut("ㄱㅅ", "감사합니다"))

    @Test
    fun lastTokenBeforeCursor() {
        assertEquals("ㅈㅅ", ShortcutRules.lastToken("안녕 ㅈㅅ"))
        assertEquals("ㅈㅅ", ShortcutRules.lastToken("ㅈㅅ"))
        assertEquals("", ShortcutRules.lastToken("안녕 "))
        assertEquals("", ShortcutRules.lastToken(""))
        assertEquals("b", ShortcutRules.lastToken("a\nb"))
    }

    @Test
    fun matchesOnlyWholeLastToken() {
        assertEquals("죄송합니다", ShortcutRules.match("늦어서 ㅈㅅ", shortcuts)?.expansion)
        assertNull(ShortcutRules.match("늦어서ㅈㅅ", shortcuts))
        assertNull(ShortcutRules.match("ㅈㅅ ", shortcuts))
    }

    @Test
    fun validation() {
        assertNull(ShortcutRules.validate("ㅂㅂ", "바이바이", shortcuts))
        assertNotNull(ShortcutRules.validate("", "x", shortcuts))
        assertNotNull(ShortcutRules.validate("a b", "x", shortcuts))
        assertNotNull(ShortcutRules.validate("ㅈㅅ", "x", shortcuts)) // duplicate
        assertNull(ShortcutRules.validate("ㅈㅅ", "죄송해요", shortcuts, editingKey = "ㅈㅅ"))
        assertNotNull(ShortcutRules.validate("k".repeat(21), "x", shortcuts))
        assertNotNull(ShortcutRules.validate("k", "x".repeat(501), shortcuts))
        assertNotNull(ShortcutRules.validate("k", " ", shortcuts))
    }

    @Test
    fun codecRoundTrip() {
        assertEquals(shortcuts, ShortcutCodec.decode(ShortcutCodec.encode(shortcuts)))
        assertNull(ShortcutCodec.decode(null))
        assertNull(ShortcutCodec.decode("not json"))
        assertEquals(emptyList<Shortcut>(), ShortcutCodec.decode("[]"))
    }
}

class ShortcutSyncTest {
    private val requests = ShortcutSyncRequests("https://proj.supabase.co/", "anon-key")

    @Test
    fun listRequest() {
        val r = requests.list("tok")
        assertEquals("GET", r.method)
        assertEquals("https://proj.supabase.co/rest/v1/shortcuts?select=*", r.url)
        assertEquals("anon-key", r.headers["apikey"])
        assertEquals("Bearer tok", r.headers["Authorization"])
        assertNull(r.body)
    }

    @Test
    fun upsertRequest() {
        val r = requests.upsert("tok", "user-1", listOf(Shortcut("ㅈㅅ", "죄송합니다")), Instant.parse("2026-09-11T00:00:00Z"))
        assertEquals("POST", r.method)
        assertEquals("https://proj.supabase.co/rest/v1/shortcuts?on_conflict=user_id,shortcut_key", r.url)
        assertTrue(r.headers.getValue("Prefer").contains("resolution=merge-duplicates"))
        assertEquals("Bearer tok", r.headers["Authorization"])
        assertEquals("anon-key", r.headers["apikey"])
        val row = Json.parseToJsonElement(r.body!!).jsonArray.single().jsonObject
        assertEquals("user-1", row.getValue("user_id").jsonPrimitive.content)
        assertEquals("ㅈㅅ", row.getValue("shortcut_key").jsonPrimitive.content)
        assertEquals("죄송합니다", row.getValue("expansion").jsonPrimitive.content)
        assertEquals("2026-09-11T00:00:00Z", row.getValue("updated_at").jsonPrimitive.content)
    }

    @Test
    fun deleteRequest() {
        val r = requests.delete("tok", "6f1c2d3e-0000-4000-8000-000000000001")
        assertEquals("DELETE", r.method)
        assertEquals("https://proj.supabase.co/rest/v1/shortcuts?id=eq.6f1c2d3e-0000-4000-8000-000000000001", r.url)
        assertEquals("Bearer tok", r.headers["Authorization"])
    }

    @Test
    fun parseList() {
        val body = """[{"id":"1","user_id":"u","shortcut_key":"ㅈㅅ","expansion":"죄송합니다","updated_at":"2026-09-11T00:00:00+00:00"},
            {"id":"2","user_id":"u"}]"""
        assertEquals(listOf(RemoteShortcut("1", "ㅈㅅ", "죄송합니다")), ShortcutSyncRequests.parseList(body))
    }

    @Test
    fun planPushesLocalPullsRemoteAndDeletesTombstones() {
        val local = listOf(Shortcut("a", "1"), Shortcut("b", "2"), Shortcut("e", "local"))
        val remote = listOf(
            RemoteShortcut("id-a", "a", "1"), // same → nothing
            RemoteShortcut("id-c", "c", "3"), // remote-only → pull
            RemoteShortcut("id-d", "d", "4"), // deleted locally → delete
            RemoteShortcut("id-e", "e", "remote"), // differs → local wins
        )
        val plan = ShortcutSyncPlanner.plan(local, remote, tombstones = setOf("d"))
        assertEquals(listOf(Shortcut("b", "2"), Shortcut("e", "local")), plan.upserts)
        assertEquals(listOf("id-d"), plan.deleteIds)
        assertEquals(listOf(Shortcut("c", "3")), plan.pulled)
    }

    @Test
    fun tombstonedKeyReAddedLocallyIsNotDeleted() {
        val plan = ShortcutSyncPlanner.plan(
            local = listOf(Shortcut("a", "new")),
            remote = listOf(RemoteShortcut("id-a", "a", "old")),
            tombstones = setOf("a"),
        )
        assertEquals(emptyList<String>(), plan.deleteIds)
        assertEquals(listOf(Shortcut("a", "new")), plan.upserts)
    }
}
