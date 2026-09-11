package com.typeright.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.account.AccountState
import com.typeright.keyboard.settings.Shortcut
import com.typeright.keyboard.settings.ShortcutAccess
import com.typeright.keyboard.settings.ShortcutRules
import com.typeright.keyboard.settings.ShortcutSyncRepository
import com.typeright.keyboard.settings.TypeRightSettings
import kotlinx.coroutines.launch

/**
 * 단축어. The 3 built-ins (ㅈㅅ/ㄱㅅ/ㅇㅋ) work for everyone and are read-only. Custom shortcuts keep working in the
 * keyboard even after PRO expires and can always be deleted; adding/editing needs PRO ([ShortcutAccess]):
 * an expired user sees "PRO가 만료되어…", a user who never had custom shortcuts sees the normal paywall.
 */
@Composable
fun ShortcutsScreen(
    services: TypeRightServices,
    settings: TypeRightSettings,
    account: AccountState,
    onOpenPro: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var showExpired by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    fun syncAfterEdit() {
        scope.launch {
            syncMessage = when (services.shortcutSync.sync(account.isPro).status) {
                ShortcutSyncRepository.Outcome.Status.SYNCED -> "☁️ 클라우드와 동기화했어요"
                ShortcutSyncRepository.Outcome.Status.FAILED -> "동기화 실패 — 기기에는 저장됐고 다음에 다시 시도해요"
                ShortcutSyncRepository.Outcome.Status.SKIPPED -> null
            }
        }
    }

    /** Add (target == null) or edit a custom shortcut, gated by [ShortcutAccess]. */
    fun requestAddOrEdit(target: Shortcut?) {
        when (ShortcutAccess.forAddOrEdit(account.isPro, hasCustomShortcuts = settings.shortcuts.isNotEmpty())) {
            ShortcutAccess.ALLOWED -> if (target == null) creating = true else editing = target
            ShortcutAccess.EXPIRED -> showExpired = true
            ShortcutAccess.PAYWALL -> showPaywall = true
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        ScreenTitle("단축어", "키보드에서 단축어를 입력하면 제안 칩이 떠요. 칩을 누르면 문구로 바뀌어요.")
        Button(
            onClick = { requestAddOrEdit(null) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (account.isPro) "+ 단축어 추가" else "+ 단축어 추가 (PRO)") }
        syncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            item { SectionLabel("기본 단축어 · 누구나 사용") }
            items(ShortcutRules.BUILT_IN, key = { "builtin:${it.key}" }) { s ->
                ShortcutRow(s, trailing = { Text("기본", style = MaterialTheme.typography.labelSmall) })
            }
            item { SectionLabel(if (account.isPro) "내 단축어" else "내 단축어 · PRO") }
            if (settings.shortcuts.isEmpty()) {
                item {
                    Text(
                        "아직 없어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(settings.shortcuts, key = { "custom:${it.key}" }) { s ->
                ShortcutRow(s, trailing = {
                    TextButton(onClick = { requestAddOrEdit(s) }) { Text("수정") }
                    // Deleting stays allowed for everyone (RLS allows owner delete).
                    TextButton(onClick = {
                        scope.launch {
                            services.settings.deleteShortcut(s.key)
                            syncAfterEdit()
                        }
                    }) { Text("삭제") }
                })
            }
        }
    }

    if (showPaywall) {
        AlertDialog(
            onDismissRequest = { showPaywall = false },
            title = { Text("👑 커스텀 단축어는 PRO 전용이에요") },
            text = {
                Text(
                    "PRO로 업그레이드하면 나만의 단축어를 추가·수정하고 여러 기기에 동기화할 수 있어요. " +
                        "기본 단축어(ㅈㅅ·ㄱㅅ·ㅇㅋ)는 무료로 계속 쓸 수 있어요.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPaywall = false
                    onOpenPro()
                }) { Text("PRO 알아보기") }
            },
            dismissButton = { TextButton(onClick = { showPaywall = false }) { Text("닫기") } },
        )
    }

    if (showExpired) {
        AlertDialog(
            onDismissRequest = { showExpired = false },
            title = { Text("PRO가 만료되어 단축어를 추가할 수 없습니다") },
            text = {
                Text("지금 있는 단축어는 키보드에서 계속 쓸 수 있고 삭제도 할 수 있어요. 추가·수정하려면 PRO를 다시 구독해 주세요.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showExpired = false
                    onOpenPro()
                }) { Text("PRO 업그레이드") }
            },
            dismissButton = { TextButton(onClick = { showExpired = false }) { Text("닫기") } },
        )
    }

    if (account.isPro && (creating || editing != null)) {
        ShortcutDialog(
            initial = editing,
            existing = settings.shortcuts,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { shortcut ->
                val replacing = editing?.key
                creating = false
                editing = null
                scope.launch {
                    services.settings.upsertShortcut(shortcut, replacing)
                    syncAfterEdit()
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ShortcutRow(s: Shortcut, trailing: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.key, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("  →  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.expansion, modifier = Modifier.weight(1f), maxLines = 2)
            trailing()
        }
        HorizontalDivider()
    }
}

@Composable
private fun ShortcutDialog(
    initial: Shortcut?,
    existing: List<Shortcut>,
    onDismiss: () -> Unit,
    onSave: (Shortcut) -> Unit,
) {
    var key by remember { mutableStateOf(initial?.key.orEmpty()) }
    var expansion by remember { mutableStateOf(initial?.expansion.orEmpty()) }
    val error = ShortcutRules.validate(key.trim(), expansion, existing, editingKey = initial?.key)
    var touched by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "단축어 추가" else "단축어 수정") },
        text = {
            Column {
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        touched = true
                    },
                    label = { Text("단축어 (예: ㅂㅂ)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = expansion,
                    onValueChange = {
                        expansion = it
                        touched = true
                    },
                    label = { Text("바꿀 문구 (예: 바이바이)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                if (touched && error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { onSave(Shortcut(key.trim(), expansion)) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
