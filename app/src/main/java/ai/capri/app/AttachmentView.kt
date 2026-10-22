package ai.capri.app

import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable internal fun AttachmentView(payload: String, workspace: Workspace, chat: String) {
    val data = remember(payload) { runCatching { JSONObject(payload) }.getOrNull() }
    val context = LocalContext.current
    val file = remember(payload, workspace, chat) { runCatching { workspace.file(chat, data!!.getString("path")) }.getOrNull() }
    var expanded by remember(payload) { mutableStateOf(false) }
    var error by remember(payload) { mutableStateOf("") }
    val image = data?.optString("mime")?.startsWith("image/") == true && data.optInt("width") > 0
    val name = data?.optString("name").orEmpty().ifEmpty { "附件" }
    val caption = data?.optString("caption").orEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (image && file?.isFile == true) {
            val ratio = (data!!.getDouble("width") / data.getDouble("height")).toFloat().coerceIn(.5f, 2f)
            LocalImage(workspace, chat, data.getString("path"), Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(ratio).clickable { expanded = true }, name)
        }
        Text(caption.ifBlank { name }, fontSize = 13.sp, color = Color(0xFFF4F6F3))
        Row {
            if (image) TextButton(onClick = { expanded = true }) { Text("查看图片", fontSize = 11.sp) }
            TextButton(onClick = {
                try {
                    val current = workspace.file(chat, data!!.getString("path"))
                    require(current.isFile && current.length() in 1..Attachments.MAX_BYTES)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", current)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(data.getString("mime"))
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享附件"))
                } catch (_: Exception) { error = "文件已删除、移动或不可用" }
            }) { Text("分享文件", fontSize = 11.sp) }
        }
        if (file?.isFile != true || error.isNotEmpty()) Text(error.ifEmpty { "文件已删除或不可用" }, color = Color(0xFFFF514F), fontSize = 11.sp)
    }
    if (expanded && image && data != null) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF080B09)).systemBarsPadding().padding(12.dp)) {
            TextButton(onClick = { expanded = false }) { Text("关闭图片") }
            LocalImage(workspace, chat, data.getString("path"), Modifier.fillMaxWidth().weight(1f), name)
        }
    }
}

/** Native decoder handles GIF/animated WebP; no network loading, WebView or Base64 in model messages. */
@Composable private fun LocalImage(workspace: Workspace, chat: String, path: String, modifier: Modifier, label: String) {
    var error by remember(path) { mutableStateOf(false) }
    val drawable by produceState<Drawable?>(null, workspace, chat, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = workspace.file(chat, path)
                require(file.isFile && file.length() in 1..Attachments.MAX_BYTES)
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    require(info.size.width.toLong() * info.size.height <= 32_000_000L)
                    val scale = minOf(1f, 1280f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
        error = value == null
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(drawable, lifecycle) {
        val animated = drawable as? AnimatedImageDrawable
        val observer = LifecycleEventObserver { _, _ ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) animated?.start() else animated?.stop()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) animated?.start()
        onDispose { lifecycle.removeObserver(observer); animated?.stop() }
    }
    Box(modifier) {
        if (drawable != null) AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = label }
        }, update = { if (it.drawable !== drawable) it.setImageDrawable(drawable) })
        else Text(if (error) "图片无法显示，请检查文件" else "正在加载图片…", color = Color(0xFF939E94), fontSize = 12.sp)
    }
}
