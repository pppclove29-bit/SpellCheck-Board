package com.typeright.keyboard.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState
import com.typeright.keyboard.api.AiStatus
import com.typeright.keyboard.api.ApiResult
import com.typeright.keyboard.check.AiCallPolicy
import com.typeright.keyboard.check.AiDecision
import com.typeright.keyboard.check.CheckSnapshot
import com.typeright.keyboard.check.CorrectionApplier
import com.typeright.keyboard.check.Debouncer
import com.typeright.keyboard.check.PoliceGate
import com.typeright.keyboard.check.SentenceExtractor
import com.typeright.keyboard.check.SpanKey
import com.typeright.keyboard.check.StaleGuard
import com.typeright.keyboard.hangul.CheonjiinComposer
import com.typeright.keyboard.hangul.ComposeResult
import com.typeright.keyboard.hangul.Composer
import com.typeright.keyboard.hangul.HangulComposer
import com.typeright.keyboard.ime.layout.KeyAction
import com.typeright.keyboard.ime.layout.KeySpec
import com.typeright.keyboard.ime.layout.LayoutId
import com.typeright.keyboard.ime.ui.KeyboardScreen
import com.typeright.keyboard.rules.Correction
import com.typeright.keyboard.rules.FeedbackMode
import com.typeright.keyboard.rules.RuleEngine
import com.typeright.keyboard.secure.SecureFieldDetector
import com.typeright.keyboard.settings.KeyboardLanguage
import com.typeright.keyboard.settings.KoreanLayout
import com.typeright.keyboard.settings.Shortcut
import com.typeright.keyboard.settings.ShortcutRules
import com.typeright.keyboard.settings.TypeRightSettings
import com.typeright.keyboard.settings.FeedbackModeResolver
import com.typeright.keyboard.share.ShareCardRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TypeRight IME. Compose UI inside an InputMethodService: the service is its own Lifecycle/ViewModelStore/
 * SavedStateRegistry owner and installs them on the window decor view (otherwise ComposeView crashes on show).
 *
 * Everything runs on the main thread except network calls (GrammarApiClient → IO dispatcher). The IME never signs
 * in itself (that needs an Activity); it only reads the session shared with the host app.
 */
class TypeRightIME :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    ImeActions {

    // --- owners for Compose ---------------------------------------------------------------------
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    // --- state ----------------------------------------------------------------------------------
    private lateinit var services: TypeRightServices
    private val ui = ImeUiState()
    private var settings = TypeRightSettings()
    private var account = AccountState()

    /** Signed in with Google (or dev auth). Signed out → on-device checks only + "로그인하면 AI 훈수" chip. */
    private var signedIn = false
    private var ruleEngine: RuleEngine? = null

    /** App being typed in (EditorInfo.packageName) → its feedback mode (앱별 모드: override > built-in > global). */
    private var currentPackage: String? = null
    private val currentMode: FeedbackMode get() = settings.modeFor(currentPackage)

    private val hangul = HangulComposer()
    private val cheonjiin = CheonjiinComposer()
    private val composer: Composer get() = if (ui.layout == LayoutId.CHEONJIIN) cheonjiin else hangul

    private var selStart = -1
    private var selEnd = -1
    private lateinit var debouncer: Debouncer<Unit>
    private var remoteJob: Job? = null
    private var remoteChecking = false
    private var offline = false

    /** Currently displayed check result (local or remote) and the snapshot its offsets refer to. */
    private var snapshot: CheckSnapshot? = null
    private var corrections: List<Correction> = emptyList()

    /** Last remote result, reused while its sentence is unchanged (e.g. space after `?` must not drop AI chips). */
    private var remoteResult: Pair<CheckSnapshot, List<Correction>>? = null
    private var lastAiText: String? = null
    private val policeGate = PoliceGate()
    private var lastFeedbackKey: SpanKey? = null
    private var feedbackSeq = 0L
    private var lastSirenAt = 0L
    private var shortcutMatch: Shortcut? = null
    private var punctCycleIndex = -1
    private var layoutBeforeSymbols = LayoutId.DUBEOLSIK

