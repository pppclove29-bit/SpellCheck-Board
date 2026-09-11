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
import com.typeright.keyboard.settings.ShortcutRules
import com.typeright.keyboard.settings.ShortcutSyncRepository
import com.typeright.keyboard.settings.TypeRightSettings
import kotlinx.coroutines.launch

/**
 * 단축어 관리. Expansion works for everyone; saving custom shortcuts (add/edit/delete) is PRO — non-PRO users see a
 * paywall placeholder instead of the editor. PRO edits are synced to Supabase right after saving.
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
    var syncMessage by remember { mutableStateOf<String?>(null) }

    fun syncAfterEdit() {
        scope.launch {
            syncMessage = when (services.shortcutSync.sync(account.isPro)) {
                ShortcutSyncRepository.Result.SYNCED -> "☁️ 클라우드와 동기화했어요"
                ShortcutSyncRepository.Result.FAILED -> "동기화 실패 — 기기에는 저장됐고 다음에 다시 시도해요"
                ShortcutSyncRepository.Result.SKIPPED -> null
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        ScreenTitle("단축어", "키보드에서 단축어를 입력하면 제안 칩이 떠요. 칩을 누르면 문구로 바뀌어요.")

        if (account.isPro) {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("+ 단축어 추가") }
            syncMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("👑 커스텀 단축어 저장은 PRO 전용이에요", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "PRO로 업그레이드하면 나만의 단축어를 추가·수정하고 여러 기기에 동기화할 수 있어요. " +
                        "아래 기본 단축어는 무료로 계속 쓸 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenPro) { Text("PRO 알아보기") }
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(settings.shortcuts, key = { it.key }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.key, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("  →  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.expansion, modifier = Modifier.weight(1f), maxLines = 2)
                    if (account.isPro) {
                        TextButton(onClick = { editing = s }) { Text("수정") }
                        TextButton(onClick = {
                            scope.launch {
                                services.settings.deleteShortcut(s.key)
                                syncAfterEdit()
                            }
                        }) { Text("삭제") }
                    }
                }
                HorizontalDivider()
            }
        }
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
                    label = { Text("단축어 (예: ㅈㅅ)") },
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
                    label = { Text("바꿀 문구 (예: 죄송합니다)") },
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
