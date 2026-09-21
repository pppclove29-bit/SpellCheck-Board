package com.typeright.keyboard

import com.typeright.keyboard.rules.RulesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 스토어 등록 정보(`docs/store-listing.md`)에 적은 **수치와 교정 예시**를 규칙 파일과 대조한다.
 *
 * 등록 정보는 공개 제출물이라 "사전 193개" 같은 숫자가 실제와 어긋나면 안 된다.
 * 규칙을 늘리거나 줄이면 이 테스트가 먼저 깨지고, 그때 `docs/store-listing.md` 를 같이 고치면 된다.
 *
 * 교정 예시는 `shared/rule-golden-cases.json` 이 이미 동작을 보장하므로, 여기서는
 * **등록 정보에 쓴 예시가 골든 케이스에 실제로 들어 있는지**만 확인한다(없는 기능을 광고하지 않기 위해).
 */
class StoreListingFactsTest {

    private val sharedDir = File(requireNotNull(System.getProperty("typeright.sharedDir")) { "typeright.sharedDir 미설정" })
    private val listing = File(sharedDir.parentFile, "docs/store-listing.md").readText()
    private val rulesJson = File(sharedDir, "korean-rules.json").readText()
    private val goldenJson = File(sharedDir, "rule-golden-cases.json").readText()

    private fun countTopLevelObjects(json: String, key: String): Int =
        RulesParser.parse(rulesJson).let { parsed ->
            when (key) {
                "dictionary" -> parsed.dictionary.size
                else -> parsed.patterns.size
            }
        }

    @Test
    fun `등록 정보의 사전·패턴 개수가 규칙 파일과 일치한다`() {
        val dict = countTopLevelObjects(rulesJson, "dictionary")
        val patterns = countTopLevelObjects(rulesJson, "patterns")
        assertTrue(
            "등록 정보에 적은 사전 개수가 실제($dict)와 다르다 — docs/store-listing.md 를 고칠 것",
            listing.contains("표기 ${dict}개"),
        )
        assertTrue(
            "등록 정보에 적은 패턴 개수가 실제($patterns)와 다르다 — docs/store-listing.md 를 고칠 것",
            listing.contains("패턴 규칙 ${patterns}개"),
        )
    }

    @Test
    fun `등록 정보의 매운맛 멘트 개수가 실제와 일치한다`() {
        // dictionary 항목 중 wit 문구가 달린 개수. README·human-todo C1 의 숫자와도 같아야 한다.
        val wit = Regex("\"wit\"\\s*:\\s*\"").findAll(rulesJson).count()
        assertTrue(
            "등록 정보의 멘트 개수가 실제($wit)와 다르다 — docs/store-listing.md 를 고칠 것",
            listing.contains("전용 멘트 ${wit}개"),
        )
    }

    @Test
    fun `등록 정보에 쓴 교정 예시가 전부 골든 케이스에 있다`() {
        // 등록 정보의 "· 몇일 → 며칠" 형태 줄을 뽑아 골든 케이스와 대조한다.
        val examples = Regex("""·\s*(\S+)\s*→\s*([^\n]+)""").findAll(listing)
            .map { it.groupValues[1].trim() to it.groupValues[2].trim() }
            .filter { (from, _) -> from != "ㅈㅅ" } // 단축어 예시는 규칙이 아니다
            .toList()

        assertTrue("등록 정보에서 교정 예시를 찾지 못했다", examples.isNotEmpty())

        val missing = examples.filterNot { (from, to) ->
            goldenJson.contains("\"original_word\": \"$from\"") && goldenJson.contains("\"suggested_word\": \"$to\"")
        }
        assertTrue(
            "골든 케이스에 없는 교정을 등록 정보에 썼다: $missing — 실제로 되지 않는 동작을 광고하게 된다",
            missing.isEmpty(),
        )
    }

    @Test
    fun `등록 정보가 이번 빌드에 없는 기능을 말하지 않는다`() {
        // 본문(코드 블록) 안에서만 검사한다. 문서 하단의 "쓰지 않은 것" 설명표는 제외.
        val body = Regex("""```\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(listing).joinToString("\n") { it.groupValues[1] }
        val banned = listOf("AI", "인공지능", "GPT", "구독", "프리미엄", "로그인하면", "동기화", "PRO")
        // "광고도, 구독도 없습니다" 처럼 **없다고 말하는** 문장은 문제가 아니다 — 부정어가 있는 줄은 넘긴다.
        val offenders = body.lines()
            .filterNot { it.contains("없") || it.contains("않") }
            .flatMap { line -> banned.filter { line.contains(it) }.map { "$it: ${line.trim()}" } }
        assertTrue(
            "이번 빌드에 없는 기능을 등록 정보 본문이 광고하고 있다: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `등록 정보의 방침 주소가 앱 상수와 같다`() {
        assertTrue(
            "등록 정보의 개인정보처리방침 주소가 앱이 여는 주소와 다르다",
            listing.contains("https://pppclove29-bit.github.io/SpellCheck-Board/privacy/"),
        )
    }
}
