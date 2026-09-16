package com.typeright.app.share

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.typeright.app.ui.theme.TypeRightTheme
import com.typeright.keyboard.TypeRightServices
import com.typeright.keyboard.analytics.Events
import com.typeright.keyboard.share.ShareCardRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "📸 짤 생성": preview the share card, save it to the gallery or send it through the system share sheet
 * (Instagram, KakaoTalk, …). Translucent and in its own task, so closing returns to where the user was.
 */
class ShareCardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ShareCardRequest.from(intent)
        if (request == null) {
            finish()
            return
        }
        setContent { TypeRightTheme { ShareCardDialog(request, onClose = ::finish) } }
    }
}

@Composable
private fun ShareCardDialog(request: ShareCardRequest, onClose: () -> Unit) {
    val context = LocalContext.current
    val analytics = remember(context) { TypeRightServices.get(context).analytics }
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        bitmap = withContext(Dispatchers.Default) { ShareCardRenderer.render(request) }
        analytics.log(Events.shareCardCreated(request.mode.apiValue))
    }

    fun save() {
        val bmp = bitmap ?: return
        scope.launch {
            val saved = ShareCardStorage.saveToGallery(context, bmp)
            if (saved) analytics.log(Events.shareCardShared("gallery"))
            message = if (saved) "갤러리(사진/TypeRight)에 저장했어요 📸" else "저장하지 못했어요."
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else message = "저장하려면 사진 저장 권한이 필요해요."
    }

    fun onSaveClick() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else save()
    }

    fun onShareClick() {
        val bmp = bitmap ?: return
        scope.launch {
            val uri = runCatching { ShareCardStorage.shareUri(context, bmp) }.getOrNull()
            if (uri == null) {
                message = "공유 이미지를 만들지 못했어요."
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, "TypeRight · 맞춤법 훈수 키보드")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri("TypeRight", uri)
            runCatching { context.startActivity(Intent.createChooser(send, "짤 공유하기")) }
                .onSuccess { analytics.log(Events.shareCardShared("share_sheet")) }
                .onFailure { message = "공유할 앱을 찾지 못했어요." }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.fillMaxWidth(0.92f)) {
            Column(Modifier.padding(16.dp)) {
                Text("📸 짤 생성", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "검사한 문장만 들어가고, 전화번호·이메일 같은 개인정보는 가려져요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ShareCardRenderer.WIDTH.toFloat() / ShareCardRenderer.HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    if (bmp == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(bmp.asImageBitmap(), contentDescription = "맞춤법 훈수 짤 미리보기", modifier = Modifier.fillMaxWidth())
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("닫기") }
                    OutlinedButton(onClick = ::onSaveClick, enabled = bitmap != null) { Text("갤러리 저장") }
                    Spacer(Modifier.padding(start = 8.dp))
                    Button(onClick = ::onShareClick, enabled = bitmap != null) { Text("공유") }
                }
            }
        }
    }
}

/** Gallery save (MediaStore) and share-sheet file (FileProvider) for share cards. */
object ShareCardStorage {
    suspend fun saveToGallery(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val name = "typeright_${System.currentTimeMillis()}.png"
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TypeRight")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                written
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.insertImage(resolver, bitmap, name, "TypeRight") != null
            }
        }.getOrDefault(false)
    }

    /** Writes the PNG to cache/share/ and returns a content:// URI for ACTION_SEND. */
    suspend fun shareUri(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "typeright_card.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
