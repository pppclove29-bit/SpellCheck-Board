package com.typeright.app.check

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.typeright.app.share.ShareCardActivity
import com.typeright.app.ui.theme.TypeRightTheme
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.analytics.Events
import com.typeright.keyboard.api.AiStatus
import com.typeright.keyboard.api.ApiResult
import com.typeright.keyboard.check.AiCallPolicy
import com.typeright.keyboard.rules.Correction
import com.typeright.keyboard.rules.CorrectionSource
import com.typeright.keyboard.rules.Feedback
import com.typeright.keyboard.rules.FeedbackMode
import com.typeright.keyboard.settings.FeedbackModeResolver
import com.typeright.keyboard.share.ShareCardRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Text-selection menu "[맛춤뻡 검사]" (ACTION_PROCESS_TEXT). Works in any app with any keyboard: on-device rules run
 * immediately (no sign-in or consent needed); the AI check follows under the same conditions as the keyboard.
 * For editable fields, [교정 적용] returns the corrected text to the calling app (setResult).
 */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (text.isBlank()) {
            finish()
            return
        }
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val caller = callingActivity?.packageName
        setContent {
            TypeRightTheme {
                ProcessTextDialog(
                    text = text,
                    readOnly = readOnly,
                    callerPackage = caller,
                    onApply = { corrected ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, corrected))
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }
}

private data class CheckUi(
    val ready: Boolean = false,
    val mode: FeedbackMode = FeedbackMode.DEFAULT,
    val corrections: List<Correction> = emptyList(),
    val feedback: String? = null,
    val checkingAi: Boolean = false,
    val note: String? = null,
    /** Signed out or AI off/no consent: offer "AI 훈수 받기" (host app login/consent). */
    val offerAi: Boolean = false,
    val offerRecharge: Boolean = false,
)

@Composable
private fun ProcessTextDialog(
    text: String,
    readOnly: Boolean,
    callerPackage: String?,
    onApply: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val services = remember { TypeRightServices.get(context) }
    var ui by remember { mutableStateOf(CheckUi()) }
    var ignored by remember(ui.corrections) { mutableStateOf(emptySet<Int>()) }

    LaunchedEffect(text) {
        services.analytics.log(Events.processTextOpened())
        val settings = services.settings.settings.first()
        val mode = settings.modeFor(callerPackage)
        val engine = withContext(Dispatchers.IO) { runCatching { services.ruleEngine }.getOrNull() }
        val local = engine?.check(text).orEmpty()
        ui = CheckUi(ready = true, mode = mode, corrections = local, feedback = engine?.feedback(local, mode))

        val signedIn = services.isDevAuth || services.auth.userId() != null
        val account = services.account.current()
        val tooLong = text.codePointCount(0, text.length) > AiCallPolicy.MAX_AI_CHARS
        ui = when {
            !settings.aiActive || !signedIn -> ui.copy(offerAi = true)
            !AiCallPolicy.isAiEligible(text) -> ui.copy(note = if (tooLong) "긴 글은 기본 교정만 돼요" else null)
            account.aiPaused -> ui.copy(note = "오늘 AI 선생님이 퇴근했습니다 😴")
            !account.hasAiQuota -> ui.copy(note = "오늘 AI 훈수를 다 썼어요", offerRecharge = true)
            else -> {
                ui = ui.copy(checkingAi = true)
                when (val r = services.api.grammarCheck(text, mode)) {
                    is ApiResult.Success -> {
                        val v = r.value
                        v.quota?.let { services.account.updateQuota(it) }
                        if (v.aiStatus == AiStatus.PAUSED) services.account.markAiPaused()
                        val quotaOut = v.aiStatus == AiStatus.QUOTA_EXCEEDED
                        ui.copy(
                            corrections = v.suggestions,
                            feedback = v.witFeedback ?: engine?.feedback(v.suggestions, mode),
                            checkingAi = false,
                            note = v.aiNotice ?: if (quotaOut) "오늘 AI 훈수를 다 썼어요" else null,
                            offerRecharge = quotaOut,
                        )
                    }
                    is ApiResult.Failure -> ui.copy(checkingAi = false, note = "AI에 연결하지 못해 기본 교정만 보여줘요")
                }
            }
        }
    }

    val accepted = ui.corrections.filterIndexed { i, _ -> i !in ignored }
    val corrected = Feedback.applyCorrections(text, accepted)

    Dialog(onDismissRequest = onClose) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${FeedbackModeResolver.emoji(ui.mode)} 맞춤법 검사",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.checkingAi || !ui.ready) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(10.dp))
                Text(highlight(text, accepted), style = MaterialTheme.typography.bodyLarge)
                if (accepted.isNotEmpty()) {
                    Text(
                        "→ $corrected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF1B8E3E),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                ui.feedback?.takeIf { ui.corrections.isNotEmpty() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                    )
                }

                if (ui.ready && ui.corrections.isEmpty()) {
                    Text(
                        if (ui.checkingAi) "AI가 한 번 더 보는 중…" else "고칠 곳을 못 찾았어요 👍",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                ui.corrections.forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = i !in ignored, onCheckedChange = { on -> ignored = if (on) ignored - i else ignored + i })
                        Column(Modifier.weight(1f)) {
                            Text("${c.originalWord} → ${c.suggestedWord}", fontWeight = FontWeight.SemiBold)
                            val source = if (c.source == CorrectionSource.AI) "AI · " else ""
                            Text(
                                "$source${c.type.label} · ${c.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                ui.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (ui.offerAi) {
                    TextButton(onClick = { openHostApp(context, TypeRightServices.LOGIN_DEEP_LINK) }) { Text("AI 훈수도 받아보기 →") }
                }
                if (ui.offerRecharge) {
                    TextButton(onClick = { openHostApp(context, TypeRightServices.REWARD_DEEP_LINK) }) { Text("⚡충전") }
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("닫기") }
                    TextButton(
                        onClick = {
                            val first = accepted.firstOrNull() ?: return@TextButton
                            val request = ShareCardRequest(text, first.offset, first.length, first.suggestedWord, ui.feedback ?: "", ui.mode)
                            context.startActivity(request.putInto(Intent(context, ShareCardActivity::class.java)))
                        },
                        enabled = accepted.isNotEmpty() && ui.feedback != null,
                    ) { Text("📸 짤") }
                    TextButton(onClick = {
                        copyToClipboard(context, corrected)
                        onClose()
                    }) { Text("복사") }
                    if (!readOnly) {
                        Button(onClick = { onApply(corrected) }, enabled = accepted.isNotEmpty()) { Text("교정 적용") }
                    }
                }
            }
        }
    }
}

/** Original text with the accepted corrections' spans marked red + struck through. */
private fun highlight(text: String, corrections: List<Correction>): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (c in corrections.sortedBy { it.offset }) {
        if (c.offset < cursor || c.end > text.length) continue
        append(text.substring(cursor, c.offset))
        withStyle(SpanStyle(color = Color(0xFFE53935), textDecoration = TextDecoration.LineThrough)) {
            append(text.substring(c.offset, c.end))
        }
        cursor = c.end
    }
    append(text.substring(cursor))
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TypeRight", text))
}

private fun openHostApp(context: Context, deepLink: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
