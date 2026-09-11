package com.typeright.keyboard.settings

import com.typeright.keyboard.rules.FeedbackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackModeResolverTest {
    private fun resolve(
        pkg: String?,
        overrides: Map<String, FeedbackMode> = emptyMap(),
        auto: Boolean = true,
        global: FeedbackMode = FeedbackMode.POLICE,
    ) = FeedbackModeResolver.resolve(pkg, overrides, auto, global)

    @Test
    fun builtInsSplitFunAndWorkApps() {
        assertEquals(FeedbackMode.SPICY_WIT, resolve("com.kakao.talk"))
        assertEquals(FeedbackMode.SPICY_WIT, resolve("com.instagram.android"))
        assertEquals(FeedbackMode.GENTLE, resolve("com.Slack"))
        assertEquals(FeedbackMode.GENTLE, resolve("com.google.android.gm"))
    }

    @Test
    fun unknownAppsAndMissingPackageUseGlobalDefault() {
        assertEquals(FeedbackMode.POLICE, resolve("com.example.notes"))
        assertEquals(FeedbackMode.POLICE, resolve(null))
        assertEquals(FeedbackMode.POLICE, resolve(""))
    }

    @Test
    fun userOverrideWinsOverBuiltIn() {
        assertEquals(FeedbackMode.GENTLE, resolve("com.kakao.talk", overrides = mapOf("com.kakao.talk" to FeedbackMode.GENTLE)))
    }

    @Test
    fun autoModeOffIgnoresBuiltInsButKeepsOverrides() {
        assertEquals(FeedbackMode.POLICE, resolve("com.kakao.talk", auto = false))
        assertEquals(FeedbackMode.SPICY_WIT, resolve("com.Slack", mapOf("com.Slack" to FeedbackMode.SPICY_WIT), auto = false))
    }

    @Test
    fun builtInsNeverPickPolice() {
        assertTrue(FeedbackModeResolver.BUILT_IN.values.none { it.mode == FeedbackMode.POLICE })
    }

    @Test
    fun modeChipCyclesAndSkipsPoliceWhenSignedOut() {
        assertEquals(FeedbackMode.GENTLE, FeedbackModeResolver.next(FeedbackMode.SPICY_WIT, allowPolice = true))
        assertEquals(FeedbackMode.POLICE, FeedbackModeResolver.next(FeedbackMode.GENTLE, allowPolice = true))
        assertEquals(FeedbackMode.SPICY_WIT, FeedbackModeResolver.next(FeedbackMode.POLICE, allowPolice = true))
        assertEquals(FeedbackMode.SPICY_WIT, FeedbackModeResolver.next(FeedbackMode.GENTLE, allowPolice = false))
        assertEquals(FeedbackMode.SPICY_WIT, FeedbackModeResolver.next(FeedbackMode.POLICE, allowPolice = false))
    }

    @Test
    fun overridesRoundTripAndBadLinesAreIgnored() {
        val map = mapOf("com.kakao.talk" to FeedbackMode.GENTLE, "com.Slack" to FeedbackMode.POLICE)
        assertEquals(map, AppModeCodec.decode(AppModeCodec.encode(map)))
        assertEquals(
            mapOf("a.b" to FeedbackMode.SPICY_WIT),
            AppModeCodec.decode("a.b\tspicy_wit\nno-tab\n\tgentle\nc.d\tturbo"),
        )
        assertEquals(emptyMap<String, FeedbackMode>(), AppModeCodec.decode(null))
    }
}
