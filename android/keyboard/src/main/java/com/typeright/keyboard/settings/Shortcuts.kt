package com.typeright.keyboard.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** JSON (de)serialisation of the shortcut list stored in preferences. */
object ShortcutCodec {
    fun encode(shortcuts: List<Shortcut>): String = JsonArray(
        shortcuts.map { s ->
            buildJsonObject {
                put("key", JsonPrimitive(s.key))
                put("expansion", JsonPrimitive(s.expansion))
            }
        },
    ).toString()

    fun decode(json: String?): List<Shortcut>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            Json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val key = o["key"]?.jsonPrimitive?.content
                val expansion = o["expansion"]?.jsonPrimitive?.content
                if (key.isNullOrEmpty() || expansion.isNullOrEmpty()) null else Shortcut(key, expansion)
            }
        }.getOrNull()
    }
}

object ShortcutRules {
    const val MAX_KEY_LENGTH = 20
    const val MAX_EXPANSION_LENGTH = 500

    /** Built-in shortcuts usable by everyone. Local constants: read-only and never synced. */
    val BUILT_IN: List<Shortcut> = listOf(
        Shortcut("ㅈㅅ", "죄송합니다"),
        Shortcut("ㄱㅅ", "감사합니다"),
        Shortcut("ㅇㅋ", "알겠습니다"),
    )

    fun isBuiltIn(key: String): Boolean = BUILT_IN.any { it.key == key }

    /** Returns a Korean error message, or null if valid. [editingKey] is the key being edited (allowed to stay). */
    fun validate(key: String, expansion: String, existing: List<Shortcut>, editingKey: String? = null): String? = when {
        key.isBlank() -> "단축어를 입력하세요."
        key.any { it.isWhitespace() } -> "단축어에는 공백을 넣을 수 없습니다."
        key.length > MAX_KEY_LENGTH -> "단축어는 ${MAX_KEY_LENGTH}자 이하로 입력하세요."
        expansion.isBlank() -> "바꿀 문구를 입력하세요."
        expansion.length > MAX_EXPANSION_LENGTH -> "문구는 ${MAX_EXPANSION_LENGTH}자 이하로 입력하세요."
        isBuiltIn(key) -> "기본 단축어($key)와 같은 단축어는 쓸 수 없어요."
        key != editingKey && existing.any { it.key == key } -> "이미 등록된 단축어입니다."
        else -> null
    }

    /** Last whitespace-delimited token right before the cursor ("" if the cursor follows whitespace). */
    fun lastToken(textBeforeCursor: CharSequence): String {
        var i = textBeforeCursor.length
        while (i > 0 && !textBeforeCursor[i - 1].isWhitespace()) i--
        return textBeforeCursor.subSequence(i, textBeforeCursor.length).toString()
    }

    fun match(textBeforeCursor: CharSequence, shortcuts: List<Shortcut>): Shortcut? {
        val token = lastToken(textBeforeCursor)
        if (token.isEmpty()) return null
        return shortcuts.firstOrNull { it.key == token }
    }
}
