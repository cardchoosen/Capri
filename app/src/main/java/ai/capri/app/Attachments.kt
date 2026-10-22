package ai.capri.app

import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File

internal object Attachments {
    const val MAX_BYTES = 20_000_000L
    fun relativePath(path: String): String {
        require(!path.contains(":") && !path.contains('\\')) { "仅支持当前会话工作区文件" }
        return path.removePrefix("/workspace/")
    }
    fun inspect(workspace: Workspace, chat: String, path: String, caption: String): JSONObject {
        val relative = relativePath(path)
        val file = workspace.file(chat, relative)
        require(file.isFile && file.length() in 1..MAX_BYTES) { "文件不存在、为空或超过20MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val image = bounds.outMimeType in setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        val extension = file.extension.lowercase()
        require(image || extension !in setOf("png", "jpg", "jpeg", "gif", "webp")) { "图片内容无法识别，请检查生成结果" }
        if (image) require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000L) { "图片尺寸无效或超过3200万像素" }
        return JSONObject().put("path", file.relativeTo(workspace.folder(chat, "workspace").canonicalFile).path)
            .put("name", file.name).put("caption", caption.take(240)).put("size", file.length()).put("modified_at", file.lastModified())
            .put("mime", if (image) bounds.outMimeType else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream")
            .put("width", if (image) bounds.outWidth else 0).put("height", if (image) bounds.outHeight else 0)
    }
    fun existing(entries: List<Entry>, item: JSONObject): Boolean = entries.drop(entries.indexOfLast { it.role == "user" }.coerceAtLeast(0))
        .any { it.role == "attachment" && runCatching {
            val previous = JSONObject(it.text)
            previous.getString("path") == item.getString("path") && previous.getLong("size") == item.getLong("size") && previous.getLong("modified_at") == item.getLong("modified_at")
        }.getOrDefault(false) }
}
