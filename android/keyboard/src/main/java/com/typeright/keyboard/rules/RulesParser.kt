package com.typeright.keyboard.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses shared/korean-rules.json (version 2 format in docs/api-contract.md). */
object RulesParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): RulesFile {
        val root = json.parseToJsonElement(text).jsonObject
        val templates = (root["feedback_templates"] as? JsonObject)?.let { t ->
            FeedbackTemplates(
                spicyWit = t.opt(FeedbackMode.SPICY_WIT.apiValue).orEmpty(),
                police = t.opt(FeedbackMode.POLICE.apiValue).orEmpty(),
                gentle = t.opt(FeedbackMode.GENTLE.apiValue).orEmpty(),
            )
        } ?: FeedbackTemplates.EMPTY

        return RulesFile(
            version = root["version"]?.jsonPrimitive?.int ?: 1,
            feedbackTemplates = templates,
            dictionary = root["dictionary"]?.jsonArray.orEmpty().map { el ->
                val o = el.jsonObject
                DictionaryRule(
                    from = o.opt("from").orEmpty(),
                    to = o.opt("to").orEmpty(),
                    reason = o.opt("reason").orEmpty(),
                    wit = o.opt("wit"),
                )
            },
            patterns = root["patterns"]?.jsonArray.orEmpty().map { el ->
                val o = el.jsonObject
                PatternRule(
                    id = o.opt("id").orEmpty(),
                    pattern = o.opt("pattern").orEmpty(),
                    replacement = o.opt("replacement").orEmpty(),
                    reason = o.opt("reason").orEmpty(),
                    wit = o.opt("wit"),
                )
            },
        )
    }

    private fun JsonObject.opt(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
