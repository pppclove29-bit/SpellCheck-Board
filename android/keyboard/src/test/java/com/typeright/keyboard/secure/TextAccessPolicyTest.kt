package com.typeright.keyboard.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 방침의 "비밀번호 입력란과 시크릿 모드에서는 맞춤법 검사 자체를 하지 않는다"를 지킨다.
 *
 * IME 서비스를 JVM 단위 테스트로 띄울 수는 없으므로, **텍스트를 읽는 경로가 하나뿐이고 그 경로가
 * 관문을 거친다**는 구조를 검사한다. 읽기 경로가 하나면 "읽지 않는다"는 약속을 한 곳에서 지킬 수 있고,
 * 새 경로가 생기면 여기서 걸린다.
 */
class TextAccessPolicyTest {

    private val sharedDir = File(requireNotNull(System.getProperty("typeright.sharedDir")))
    private val keyboardSrc = File(sharedDir.parentFile, "android/keyboard/src/main")
    private val appSrc = File(sharedDir.parentFile, "android/app/src")

    private fun productionSources(): List<File> =
        listOf(keyboardSrc, File(appSrc, "main"), File(appSrc, "cloud"), File(appSrc, "ondevice"))
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }

    @Test
    fun `보안 입력란에서는 텍스트를 읽지 않는다`() {
        assertFalse("보안 입력란인데 읽기를 허용했다", TextAccessPolicy.mayReadText(secure = true))
        assertTrue("일반 입력란에서 읽기가 막혔다", TextAccessPolicy.mayReadText(secure = false))
    }

    @Test
    fun `커서 앞 텍스트를 읽는 곳은 IME 의 readWindow 하나뿐이다`() {
        // 읽기 경로가 늘어나면 보안 가드를 우회할 수 있다. 새 경로가 필요하면 readWindow 를 쓰거나,
        // 왜 안전한지 확인하고 이 테스트를 함께 고쳐야 한다.
        val callSites = productionSources().flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> line.contains("EditorText.readBeforeCursor(") }
                .map { (i, line) -> "${f.name}:${i + 1} ${line.trim()}" }
        }
        assertEquals(
            "커서 앞 텍스트를 읽는 곳이 하나가 아니다. 보안 입력란 가드를 우회할 수 있다:\n" +
                callSites.joinToString("\n"),
            1,
            callSites.size,
        )
        assertTrue("읽기 호출이 TypeRightIME 밖에 있다: $callSites", callSites.single().startsWith("TypeRightIME.kt"))
    }

    @Test
    fun `readWindow 가 관문을 거친다`() {
        val ime = File(keyboardSrc, "java/com/typeright/keyboard/ime/TypeRightIME.kt").readText()
        val body = ime.substringAfter("private fun readWindow(").substringBefore("\n    }")
        assertTrue(
            "readWindow 가 TextAccessPolicy 를 거치지 않는다 — 보안 입력란에서도 텍스트를 읽게 된다",
            body.contains("TextAccessPolicy.mayReadText"),
        )
        assertTrue(
            "TextAccessPolicy 검사가 InputConnection 접근보다 뒤에 있다",
            body.indexOf("TextAccessPolicy.mayReadText") < body.indexOf("currentInputConnection"),
        )
    }
}
