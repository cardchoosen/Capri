package ai.capri.host

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun imageMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.copyOf(8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
    bytes.size >= 3 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte() -> "image/jpeg"
    bytes.size >= 6 && bytes.copyOf(6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

internal fun wavInfo(bytes: ByteArray): JSONObject {
    fun invalid(): Nothing = throw WorkspaceFailure("invalid_audio", "Upload a readable PCM WAV file.")
    fun tag(offset: Int) = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
    if (bytes.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") invalid()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val end = Integer.toUnsignedLong(buffer.getInt(4)) + 8
    if (end !in 12L..bytes.size.toLong()) invalid()
    var cursor = 12L
    var channels = 0
    var bits = 0
    var rate = 0
    var block = 0
    var audio = -1L
    while (cursor + 8 <= end) {
        val at = cursor.toInt()
        val size = Integer.toUnsignedLong(buffer.getInt(at + 4))
        val body = at + 8
        if (body + size > end) invalid()
        when (tag(at)) {
            "fmt " -> {
                if (size < 16 || buffer.getShort(body).toInt() != 1) invalid()
                channels = buffer.getShort(body + 2).toUShort().toInt()
                rate = buffer.getInt(body + 4)
                block = buffer.getShort(body + 12).toUShort().toInt()
                bits = buffer.getShort(body + 14).toUShort().toInt()
            }
            "data" -> audio = size
        }
        cursor = body + size + (size % 2)
    }
    if (channels !in 1..32 || rate !in 1..384_000 || bits !in setOf(8, 16, 24, 32) ||
        block != channels * (bits / 8) || audio < 0 || audio % block != 0L) invalid()
    return JSONObject().put("format", "wav").put("encoding", "pcm").put("channels", channels)
        .put("sampleRate", rate).put("bitsPerSample", bits)
        .put("durationSeconds", audio.toDouble() / (block * rate))
}
