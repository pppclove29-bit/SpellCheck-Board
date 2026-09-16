package com.typeright.keyboard.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEventTest {

    /** Every event the catalogue can produce, with representative arguments. */
    private val all = listOf(
        Events.keyboardShown(AppCategory.MESSENGER),
        Events.imeStatus(enabled = true, isDefault = false),
        Events.rulesFound(3),
        Events.aiChecked("used"),
        Events.correctionApplied("rule"),
        Events.correctionIgnored(),
        Events.feedbackShown("spicy_wit"),
        Events.modeChanged("gentle", perApp = true),
        Events.quotaExhausted(isPro = false),
        Events.processTextOpened(),
        Events.shareCardCreated("police"),
        Events.shareCardShared("gallery"),
        Events.rewardAdWatched(credited = true),
        Events.purchaseStarted("typeright_pro_monthly"),
        Events.purchaseVerified("typeright_pro_monthly", "activated"),
        Events.signedIn(),
    )

    private val validName = Regex("^[A-Za-z][A-Za-z0-9_]{0,39}$")
    private val reservedPrefixes = listOf("firebase_", "google_", "ga_")

    @Test
    fun `event and parameter names follow the analytics naming rules`() {
        for (event in all) {
            assertTrue("event name '${event.name}'", validName.matches(event.name))
            assertTrue(event.name, reservedPrefixes.none { event.name.startsWith(it) })
            for (key in event.params.keys) {
                assertTrue("param '$key' of ${event.name}", validName.matches(key))
                assertTrue("param '$key' of ${event.name}", reservedPrefixes.none { key.startsWith(it) })
            }
        }
    }

    @Test
    fun `event names are unique`() {
        val names = all.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    /**
     * The privacy line the whole product rests on: no event may carry free text. Parameters are counts, enum keys
     * and booleans only — nothing that could be a fragment of what somebody typed.
     */
    @Test
    fun `no event carries free text`() {
        val allowedStrings = setOf(
            "messenger", "social", "work", "browser", "other",
            "true", "false",
            "used", "rule", "ai", "spicy_wit", "police", "gentle",
            "gallery", "share_sheet",
            "activated", "typeright_pro_monthly", "typeright_pro_yearly",
        )
        for (event in all) {
            for ((key, value) in event.params) {
                when (value) {
                    is Int, is Long, is Double -> Unit
                    is String -> assertTrue(
                        "'$key' of ${event.name} carries '$value', which is not a known enum value — " +
                            "analytics must never receive user text",
                        value in allowedStrings,
                    )
                    else -> throw AssertionError("unsupported param type for '$key' of ${event.name}")
                }
            }
        }
    }

    @Test
    fun `app categories map known packages and bucket the rest`() {
        assertEquals(AppCategory.MESSENGER, AppCategories.of("com.kakao.talk"))
        assertEquals(AppCategory.WORK, AppCategories.of("com.Slack"))
        assertEquals(AppCategory.BROWSER, AppCategories.of("com.android.chrome"))
        assertEquals(AppCategory.OTHER, AppCategories.of("com.some.unknown.app"))
        assertEquals(AppCategory.OTHER, AppCategories.of(null))
    }

    @Test
    fun `the no-op sink accepts everything without failing`() {
        all.forEach(Analytics.None::log)
        UserProperty.entries.forEach { Analytics.None.setUserProperty(it, "x") }
    }

    @Test
    fun `user property keys follow the naming rules and are unique`() {
        val keys = UserProperty.entries.map { it.key }
        keys.forEach { assertTrue(it, validName.matches(it)) }
        assertEquals(keys.size, keys.distinct().size)
        assertFalse(keys.any { key -> reservedPrefixes.any { key.startsWith(it) } })
    }
}
