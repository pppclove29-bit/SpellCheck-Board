package com.typeright.app

import com.typeright.app.ui.Links
import com.typeright.keyboard.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 공개된 개인정보처리방침(`site/privacy/index.html`)의 문장을 코드·매니페스트로 검증한다.
 *
 * 방침은 Play 심사 제출물이자 공개 웹사이트다. 거기 쓴 문장이 사실이 아니게 되는 변경은
 * **빌드가 아니라 테스트에서** 걸려야 한다. 어느 문장이 어느 코드에 걸려 있는지는
 * `docs/privacy-claims.md` 에 정리돼 있고, 그중 기계로 검사할 수 있는 것을 여기로 옮겼다.
 *
 * 이 테스트가 깨지면 **코드를 되돌리거나, 방침 페이지와 privacy-claims.md 를 같이 고쳐야 한다.**
 * 둘 중 하나만 하고 넘어가면 허위 고지가 된다.
 *
 * 두 플레이버(`ondevice` / `cloud`) 모두에서 돌며, 플레이버에 따라 달라지는 항목은
 * [FeatureFlags] 와 대조하는 방식으로 양쪽 다 성립하게 썼다.
 */
class PrivacyClaimsTest {

    private val appDir = File(requireNotNull(System.getProperty("typeright.appDir")) { "typeright.appDir 미설정" })
    private val repoDir = File(requireNotNull(System.getProperty("typeright.repoDir")) { "typeright.repoDir 미설정" })

    private val mainManifest = File(appDir, "src/main/AndroidManifest.xml").readText()

