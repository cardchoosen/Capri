package ai.capri.app

import android.os.Bundle
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Base64

/** Only attachments already sent to this chat. Neither absolute paths nor arbitrary file reads are accepted. */
internal object DebugWebAttachments {
    private val images = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    internal data class Ticket(val file: File, val size: Long, val modified: Long, val mime: String, val name: String, val offset: Int) {
        fun read(): JSONObject {
            check(Looper.myLooper() != Looper.getMainLooper()) { "attachment IO must be off Main" }
            check(file.isFile && file.length() == size && file.lastModified() == modified) { "附件已变化或被删除，请刷新会话" }
            val bytes = ByteArray(minOf(65536L, size - offset).toInt())
            FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                check(channel.size() == size) { "附件已变化" }
                channel.position(offset.toLong())
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "附件读取未完成" }
            }
            check(file.length() == size && file.lastModified() == modified) { "附件已变化" }
            return JSONObject().put("data", Base64.getEncoder().encodeToString(bytes)).put("size", size)
                .put("offset", offset).put("next_offset", offset + bytes.size).put("mime", mime).put("name", name)
        }
    }
    fun prepare(vm: CapriModel, args: Bundle): Ticket {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(args.getString("epoch") == DebugWebBridge.epoch) { "app restarted; refresh attachment" }
        val chat = args.getString("chat_id").orEmpty()
        val session = vm.sessions.firstOrNull { it.id == chat } ?: error("会话已删除")
        check(chat !in vm.deletingChats) { "会话正在删除" }
        val entry = session.messages.getOrNull(args.getInt("entry", -1)) ?: error("附件记录不存在")
        if (args.containsKey("context_id")) require(args.getString("context_id") == session.contextVersion) { "History changed; reload attachments." }
        if (args.containsKey("entry_id")) require(args.getString("entry_id") == entry.id) { "Attachment message changed." }
        require(entry.role == "attachment") { "仅允许读取已发送的附件" }
        val item = JSONObject(entry.text)
        val file = vm.store.workspace.file(chat, item.getString("path"))
        val size = item.getLong("size")
        require(size in 1..Attachments.MAX_BYTES && file.isFile && file.length() == size && file.lastModified() == item.getLong("modified_at")) { "附件已变化或不存在" }
        val offset = args.getInt("offset", 0)
        require(offset.toLong() in 0..size) { "invalid byte offset" }
        val mime = item.optString("mime").takeIf { it in images } ?: "application/octet-stream"
        return Ticket(file, size, item.getLong("modified_at"), mime, item.optString("name", file.name).take(240), offset)
    }
}