    // --- lifecycle ------------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        services = TypeRightServices.get(this)
        debouncer = Debouncer(lifecycleScope, Debouncer.DEFAULT_DELAY_MS) { runLocalCheck() }

        lifecycleScope.launch { services.settings.settings.collect(::onSettingsChanged) }
        lifecycleScope.launch {
            services.account.account.collect {
                account = it
                refreshBar()
            }
        }
        lifecycleScope.launch {
            services.auth.authState.collect { state ->
                signedIn = state.isSignedIn
                if (!signedIn) {
                    remoteJob?.cancel()
                    lastAiText = null
                }
                refreshBar()
            }
        }
        lifecycleScope.launch {
            ruleEngine = withContext(Dispatchers.IO) { runCatching { services.ruleEngine }.getOrNull() }
        }
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@TypeRightIME)
            setViewTreeViewModelStoreOwner(this@TypeRightIME)
            setViewTreeSavedStateRegistryOwner(this@TypeRightIME)
            setContent { KeyboardScreen(state = ui, actions = this@TypeRightIME) }
        }
    }

    /** Compose keyboards don't support the extract (fullscreen landscape) UI. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        hangul.reset()
        cheonjiin.reset()
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd

        // Re-evaluated on every field: secure → no capture, no checks, no network, no police blocking.
        ui.secure = SecureFieldDetector.isSecure(info)
        ui.enterLabel = enterLabel(info)
        currentPackage = info.packageName
        lastFeedbackKey = null
        ui.shift = ShiftState.OFF
        ui.feedback = null
        setLayout(initialLayout(info))
        resetCheckState()
        if (!ui.secure && signedIn) {
            // Cache /v1/me (PRO + quota) on keyboard start; not an AI call, no text is sent.
            lifecycleScope.launch { services.account.refreshIfStale() }
        }
        refreshBar()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        finishComposing()
        resetCheckState()
        ui.feedback = null
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        hangul.reset()
        cheonjiin.reset()
        super.onFinishInput()
    }

    override fun onDestroy() {
        debouncer.cancel()
        remoteJob?.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        // The cursor moved away from our composing region (tap elsewhere, app edit): drop composing state.
        val c = composer
        if (c.isComposing && (candidatesEnd < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            hangul.reset()
            cheonjiin.reset()
            currentInputConnection?.finishComposingText()
        }
    }

    // --- settings -------------------------------------------------------------------------------

    private fun onSettingsChanged(new: TypeRightSettings) {
        val old = settings
        settings = new
        if (old.koreanLayout != new.koreanLayout && ui.layout.isKorean()) {
            finishComposing()
            setLayout(koreanLayoutId())
        }
        if (!new.aiActive) remoteJob?.cancel()
        if (old.modeFor(currentPackage) != new.modeFor(currentPackage)) lastFeedbackKey = null
        refreshBar()
    }

    private fun LayoutId.isKorean() = this == LayoutId.DUBEOLSIK || this == LayoutId.CHEONJIIN

    private fun koreanLayoutId() =
        if (settings.koreanLayout == KoreanLayout.CHEONJIIN) LayoutId.CHEONJIIN else LayoutId.DUBEOLSIK

    private fun languageLayoutId(language: KeyboardLanguage) =
        if (language == KeyboardLanguage.KOREAN) koreanLayoutId() else LayoutId.QWERTY

    private fun initialLayout(info: EditorInfo): LayoutId {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when {
            cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME -> LayoutId.SYMBOLS
            SecureFieldDetector.isPassword(info.inputType) -> LayoutId.QWERTY
            cls == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_URI
                ) -> LayoutId.QWERTY
            else -> languageLayoutId(settings.lastLanguage)
        }
    }

    private fun setLayout(id: LayoutId) {
        if (id != LayoutId.SYMBOLS) layoutBeforeSymbols = id
        ui.layout = id
        ui.lettersLabel = if (layoutBeforeSymbols == LayoutId.QWERTY) "ABC" else "가"
        if (id == LayoutId.SYMBOLS || id == LayoutId.CHEONJIIN) ui.shift = ShiftState.OFF
    }

    private fun enterLabel(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "↵"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "전송"
            EditorInfo.IME_ACTION_SEARCH -> "검색"
            EditorInfo.IME_ACTION_GO -> "이동"
            EditorInfo.IME_ACTION_DONE -> "완료"
            EditorInfo.IME_ACTION_NEXT -> "다음"
            else -> "↵"
        }
    }

    // --- key handling ---------------------------------------------------------------------------

    override fun onKey(spec: KeySpec) {
        val action = (if (ui.shift != ShiftState.OFF) spec.shiftAction else null) ?: spec.action ?: return
        dispatch(action)
    }

    override fun onKeyLongPress(spec: KeySpec) {
        spec.longPress?.let(::dispatch)
    }

    private fun dispatch(action: KeyAction) {
        if (action !is KeyAction.CjPunctuation) punctCycleIndex = -1
        when (action) {
            is KeyAction.Jamo -> {
                applyCompose(hangul.input(action.jamo))
                consumeShiftOnce()
                onTyped()
            }
            is KeyAction.CjConsonant -> {
                applyCompose(cheonjiin.inputConsonant(action.key))
                onTyped()
            }
            is KeyAction.CjStroke -> {
                applyCompose(cheonjiin.inputStroke(action.stroke))
                onTyped()
            }
            is KeyAction.Text -> commitChars(action.text)
            KeyAction.CjPunctuation -> cyclePunctuation()
            KeyAction.Backspace -> backspace()
            KeyAction.Space -> onSpace()
            KeyAction.Enter -> onEnter()
            KeyAction.Shift -> ui.shift = when (ui.shift) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> if (ui.layout == LayoutId.QWERTY) ShiftState.LOCKED else ShiftState.OFF
                ShiftState.LOCKED -> ShiftState.OFF
            }
            KeyAction.ToggleLanguage -> {
                finishComposing()
                val next = if (ui.layout.isKorean()) KeyboardLanguage.ENGLISH else KeyboardLanguage.KOREAN
                ui.shift = ShiftState.OFF
                setLayout(languageLayoutId(next))
                lifecycleScope.launch { services.settings.setLastLanguage(next) }
            }
            KeyAction.SwitchKoreanLayout -> {
                finishComposing()
                val next = if (settings.koreanLayout == KoreanLayout.DUBEOLSIK) KoreanLayout.CHEONJIIN else KoreanLayout.DUBEOLSIK
                settings = settings.copy(koreanLayout = next, lastLanguage = KeyboardLanguage.KOREAN)
                setLayout(koreanLayoutId())
                lifecycleScope.launch {
                    services.settings.setKoreanLayout(next)
                    services.settings.setLastLanguage(KeyboardLanguage.KOREAN)
                }
            }
            KeyAction.Symbols -> {
                finishComposing()
                setLayout(LayoutId.SYMBOLS)
            }
            KeyAction.Letters -> setLayout(layoutBeforeSymbols)
        }
    }

    private fun consumeShiftOnce() {
        if (ui.shift == ShiftState.ONCE) ui.shift = ShiftState.OFF
    }

    private fun applyCompose(r: ComposeResult) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (r.commit.isNotEmpty()) ic.commitText(r.commit, 1)
        if (r.composing.isNotEmpty() || r.commit.isEmpty()) ic.setComposingText(r.composing, 1)
        ic.endBatchEdit()
    }

    /** Commits whatever is composing (the active composer's final text replaces the composing region). */
    private fun finishComposing() {
        val ic = currentInputConnection
        val active = composer
        if (active.isComposing) {
            val r = active.finish()
            ic?.commitText(r.commit, 1)
        }
        hangul.reset()
        cheonjiin.reset()
    }

    private fun commitChars(text: String) {
        val isTerminator = text.length == 1 && SentenceExtractor.isTerminator(text[0])
        if (isTerminator && policeBlocks()) return
        finishComposing()
        currentInputConnection?.commitText(text, 1)
        consumeShiftOnce()
        if (isTerminator) sentenceEnded() else onTyped()
    }

    private fun cyclePunctuation() {
        val ic = currentInputConnection ?: return
        if (punctCycleIndex >= 0) {
            punctCycleIndex = (punctCycleIndex + 1) % CJ_PUNCTUATION.length
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(CJ_PUNCTUATION[punctCycleIndex].toString(), 1)
            ic.endBatchEdit()
        } else {
            if (policeBlocks()) return
            finishComposing()
            punctCycleIndex = 0
            ic.commitText(CJ_PUNCTUATION[0].toString(), 1)
        }
        // Settle first (the user may keep cycling); the debounced check sees the terminator and asks the AI.
        onTyped()
    }

    private fun backspace() {
        val r = composer.backspace()
        if (r != null) {
            applyCompose(r)
        } else {
            currentInputConnection ?: return
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        onTyped()
    }

    private fun onSpace() {
        if (policeBlocks()) return
        finishComposing()
        currentInputConnection?.commitText(" ", 1)
        consumeShiftOnce()
        if (!ui.secure) debouncer.flushWith(Unit) // space: flush → on-device check now
    }

    private fun onEnter() {
        if (policeBlocks()) return
        finishComposing()
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multiLine = info != null && info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        when {
            !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED ->
                ic.performEditorAction(action)
            multiLine || noEnterAction -> {
                ic.commitText("\n", 1)
                sentenceEnded()
            }
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** After `.` `!` `?` or newline: on-device check now (+ AI decided inside the check). */
    private fun sentenceEnded() {
        if (!ui.secure) debouncer.flushWith(Unit)
    }

    private fun onTyped() {
        if (!ui.secure) debouncer.submit(Unit)
    }

    // --- checking -------------------------------------------------------------------------------

    private fun resetCheckState() {
        debouncer.cancel()
        remoteJob?.cancel()
        remoteJob = null
        remoteChecking = false
        snapshot = null
        corrections = emptyList()
        remoteResult = null
        lastAiText = null
        lastFeedbackKey = null
        shortcutMatch = null
        policeGate.reset()
    }

    private fun readWindow(maxChars: Int): EditorWindow? {
        val ic = currentInputConnection ?: return null
        return EditorText.readBeforeCursor(ic, maxChars, selStart)
    }

    /** Debounced / flushed on-device check of the current sentence. Main thread; rules are cheap on ≤300 chars. */
    private fun runLocalCheck() {
        if (ui.secure) return
        val engine = ruleEngine ?: return
        val win = readWindow(SentenceExtractor.MAX_WINDOW) ?: return
        val span = SentenceExtractor.extract(win.text)
        // Snapshot = the sentence without trailing whitespace + its absolute start (contract "stale guard"), so typing
        // after it (a space, the next sentence) keeps results valid.
        val snap = CheckSnapshot(span.text.trimEnd(), win.cursorAbs - win.text.length + span.start)
        policeGate.enterSentence(snap.absStart)
        shortcutMatch = ShortcutRules.match(win.text, settings.allShortcuts)

        val remote = remoteResult
        if (remote != null && remote.first == snap) {
            // Sentence unchanged since the AI answered (e.g. a space after `?`): keep the AI result.
            showCorrections(remote.first, remote.second, feedbackOverride = null)
        } else {
            remoteResult = null
            // Match on the untrimmed span so lookaheads like (?=\s) still see the trailing space; keep only spans
            // inside the snapshot.
            val found = engine.check(span.text).filter { it.end <= snap.text.length }
            showCorrections(snap, found, feedbackOverride = null)
        }
        if (SentenceExtractor.endsSentence(win.text)) maybeRequestAi(snap)
    }

    private fun showCorrections(
        snap: CheckSnapshot,
        list: List<Correction>,
        feedbackOverride: String?,
        alert: Boolean = true,
    ) {
        snapshot = snap
        corrections = list
        val police = currentMode == FeedbackMode.POLICE
        val policeSilenced = police && policeGate.isSentenceIgnored(snap.absStart)
        if (alert && !ui.secure && !policeSilenced) {
            val first = list.firstOrNull()
            val key = first?.let { SpanKey.of(snap, it) }
            if (first == null) {
                lastFeedbackKey = null
            } else if (key != lastFeedbackKey || feedbackOverride != null) {
                lastFeedbackKey = key
                val text = feedbackOverride ?: ruleEngine?.feedback(list, currentMode)
                if (!text.isNullOrBlank()) showFeedback(FeedbackUi.styleFor(currentMode), text, shareable = true)
            }
            if (police && policeGate.takeNewlyDetected(snap, list).isNotEmpty()) {
                siren() // every police user gets the haptic warning; PRO additionally gets blocking
            }
        }
        refreshBar()
    }

    private fun maybeRequestAi(snap: CheckSnapshot) {
        val text = snap.text
        val decision = AiCallPolicy.decide(
            secure = ui.secure,
            aiEnabled = settings.aiEnabled,
            aiConsented = settings.aiConsented,
            signedIn = signedIn,
            networkAvailable = isNetworkAvailable(),
            account = account,
            text = text,
            lastAiText = lastAiText,
        )
        // QUOTA_EXHAUSTED: nothing extra — the ⚡충전 button is already emphasized (remaining == 0).
        if (decision != AiDecision.CALL) return
        lastAiText = text
        val requestSnapshot = CheckSnapshot(text, snap.absStart)
        val mode = currentMode
        remoteJob?.cancel()
        remoteJob = lifecycleScope.launch {
            remoteChecking = true
            refreshBar()
            try {
                when (val result = services.api.grammarCheck(text, mode)) {
                    is ApiResult.Success -> {
                        offline = false
                        val r = result.value
                        r.quota?.let { q -> launch { services.account.updateQuota(q) } }
                        if (!ui.secure && isFresh(requestSnapshot)) {
                            remoteResult = requestSnapshot to r.suggestions
                            showCorrections(requestSnapshot, r.suggestions, feedbackOverride = r.witFeedback)
                        }
                        if (r.aiStatus == AiStatus.PAUSED) {
                            // Monthly AI budget paused: show the notice once, then on-device only until /v1/me
                            // reports ai_paused == false (re-checked at keyboard start, at most hourly).
                            launch { services.account.markAiPaused() }
                            if (!ui.secure) showFeedback(FeedbackStyle.NOTICE, r.aiNotice ?: AI_PAUSED_FALLBACK)
                        }
                    }
                    is ApiResult.Failure -> {
                        // Silent fallback: on-device chips stay. UNAUTHENTICATED → signed-out state arrives via authState.
                        offline = result.kind == ApiResult.Failure.Kind.NETWORK
                        lastAiText = null
                    }
                }
            } finally {
                remoteChecking = false
                refreshBar()
            }
        }
    }

    /** Stale-response guard: the snapshot must still be unchanged at the same absolute position before the cursor. */
    private fun isFresh(snap: CheckSnapshot): Boolean {
        val win = readWindow(MAX_LOOKBACK) ?: return false
        return StaleGuard.isFresh(snap, win.text, win.cursorAbs)
    }

    /** 맞춤법 경찰 (PRO): block space/enter/`. ! ?` while un-applied errors remain and the sentence isn't 무시'd. */
    private fun policeBlocks(): Boolean {
        if (ui.secure || currentMode != FeedbackMode.POLICE || !account.isPro) return false
        debouncer.flushWith(Unit) // fresh result for the text as it is now
        val snap = snapshot ?: return false
        if (!policeGate.shouldBlock(currentMode, account.isPro, ui.secure, snap, corrections)) return false
        siren()
        val message = ruleEngine?.feedback(corrections, FeedbackMode.POLICE) ?: POLICE_FALLBACK
        showFeedback(FeedbackStyle.POLICE, message)
        return true
    }

    private fun refreshBar() {
        if (ui.secure) {
            ui.bar = BarState(status = BarStatus.SECURE)
            return
        }
        val snap = snapshot
        val police = currentMode == FeedbackMode.POLICE
        val chips = buildList {
            shortcutMatch?.let { add(ChipUi.ShortcutChip(it)) }
            if (snap != null && corrections.isNotEmpty()) {
                corrections.forEach { add(ChipUi.CorrectionChip(it, snap)) }
                if (police && !policeGate.isSentenceIgnored(snap.absStart)) add(ChipUi.IgnoreChip(snap))
            }
            if (!signedIn) add(ChipUi.LoginChip)
        }
        val status = when {
            !settings.aiActive -> BarStatus.AI_OFF
            account.aiPaused -> BarStatus.AI_PAUSED
            remoteChecking -> BarStatus.CHECKING
            offline -> BarStatus.OFFLINE
            else -> BarStatus.NONE
        }
        ui.bar = BarState(
            chips = chips,
            status = status,
            isPro = account.isPro,
            showRecharge = signedIn && !account.isPro,
            rechargeEmphasized = account.quota?.remaining == 0,
            mode = currentMode,
            showModeChip = true,
        )
    }

    private fun showFeedback(style: FeedbackStyle, text: String, shareable: Boolean = false) {
        ui.feedback = FeedbackUi(++feedbackSeq, style, text, shareable)
    }

    // --- chips ----------------------------------------------------------------------------------

    override fun onChipClick(chip: ChipUi) {
        when (chip) {
            is ChipUi.CorrectionChip -> applyCorrection(chip)
            is ChipUi.IgnoreChip -> {
                // 무시: police off for the whole current sentence (no blocking, no siren); chips stay.
                policeGate.ignoreSentence(chip.snapshot.absStart)
                if (ui.feedback?.style == FeedbackStyle.POLICE) ui.feedback = null
                refreshBar()
            }
            is ChipUi.ShortcutChip -> applyShortcut(chip.shortcut)
            ChipUi.LoginChip -> openHostApp(TypeRightServices.LOGIN_DEEP_LINK)
        }
    }

    override fun onChipLongPress(chip: ChipUi) {
        if (chip is ChipUi.CorrectionChip) {
            val c = chip.correction
            val reason = c.reason.ifBlank { c.type.label }
            showFeedback(FeedbackStyle.REASON, "${c.originalWord} → ${c.suggestedWord} · $reason")
        }
    }

    /** ⚡충전 → host app's RewardAdActivity (translucent, own task) via `typeright://reward`. No ads in the IME. */
    override fun onRechargeClick() = openHostApp(TypeRightServices.REWARD_DEEP_LINK)

    override fun onFeedbackDismiss(id: Long) {
        if (ui.feedback?.id == id) ui.feedback = null
    }

    /** Mode chip: cycles this app's mode and saves it as a per-app override (police needs sign-in, so it's skipped). */
    override fun onModeChipClick() {
        if (ui.secure) return
        val next = FeedbackModeResolver.next(currentMode, allowPolice = signedIn)
        val pkg = currentPackage
        lifecycleScope.launch {
            if (pkg.isNullOrEmpty()) services.settings.setFeedbackMode(next) else services.settings.setAppModeOverride(pkg, next)
        }
        val policeHint = if (!signedIn) " · 경찰 모드는 로그인 후" else ""
        showFeedback(FeedbackStyle.NOTICE, "${FeedbackModeResolver.emoji(next)} 이 앱에서는 ${next.label} 모드$policeHint")
    }

    /** 📸 → host app's ShareCardActivity with the checked sentence, its first correction and the 훈수 line. */
    override fun onShareFeedback(id: Long) {
        val f = ui.feedback?.takeIf { it.id == id && it.shareable } ?: return
        val snap = snapshot ?: return
        val c = corrections.firstOrNull() ?: return
        if (ui.secure || c.end > snap.text.length) return
        val request = ShareCardRequest(snap.text, c.offset, c.length, c.suggestedWord, f.text, currentMode)
        val intent = android.content.Intent()
            .setClassName(packageName, TypeRightServices.SHARE_CARD_ACTIVITY)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(request.putInto(intent)) }
        ui.feedback = null
    }

    private fun applyCorrection(chip: ChipUi.CorrectionChip) {
        val ic = currentInputConnection ?: return
        val c = chip.correction
        ic.beginBatchEdit()
        try {
            finishComposing()
            val win = EditorText.readBeforeCursor(ic, MAX_LOOKBACK, selStart)
            if (win == null || !StaleGuard.isFresh(chip.snapshot, win.text, win.cursorAbs)) {
                // Text changed since the check: drop the chip and re-check what is there now.
                snapshot = null
                corrections = emptyList()
                remoteResult = null
                debouncer.submit(Unit)
                refreshBar()
                return
            }
            val plan = CorrectionApplier.plan(chip.snapshot, c.offset, c.length, c.suggestedWord, win.cursorAbs)
            ic.setComposingRegion(plan.start, plan.end)
            ic.commitText(plan.replacement, 1)
            ic.setSelection(plan.newCursor, plan.newCursor)
            selStart = plan.newCursor
            selEnd = plan.newCursor
        } finally {
            ic.endBatchEdit()
        }
        // Shift the remaining chips by the length delta so they stay valid without a re-check.
        val (newSnap, rest) = CorrectionApplier.rebase(chip.snapshot, c, corrections)
        if (remoteResult?.first == chip.snapshot) remoteResult = newSnap to rest
        if (ui.feedback?.style == FeedbackStyle.POLICE) ui.feedback = null
        showCorrections(newSnap, rest, feedbackOverride = null, alert = false)
    }

    private fun applyShortcut(shortcut: Shortcut) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            finishComposing()
            val before = ic.getTextBeforeCursor(shortcut.key.length, 0)?.toString()
            if (before == shortcut.key) {
                ic.deleteSurroundingText(shortcut.key.length, 0)
                ic.commitText(shortcut.expansion, 1)
            }
        } finally {
            ic.endBatchEdit()
        }
        shortcutMatch = null
        refreshBar()
        onTyped()
    }

    private fun openHostApp(deepLink: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    override fun onSwitchToPreviousKeyboard() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            val token = window.window?.attributes?.token
            @Suppress("DEPRECATION")
            token != null && getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
        }
        if (!switched) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    // --- platform helpers -----------------------------------------------------------------------

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 맞춤법 경찰 siren haptic. */
    private fun siren() {
        val now = SystemClock.uptimeMillis()
        if (now - lastSirenAt < SIREN_THROTTLE_MS) return
        lastSirenAt = now
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(SIREN_TIMINGS, SIREN_AMPLITUDES, -1)
        } else {
            VibrationEffect.createWaveform(SIREN_TIMINGS, -1)
        }
        runCatching { vibrator.vibrate(effect) }
    }

    private companion object {
        const val CJ_PUNCTUATION = ".,?!"
        const val MAX_LOOKBACK = 1000
        const val SIREN_THROTTLE_MS = 600L
        const val POLICE_FALLBACK = "🚨 맞춤법 위반! 교정 칩을 누르거나 '무시'를 눌러야 통과할 수 있어요."
        const val AI_PAUSED_FALLBACK = "오늘 AI 선생님이 퇴근했습니다 😴"
        val SIREN_TIMINGS = longArrayOf(0, 90, 60, 90, 60, 220)
        val SIREN_AMPLITUDES = intArrayOf(0, 140, 0, 200, 0, 255)
    }
}
