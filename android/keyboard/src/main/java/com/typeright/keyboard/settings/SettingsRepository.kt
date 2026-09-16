package com.typeright.keyboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.typeright.keyboard.emoji.RecentEmoji
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
                aiEnabled = p[Keys.AI_ENABLED] ?: false,
                aiConsentAtMillis = p[Keys.AI_CONSENT_AT],
                feedbackMode = FeedbackMode.fromApi(p[Keys.FEEDBACK_MODE]) ?: FeedbackMode.DEFAULT,
                shortcuts = customShortcuts(p),
                koreanLayout = p[Keys.KOREAN_LAYOUT]?.let { v -> KoreanLayout.entries.firstOrNull { it.name == v } }
                    ?: KoreanLayout.DUBEOLSIK,
                lastLanguage = p[Keys.LAST_LANGUAGE]?.let { v -> KeyboardLanguage.entries.firstOrNull { it.name == v } }
                    ?: KeyboardLanguage.KOREAN,
                appAutoMode = p[Keys.APP_AUTO_MODE] ?: true,
                appModeOverrides = AppModeCodec.decode(p[Keys.APP_MODE_OVERRIDES]),
                keyVibration = p[Keys.KEY_VIBRATION] ?: true,
                keySound = p[Keys.KEY_SOUND] ?: false,
                recentEmoji = RecentEmoji.decode(p[Keys.RECENT_EMOJI]),
            )
        }
        .distinctUntilChanged()

    /** Turning AI on is only possible together with recording the data-transfer consent. */
    suspend fun enableAiWithConsent(consentAtMillis: Long) {
        dataStore.edit {
            it[Keys.AI_CONSENT_AT] = consentAtMillis
            it[Keys.AI_ENABLED] = true
        }
    }

    /**
     * Turns AI on/off. Consent is asked only once (the first time AI is enabled); after that the toggle is free.
     * Turning on without recorded consent does nothing and returns false (the caller shows the consent dialog).
     */
    suspend fun setAiEnabled(enabled: Boolean): Boolean {
        var applied = false
        dataStore.edit { p ->
            if (!enabled || p[Keys.AI_CONSENT_AT] != null) {
                p[Keys.AI_ENABLED] = enabled
                applied = true
            }
        }
        return applied
    }

    suspend fun setFeedbackMode(mode: FeedbackMode) {
        dataStore.edit { it[Keys.FEEDBACK_MODE] = mode.apiValue }
    }

    suspend fun setAppAutoMode(enabled: Boolean) {
        dataStore.edit { it[Keys.APP_AUTO_MODE] = enabled }
    }

    /** Sets the feedback mode for one app, or with null removes that app's override. */
    suspend fun setAppModeOverride(packageName: String, mode: FeedbackMode?) {
        dataStore.edit { p ->
            val map = AppModeCodec.decode(p[Keys.APP_MODE_OVERRIDES]).toMutableMap()
            if (mode == null) map.remove(packageName) else map[packageName] = mode
            p[Keys.APP_MODE_OVERRIDES] = AppModeCodec.encode(map)
        }
    }

    suspend fun clearAppModeOverrides() {
        dataStore.edit { it.remove(Keys.APP_MODE_OVERRIDES) }
    }

    suspend fun setKeyVibration(enabled: Boolean) {
        dataStore.edit { it[Keys.KEY_VIBRATION] = enabled }
    }

    suspend fun setKeySound(enabled: Boolean) {
        dataStore.edit { it[Keys.KEY_SOUND] = enabled }
    }

    /** Records an emoji pick at the front of the 최근 tab. */
    suspend fun pushRecentEmoji(emoji: String) {
        dataStore.edit { p ->
            p[Keys.RECENT_EMOJI] = RecentEmoji.encode(RecentEmoji.push(RecentEmoji.decode(p[Keys.RECENT_EMOJI]), emoji))
        }
    }

    suspend fun setKoreanLayout(layout: KoreanLayout) {
        dataStore.edit { it[Keys.KOREAN_LAYOUT] = layout.name }
    }

    suspend fun setLastLanguage(language: KeyboardLanguage) {
        dataStore.edit { it[Keys.LAST_LANGUAGE] = language.name }
    }

    /**
     * Adds or replaces a custom shortcut. When [replacingKey] is given, that entry is replaced in place (a renamed key
     * tombstones the old one for sync). PRO only — gated in the host app UI.
     */
    suspend fun upsertShortcut(shortcut: Shortcut, replacingKey: String? = null) {
        require(!ShortcutRules.isBuiltIn(shortcut.key)) { "built-in shortcuts are read-only" }
        dataStore.edit { p ->
            val current = customShortcuts(p)
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
            p[Keys.SHORTCUTS] = ShortcutCodec.encode(customShortcuts(p).filterNot { it.key == key })
            p[Keys.SHORTCUT_TOMBSTONES] = encodeKeys(decodeKeys(p[Keys.SHORTCUT_TOMBSTONES]) + key)
        }
    }

    /** Custom shortcuts (what sync uploads). */
    suspend fun currentShortcuts(): List<Shortcut> = customShortcuts(dataStore.data.first())

    /** Keys deleted locally whose remote rows still need deleting. */
    suspend fun shortcutTombstones(): Set<String> = decodeKeys(dataStore.data.first()[Keys.SHORTCUT_TOMBSTONES])

    suspend fun clearShortcutTombstones(keys: Set<String>) {
        if (keys.isEmpty()) return
        dataStore.edit { p -> p[Keys.SHORTCUT_TOMBSTONES] = encodeKeys(decodeKeys(p[Keys.SHORTCUT_TOMBSTONES]) - keys) }
    }

    /** Adds remote-only shortcuts pulled by sync; never overwrites local entries (local is the source of truth). */
    suspend fun addShortcutsIfAbsent(pulled: List<Shortcut>): Int {
        var added = 0
        dataStore.edit { p ->
            val current = customShortcuts(p)
            val keys = current.mapTo(HashSet()) { it.key }
            val fresh = pulled.filter { it.key !in keys && !ShortcutRules.isBuiltIn(it.key) }.distinctBy { it.key }
            added = fresh.size
            p[Keys.SHORTCUTS] = ShortcutCodec.encode(current + fresh)
        }
        return added
    }

    /**
     * Logout / account deletion: drops account-bound data stored on the device (custom shortcuts + pending sync
     * state). Device preferences (layout, feedback mode, AI toggle and consent) are kept.
     */
    suspend fun clearUserData() {
        dataStore.edit { p ->
            p.remove(Keys.SHORTCUTS)
            p.remove(Keys.SHORTCUT_TOMBSTONES)
        }
    }

    private fun customShortcuts(p: Preferences): List<Shortcut> =
        ShortcutCodec.decode(p[Keys.SHORTCUTS])?.filterNot { ShortcutRules.isBuiltIn(it.key) } ?: emptyList()

    private fun decodeKeys(value: String?): Set<String> =
        value?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun encodeKeys(keys: Set<String>): String = keys.joinToString("\n")

    private object Keys {
        val AI_ENABLED = booleanPreferencesKey("ai_enabled")
        val AI_CONSENT_AT = longPreferencesKey("ai_consent_at")
        val FEEDBACK_MODE = stringPreferencesKey("feedback_mode")
        val SHORTCUTS = stringPreferencesKey("shortcuts_json")
        val SHORTCUT_TOMBSTONES = stringPreferencesKey("shortcut_tombstones")
        val KOREAN_LAYOUT = stringPreferencesKey("korean_layout")
        val LAST_LANGUAGE = stringPreferencesKey("last_language")
        val APP_AUTO_MODE = booleanPreferencesKey("app_auto_mode")
        val APP_MODE_OVERRIDES = stringPreferencesKey("app_mode_overrides")
        val KEY_VIBRATION = booleanPreferencesKey("key_vibration")
        val KEY_SOUND = booleanPreferencesKey("key_sound")
        val RECENT_EMOJI = stringPreferencesKey("recent_emoji")
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
