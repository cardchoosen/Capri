package ai.capri.app.extensions

import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/** Observe wire JSON before the pinned SDK narrows Tool.Input. Never log or retain full messages. */
internal class SchemaCapture(private val schemas: MutableMap<String, JSONObject>, private val multiline: Boolean = false) {
    private val line = ByteArrayOutputStream()
    @Synchronized fun accept(bytes: ByteArray, off: Int, n: Int) {
        if (multiline) { check(line.size() + n <= 2_700_000) { "MCP单条消息超过限额" }; line.write(bytes, off, n); return }
        for (i in off until off + n) {
            if (bytes[i].toInt() == 10) flush() else {
                check(line.size() < 2_700_000) { "MCP单条消息超过限额" }
                line.write(bytes[i].toInt())
            }
        }
    }
    @Synchronized fun flush() {
        if (line.size() == 0) return
        val value = line.toString("UTF-8").trim().removePrefix("data:").trim()
        line.reset()
        val tools = runCatching { JSONObject(value).optJSONObject("result")?.optJSONArray("tools") }.getOrNull() ?: return
        require(tools.length() <= 200) { "服务工具过多" }
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            schemas[tool.getString("name")] = tool.getJSONObject("inputSchema")
        }
    }
    fun stream(input: InputStream): InputStream = object : FilterInputStream(input) {
        override fun read(): Int { val value = super.read(); if (value < 0) flush() else accept(byteArrayOf(value.toByte()), 0, 1); return value }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n < 0) flush() else accept(b, off, n); return n }
    }
    companion object {
        fun interceptor(schemas: MutableMap<String, JSONObject>) = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            if (body == null) response else {
                val capture = SchemaCapture(schemas, response.header("Content-Type")?.contains("text/event-stream") != true)
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val before = sink.size
                        val n = super.read(sink, byteCount)
                        if (n < 0) capture.flush() else {
                            val copied = Buffer(); sink.copyTo(copied, before, n)
                            val bytes = copied.readByteArray(); capture.accept(bytes, 0, bytes.size)
                        }
                        return n
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = source
                }).build()
            }
        }
    }
}
