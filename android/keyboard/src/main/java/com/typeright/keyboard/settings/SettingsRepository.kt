package com.typeright.keyboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.typeright.keyboard.rules.FeedbackMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The single preferences file shared by the host app and the IME (same APK, same process). Holds user settings,
 * the cached account/quota and the auth session.
 */
internal val Context.typeRightDataStore: DataStore<Preferences> by preferencesDataStore(name = "typeright_settings")

/** User settings shared by the host app and the IME; changes in the app reach the keyboard immediately. */
class SettingsRepository private constructor(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<TypeRightSettings> = dataStore.data
        .map { p ->
            TypeRightSettings(
                aiEnabled = p[Keys.AI_ENABLED] ?: true,
                feedbackMode = FeedbackMode.fromApi(p[Keys.FEEDBACK_MODE]) ?: FeedbackMode.DEFAULT,
                shortcuts = ShortcutCodec.decode(p[Keys.SHORTCUTS]) ?: TypeRightSettings.DEFAULT_SHORTCUTS,
                koreanLayout = p[Keys.KOREAN_LAYOUT]?.let { v -> KoreanLayout.entries.firstOrNull { it.name == v } }
                    ?: KoreanLayout.DUBEOLSIK,
                lastLanguage = p[Keys.LAST_LANGUAGE]?.let { v -> KeyboardLanguage.entries.firstOrNull { it.name == v } }
                    ?: KeyboardLanguage.KOREAN,
            )
        }
        .distinctUntilChanged()

    suspend fun setAiEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AI_ENABLED] = enabled }
    }

    suspend fun setFeedbackMode(mode: FeedbackMode) {
        dataStore.edit { it[Keys.FEEDBACK_MODE] = mode.apiValue }
    }

    suspend fun setKoreanLayout(layout: KoreanLayout) {
        dataStore.edit { it[Keys.KOREAN_LAYOUT] = layout.name }
    }

    suspend fun setLastLanguage(language: KeyboardLanguage) {
        dataStore.edit { it[Keys.LAST_LANGUAGE] = language.name }
    }

    /**
     * Adds or replaces a shortcut. When [replacingKey] is given, that entry is replaced in place (a renamed key
     * tombstones the old one for sync). PRO only — gated in the host app UI.
     */
    suspend fun upsertShortcut(shortcut: Shortcut, replacingKey: String? = null) {
        dataStore.edit { p ->
            val current = ShortcutCodec.decode(p[Keys.SHORTCUTS]) ?: TypeRightSettings.DEFAULT_SHORTCUTS
            val target = replacingKey ?: shortcut.key
            val index = current.indexOfFirst { it.key == target }
            val updated = if (index >= 0) current.toMutableList().also { it[index] = shortcut } else current + shortcut
            p[Keys.SHORTCUTS] = ShortcutCodec.encode(updated.distinctBy { it.key })
            var tombstones = decodeKeys(p[Keys.SHORTCUT_TOMBSTONES]) - shortcut.key
            if (replacingKey != null && replacingKey != shortcut.key) tombstones = tombstones + replacingKey
            p[Keys.SHORTCUT_TOMBSTONES] = encodeKeys(tombstones)
        }
    }

    suspend fun deleteShortcut(key: String) {
        dataStore.edit { p ->
            val current = ShortcutCodec.decode(p[Keys.SHORTCUTS]) ?: TypeRightSettings.DEFAULT_SHORTCUTS
            p[Keys.SHORTCUTS] = ShortcutCodec.encode(current.filterNot { it.key == key })
            p[Keys.SHORTCUT_TOMBSTONES] = encodeKeys(decodeKeys(p[Keys.SHORTCUT_TOMBSTONES]) + key)
        }
    }

    suspend fun currentShortcuts(): List<Shortcut> =
        ShortcutCodec.decode(dataStore.data.first()[Keys.SHORTCUTS]) ?: TypeRightSettings.DEFAULT_SHORTCUTS

    /** Keys deleted locally whose remote rows still need deleting. */
    suspend fun shortcutTombstones(): Set<String> = decodeKeys(dataStore.data.first()[Keys.SHORTCUT_TOMBSTONES])

    suspend fun clearShortcutTombstones(keys: Set<String>) {
        if (keys.isEmpty()) return
        dataStore.edit { p -> p[Keys.SHORTCUT_TOMBSTONES] = encodeKeys(decodeKeys(p[Keys.SHORTCUT_TOMBSTONES]) - keys) }
    }

    /** Adds remote-only shortcuts pulled by sync; never overwrites local entries (local is the source of truth). */
    suspend fun addShortcutsIfAbsent(pulled: List<Shortcut>) {
        dataStore.edit { p ->
            val current = ShortcutCodec.decode(p[Keys.SHORTCUTS]) ?: TypeRightSettings.DEFAULT_SHORTCUTS
            val keys = current.mapTo(HashSet()) { it.key }
            p[Keys.SHORTCUTS] = ShortcutCodec.encode(current + pulled.filter { it.key !in keys })
        }
    }

    private fun decodeKeys(value: String?): Set<String> =
        value?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun encodeKeys(keys: Set<String>): String = keys.joinToString("\n")

    private object Keys {
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val FEEDBACK_MODE = stringPreferencesKey("feedback_mode")
        val SHORTCUTS = stringPreferencesKey("shortcuts_json")
        val SHORTCUT_TOMBSTONES = stringPreferencesKey("shortcut_tombstones")
        val KOREAN_LAYOUT = stringPreferencesKey("korean_layout")
        val LAST_LANGUAGE = stringPreferencesKey("last_language")
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.typeRightDataStore).also { instance = it }
            }
    }
}
