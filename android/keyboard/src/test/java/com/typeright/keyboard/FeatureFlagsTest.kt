package com.typeright.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 플래그를 되살릴 때(`typeright.cloudFeatures=true`) 일부만 켜지는 사고를 막는다.
 * 어느 값으로 빌드하든 성립해야 하는 관계만 검사한다 — 현재 빌드가 온디바이스 전용인지 여부는 검사하지 않는다.
 */
class FeatureFlagsTest {
    @Test
    fun `서버가 필요한 기능은 전부 같은 스위치를 본다`() {
        val cloud = FeatureFlags.cloud
        assertEquals(cloud, FeatureFlags.ai)
        assertEquals(cloud, FeatureFlags.auth)
        assertEquals(cloud, FeatureFlags.billing)
        assertEquals(cloud, FeatureFlags.ads)
        assertEquals(cloud, FeatureFlags.shortcutSync)
        assertEquals(cloud, FeatureFlags.proGate)
    }

    @Test
    fun `결제가 없으면 PRO 잠금이 풀리고, 있으면 그대로 적용된다`() {
        if (FeatureFlags.proGate) {
            // 결제가 있는 빌드: 원래 페이월 그대로.
            assertTrue(FeatureFlags.proUnlocked(isPro = true))
            assertFalse(FeatureFlags.proUnlocked(isPro = false))
        } else {
            // 결제가 없는 빌드: 아무도 열 수 없는 막다른 길이 생기지 않도록 무조건 열어 준다.
            assertTrue(FeatureFlags.proUnlocked(isPro = true))
            assertTrue(FeatureFlags.proUnlocked(isPro = false))
        }
    }

    @Test
    fun `PRO 구독자는 어떤 빌드에서도 잠기지 않는다`() {
        assertTrue(FeatureFlags.proUnlocked(isPro = true))
    }
}
