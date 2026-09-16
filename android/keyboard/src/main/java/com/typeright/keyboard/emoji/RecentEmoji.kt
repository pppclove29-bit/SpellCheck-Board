package com.typeright.keyboard.emoji

/**
 * The emoji panel's 최근 tab: a most-recently-used list persisted in the shared preferences.
 *
 * Pure functions so the ordering rules are unit-testable without a DataStore.
 */
object RecentEmoji {

    /** One panel row is 8 columns; keep 3 rows' worth so the tab never scrolls. */
    const val MAX = 24

    private const val SEPARATOR = '\n'

    /** Moves [emoji] to the front, removing any earlier occurrence, and trims to [max]. */
    fun push(current: List<String>, emoji: String, max: Int = MAX): List<String> {
        if (emoji.isEmpty()) return current
        return (listOf(emoji) + current.filterNot { it == emoji }).take(max)
    }

    fun encode(emoji: List<String>): String = emoji.joinToString(SEPARATOR.toString())

    fun decode(value: String?): List<String> =
        value?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.take(MAX) ?: emptyList()

    /** What the 최근 tab shows: the user's picks, padded with defaults so the tab is never empty on first open. */
    fun display(recent: List<String>, max: Int = MAX): List<String> =
        (recent + EmojiCatalog.DEFAULT_RECENT.filterNot { it in recent }).take(max)
}
