package ai.capri.host

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class HostTranscription(private val endpoint: HttpUrl, private val model: String, private val token: String?) {
    suspend fun transcribe(audio: ByteArray): JSONObject {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "audio.wav", audio.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(endpoint).post(body)
        if (token != null) request.header("Authorization", "Bearer $token")
        return HostIntegrationHttp.execute(request.build()) { response ->
            if (!response.isSuccessful)
                throw WorkspaceFailure("transcription_http_error", "The speech provider returned HTTP ${response.code}.")
            val bytes = checkNotNull(response.body).byteStream().readNBytes(131_073)
            if (bytes.size > 131_072) throw WorkspaceFailure("transcript_too_large", "The speech response exceeds 128 KiB.")
            val text = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).opt("text") as? String }.getOrNull()?.trim()
            if (text.isNullOrEmpty()) throw WorkspaceFailure("transcript_unavailable", "The speech provider returned no transcript.")
            JSONObject().put("text", text.take(16_000)).put("truncated", text.length > 16_000)
        }
    }
}
