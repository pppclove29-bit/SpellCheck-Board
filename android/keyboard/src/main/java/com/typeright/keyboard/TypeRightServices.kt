package com.typeright.keyboard

import android.content.Context
import com.typeright.keyboard.account.AccountRepository
import com.typeright.keyboard.api.GrammarApiClient
import com.typeright.keyboard.api.UrlConnectionTransport
import com.typeright.keyboard.auth.AuthSessionManager
import com.typeright.keyboard.auth.DataStoreSessionStore
import com.typeright.keyboard.rules.RuleEngine
import com.typeright.keyboard.rules.RulesParser
import com.typeright.keyboard.settings.SettingsRepository
import com.typeright.keyboard.settings.ShortcutSyncRepository
import com.typeright.keyboard.settings.typeRightDataStore

/**
 * Process-wide object graph shared by the IME service and the host app (same APK, same process), so both use one
 * DataStore, one auth session (and refresh mutex) and one account cache.
 */
class TypeRightServices private constructor(context: Context) {
    private val app = context.applicationContext

    val settings: SettingsRepository = SettingsRepository.get(app)

    val auth: AuthSessionManager = AuthSessionManager(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        store = DataStoreSessionStore(app.typeRightDataStore),
        transport = UrlConnectionTransport(),
    )

    val api: GrammarApiClient = GrammarApiClient(BuildConfig.API_BASE_URL, auth)

    val account: AccountRepository = AccountRepository(app.typeRightDataStore, api)

    val shortcutSync: ShortcutSyncRepository = ShortcutSyncRepository(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        auth = auth,
        settings = settings,
        transport = UrlConnectionTransport(),
    )

    /** True when Supabase is not configured (local dev server with X-Dev-User-Id). */
    val isDevAuth: Boolean get() = auth.isDevMode

    /** Rules bundled from shared/korean-rules.json (generated asset). Loaded once; call off the main thread. */
    val ruleEngine: RuleEngine by lazy {
        val json = app.assets.open(RULES_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        RuleEngine(RulesParser.parse(json))
    }

    companion object {
        const val RULES_ASSET = "korean-rules.json"
        const val REWARD_DEEP_LINK = "typeright://reward"

        @Volatile
        private var instance: TypeRightServices? = null

        fun get(context: Context): TypeRightServices =
            instance ?: synchronized(this) {
                instance ?: TypeRightServices(context).also { instance = it }
            }
    }
}
