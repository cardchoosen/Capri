package ai.capri.app.preview

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object PreviewText {
    fun read(file: File): String {
        require(file.isFile && file.length() <= 256_000) { "文件不存在或超过256KB，请导出查看" }
        val bytes = ByteArray(256001)
        var count = 0
        file.inputStream().use { input ->
            while (count < bytes.size) { val size = input.read(bytes, count, bytes.size - count); if (size < 0) break; count += size }
        }
        require(count <= 256000 && (0 until count).none { bytes[it] == 0.toByte() }) { "此文件不支持文本预览，请导出查看" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, count)).toString()
    }
}
