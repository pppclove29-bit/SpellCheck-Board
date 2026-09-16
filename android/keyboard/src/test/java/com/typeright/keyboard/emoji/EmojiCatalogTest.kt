package com.typeright.keyboard.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {

    /**
     * Codepoint ranges first assigned by Emoji 11.0 or later. minSdk 26 = Android 8.0 = Emoji 5.0, and those devices
     * never get a font update, so anything in these ranges would render as tofu (□) for a chunk of the install base.
     */
    private val postEmoji5 = listOf(
        0x1F6D5..0x1F6DF,
        0x1F6FA..0x1F6FF,
        0x1F7E0..0x1F7FF,
        0x1F90C..0x1F90F,
        0x1F96C..0x1F97F,
        0x1F998..0x1F9BF,
        0x1F9C1..0x1F9CF,
        0x1F9E7..0x1F9FF,
        0x1FA00..0x1FAFF,
    )

    private fun offenders(emoji: List<String>): List<String> = emoji.filter { e ->
        e.codePoints().anyMatch { cp -> postEmoji5.any { cp in it } }
    }

    @Test
    fun `every emoji renders on Android 8 (Emoji 5٫0)`() {
        val bad = offenders(EmojiCatalog.all + EmojiCatalog.DEFAULT_RECENT + EmojiCatalog.categories.map { it.tab })
        assertEquals("emoji newer than Emoji 5.0 would show as tofu on minSdk devices", emptyList<String>(), bad)
    }

    @Test
    fun `no category repeats an emoji`() {
        for (category in EmojiCatalog.categories) {
            assertEquals(
                "duplicate emoji in '${category.id}' would crash the grid's key lookup",
                category.emoji.distinct(),
                category.emoji,
            )
        }
    }

    @Test
    fun `categories are non-empty and have distinct ids and tabs`() {
        assertTrue(EmojiCatalog.categories.isNotEmpty())
        EmojiCatalog.categories.forEach { assertTrue(it.id, it.emoji.isNotEmpty()) }
        assertEquals(
            EmojiCatalog.categories.size,
            EmojiCatalog.categories.map { it.id }.distinct().size,
        )
        val tabs = EmojiCatalog.categories.map { it.tab } + EmojiCatalog.RECENT_TAB
        assertEquals(tabs.size, tabs.distinct().size)
    }

    @Test
    fun `no entry is blank or contains whitespace`() {
        (EmojiCatalog.all + EmojiCatalog.DEFAULT_RECENT).forEach {
            assertTrue("'$it'", it.isNotBlank() && it.none(Char::isWhitespace))
        }
    }

    @Test
    fun `the default recent list fills a full panel row set`() {
        assertTrue(EmojiCatalog.DEFAULT_RECENT.size >= RecentEmoji.MAX / 2)
        assertEquals(EmojiCatalog.DEFAULT_RECENT.distinct(), EmojiCatalog.DEFAULT_RECENT)
    }
}
