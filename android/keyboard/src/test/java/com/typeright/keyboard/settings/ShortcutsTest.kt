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
    private val custom = listOf(Shortcut("ㅂㅂ", "바이바이"), Shortcut("ㅎㅇ", "안녕하세요"))

    @Test
    fun builtInsAreTheThreeDefaults() {
        assertEquals(
            listOf(Shortcut("ㅈㅅ", "죄송합니다"), Shortcut("ㄱㅅ", "감사합니다"), Shortcut("ㅇㅋ", "알겠습니다")),
            ShortcutRules.BUILT_IN,
        )
        val settings = TypeRightSettings(shortcuts = custom)
        assertEquals(ShortcutRules.BUILT_IN + custom, settings.allShortcuts)
        assertEquals(ShortcutRules.BUILT_IN, TypeRightSettings().allShortcuts)
    }

    @Test
    fun lastTokenBeforeCursor() {
        assertEquals("ㅈㅅ", ShortcutRules.lastToken("안녕 ㅈㅅ"))
        assertEquals("ㅈㅅ", ShortcutRules.lastToken("ㅈㅅ"))
        assertEquals("", ShortcutRules.lastToken("안녕 "))
        assertEquals("", ShortcutRules.lastToken(""))
        assertEquals("b", ShortcutRules.lastToken("a\nb"))
    }

    @Test
    fun matchesBuiltInsAndCustomsOnWholeLastToken() {
        val all = TypeRightSettings(shortcuts = custom).allShortcuts
        assertEquals("죄송합니다", ShortcutRules.match("늦어서 ㅈㅅ", all)?.expansion)
        assertEquals("바이바이", ShortcutRules.match("그럼 ㅂㅂ", all)?.expansion)
        assertNull(ShortcutRules.match("늦어서ㅈㅅ", all))
        assertNull(ShortcutRules.match("ㅈㅅ ", all))
    }

    @Test
    fun validation() {
        assertNull(ShortcutRules.validate("ㅋㅋ", "ㅋㅋㅋ 웃겨", custom))
        assertNotNull(ShortcutRules.validate("", "x", custom))
        assertNotNull(ShortcutRules.validate("a b", "x", custom))
        assertNotNull(ShortcutRules.validate("ㅂㅂ", "x", custom)) // duplicate
        assertNull(ShortcutRules.validate("ㅂㅂ", "잘 가", custom, editingKey = "ㅂㅂ"))
        assertNotNull(ShortcutRules.validate("ㅈㅅ", "x", custom)) // built-ins are read-only
        assertNotNull(ShortcutRules.validate("k".repeat(21), "x", custom))
        assertNotNull(ShortcutRules.validate("k", "x".repeat(501), custom))
        assertNotNull(ShortcutRules.validate("k", " ", custom))
    }

    @Test
    fun codecRoundTrip() {
        assertEquals(custom, ShortcutCodec.decode(ShortcutCodec.encode(custom)))
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
        val plan = ShortcutSyncPlanner.plan(local, remote, tombstones = setOf("d"), canPush = true)
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
            canPush = true,
        )
        assertEquals(emptyList<String>(), plan.deleteIds)
        assertEquals(listOf(Shortcut("a", "new")), plan.upserts)
    }

    @Test
    fun nonProPullsAndDeletesButNeverPushes() {
        val plan = ShortcutSyncPlanner.plan(
            local = listOf(Shortcut("a", "local-only"), Shortcut("e", "changed")),
            remote = listOf(
                RemoteShortcut("id-c", "c", "3"),
                RemoteShortcut("id-d", "d", "4"),
                RemoteShortcut("id-e", "e", "remote"),
            ),
            tombstones = setOf("d"),
            canPush = false,
        )
        assertEquals(emptyList<Shortcut>(), plan.upserts) // RLS: insert/update is PRO-only
        assertEquals(listOf("id-d"), plan.deleteIds) // owners may always delete
        assertEquals(listOf(Shortcut("c", "3")), plan.pulled) // expired users get their shortcuts back
    }
}

class ShortcutAccessTest {
    @Test
    fun addAndEditGate() {
        assertEquals(ShortcutAccess.ALLOWED, ShortcutAccess.forAddOrEdit(isPro = true, hasCustomShortcuts = false))
        assertEquals(ShortcutAccess.ALLOWED, ShortcutAccess.forAddOrEdit(isPro = true, hasCustomShortcuts = true))
        assertEquals(ShortcutAccess.EXPIRED, ShortcutAccess.forAddOrEdit(isPro = false, hasCustomShortcuts = true))
        assertEquals(ShortcutAccess.PAYWALL, ShortcutAccess.forAddOrEdit(isPro = false, hasCustomShortcuts = false))
    }

    @Test
    fun customShortcutsKeepWorkingWithoutPro() {
        // The keyboard matches built-ins + customs regardless of PRO (only add/edit is gated).
        val settings = TypeRightSettings(shortcuts = listOf(Shortcut("ㅂㅂ", "바이바이")))
        assertEquals("바이바이", ShortcutRules.match("그럼 ㅂㅂ", settings.allShortcuts)?.expansion)
    }
}