    /**
     * 검사 대상 프로덕션 소스. 테스트 소스는 제외한다 — 이 파일 자체가 금지 문자열을 담고 있어서
     * 포함하면 항상 자기 자신에 걸린다.
     */
    private fun kotlinSources(): List<File> =
        listOf("app/src/main", "app/src/cloud", "app/src/ondevice", "keyboard/src/main")
            .map { File(appDir.parentFile, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }

    private fun classOnClasspath(name: String): Boolean =
        runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess

    // --- "광고를 표시하지 않습니다" / "광고 ID를 수집하지 않습니다" -------------------------------

    @Test
    fun `광고 SDK 는 광고 기능이 켜진 빌드에만 들어간다`() {
        // ondevice 출시 AAB 에 AdMob 이 남아 있으면 '광고를 표시하지 않는다'가 거짓이 되고,
        // Play '앱 콘텐츠'의 광고 포함 여부 답도 바뀐다.
        assertEquals(
            "AdMob SDK 존재 여부가 FeatureFlags.ads 와 어긋난다",
            FeatureFlags.ads,
            classOnClasspath("com.google.android.gms.ads.MobileAds"),
        )
    }

    @Test
    fun `결제 SDK 는 결제 기능이 켜진 빌드에만 들어간다`() {
        assertEquals(
            "Play Billing SDK 존재 여부가 FeatureFlags.billing 과 어긋난다",
            FeatureFlags.billing,
            classOnClasspath("com.android.billingclient.api.BillingClient"),
        )
    }

    @Test
    fun `광고 ID 수집이 매니페스트에서 꺼져 있다`() {
        val declared = Regex(
            """<meta-data[^>]*google_analytics_adid_collection_enabled[^>]*android:value="(\w+)"""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(mainManifest)
        assertTrue("광고 ID 수집 비활성 메타데이터가 매니페스트에 없다", declared != null)
        assertEquals("광고 ID 수집이 켜져 있다", "false", declared!!.groupValues[1])
    }

    @Test
    fun `AdMob 메타데이터는 메인 매니페스트에 없다`() {
        // cloud 플레이버 매니페스트(src/cloud)에만 있어야 한다. 메인으로 올라오면 모든 빌드에 박힌다.
        assertFalse(
            "AdMob APPLICATION_ID 가 메인 매니페스트에 있다 — 출시 빌드에도 들어간다",
            mainManifest.contains("com.google.android.gms.ads.APPLICATION_ID"),
        )
    }

    // --- "앱을 삭제하면 함께 지워집니다" ----------------------------------------------------------

    @Test
    fun `백업이 꺼져 있다`() {
        // allowBackup 이 켜지면 설정·단축어가 구글 백업으로 넘어가 '기기 안에만 저장'이 거짓이 된다.
        assertTrue(
            "android:allowBackup 이 false 가 아니다",
            Regex("""android:allowBackup="false"""").containsMatchIn(mainManifest),
        )
    }

    // --- "클립보드를 읽지 않습니다" ---------------------------------------------------------------

    @Test
    fun `클립보드를 읽는 코드가 없다`() {
        // 쓰기(setPrimaryClip)는 '복사' 버튼에 쓰이므로 허용. 읽기가 생기면 방침 문장이 거짓이 된다.
        // 클립보드 패널(12.6에서 보류 중)을 만들면 여기서 걸린다.
        val offenders = kotlinSources().filter { f ->
            val src = f.readText()
            src.contains("getPrimaryClip") || src.contains("primaryClipDescription") ||
                Regex("""\.primaryClip\b(?!\s*=)""").containsMatchIn(src)
        }
        assertTrue(
            "클립보드를 읽는 코드가 생겼다: ${offenders.map { it.name }}. " +
                "방침(site/privacy/index.html)의 '클립보드를 읽지 않습니다'를 함께 고쳐야 한다.",
            offenders.isEmpty(),
        )
    }

    // --- "연락처·위치·카메라·마이크에 접근하지 않습니다" -------------------------------------------

    @Test
    fun `민감 권한을 요청하지 않는다`() {
        val forbidden = listOf(
            "READ_CONTACTS", "WRITE_CONTACTS",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
            "CAMERA", "RECORD_AUDIO", "READ_SMS", "READ_PHONE_STATE",
        )
        val found = forbidden.filter { mainManifest.contains("android.permission.$it") }
        assertTrue(
            "방침에 없는 민감 권한이 추가됐다: $found — 방침 6항을 같이 고쳐야 한다",
            found.isEmpty(),
        )
    }

    @Test
    fun `저장공간 권한은 Android 9 이하로만 제한된다`() {
        // 방침: "저장공간 쓰기는 Android 9 이하에서 짤 카드 저장에만 쓴다".
        val block = Regex(
            """<uses-permission[^>]*WRITE_EXTERNAL_STORAGE.*?/>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(mainManifest)
        assertTrue("WRITE_EXTERNAL_STORAGE 선언을 찾지 못했다", block != null)
        assertTrue(
            "WRITE_EXTERNAL_STORAGE 에 maxSdkVersion=28 이 없다 — 최신 기기에서도 권한을 요구하게 된다",
            block!!.value.contains("""android:maxSdkVersion="28""""),
        )
    }

    // --- "계정·로그인이 없습니다" / "입력 문장을 서버로 보내지 않습니다" -----------------------------

    @Test
    fun `온디바이스 빌드에서는 서버를 타는 기능이 모두 꺼져 있다`() {
        // cloud 빌드에서는 이 테스트가 반대 방향을 확인한다(전부 켜져 있어야 한다).
        val cloud = FeatureFlags.cloud
        assertEquals("ai", cloud, FeatureFlags.ai)
        assertEquals("auth", cloud, FeatureFlags.auth)
        assertEquals("shortcutSync", cloud, FeatureFlags.shortcutSync)
    }

    // --- 방침 페이지 자체 ---------------------------------------------------------------------

    @Test
    fun `방침 페이지에 자리표시가 남아 있지 않다`() {
        val page = File(repoDir, "site/privacy/index.html")
        assertTrue("방침 페이지가 없다: ${page.path}", page.exists())
        val html = page.readText()
        assertFalse("방침 페이지에 '넣어 주세요' 자리표시가 남아 있다", html.contains("넣어 주세요"))
        assertFalse("방침 페이지에 TODO 가 남아 있다", html.contains("TODO("))
        assertTrue("방침 페이지에 문의 이메일이 없다", html.contains("musikga1116@gmail.com"))
    }

    @Test
    fun `앱이 가리키는 방침 주소가 실제 페이지 경로와 맞는다`() {
        // 상수는 site/privacy/ 로 배포되는 GitHub Pages 주소여야 한다.
        assertTrue(
            "방침 URL 이 site/privacy/ 배포 경로와 다르다: ${Links.PRIVACY_POLICY_URL}",
            Links.PRIVACY_POLICY_URL.endsWith("/privacy/"),
        )
        assertTrue(
            "방침 URL 이 https 가 아니다",
            Links.PRIVACY_POLICY_URL.startsWith("https://"),
        )
    }
}
