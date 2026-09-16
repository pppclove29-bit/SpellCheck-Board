package com.typeright.keyboard.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentEmojiTest {

    @Test
    fun `picking an emoji puts it first`() {
        assertEquals(listOf("😂", "👍"), RecentEmoji.push(listOf("👍"), "😂"))
    }

    @Test
    fun `picking an emoji again moves it to the front instead of duplicating it`() {
        assertEquals(listOf("👍", "😂", "🔥"), RecentEmoji.push(listOf("😂", "🔥", "👍"), "👍"))
    }

    @Test
    fun `the list is capped, dropping the least recent`() {
        val full = (1..RecentEmoji.MAX).map { "e$it" }
        val pushed = RecentEmoji.push(full, "new")
        assertEquals(RecentEmoji.MAX, pushed.size)
        assertEquals("new", pushed.first())
        assertTrue("e${RecentEmoji.MAX}" !in pushed)
    }

    @Test
    fun `an empty pick is ignored`() {
        assertEquals(listOf("😂"), RecentEmoji.push(listOf("😂"), ""))
    }

    @Test
    fun `encode and decode round-trip`() {
        val list = listOf("😂", "🔥", "❤️")
        assertEquals(list, RecentEmoji.decode(RecentEmoji.encode(list)))
    }

    @Test
    fun `decoding missing or empty preferences yields an empty list`() {
        assertEquals(emptyList<String>(), RecentEmoji.decode(null))
        assertEquals(emptyList<String>(), RecentEmoji.decode(""))
    }

    @Test
    fun `decoding never returns more than the cap`() {
        val overflowing = RecentEmoji.encode((1..RecentEmoji.MAX * 2).map { "e$it" })
        assertEquals(RecentEmoji.MAX, RecentEmoji.decode(overflowing).size)
    }

    @Test
    fun `the recent tab is padded with defaults and never repeats`() {
        val display = RecentEmoji.display(listOf("🍕"))
        assertEquals("🍕", display.first())
        assertEquals(RecentEmoji.MAX, display.size)
        assertEquals(display.distinct(), display)
    }

    @Test
    fun `a full recent list is not padded`() {
        val full = (1..RecentEmoji.MAX).map { "e$it" }
        assertEquals(full, RecentEmoji.display(full))
    }
}
