package ai.capri.app.extensions.hub

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class HubHttpError(val code:Int):IOException("GitHub request failed with HTTP $code.")
internal interface HubRemote {
    suspend fun api(method:String,path:String,body:JSONObject?=null,token:String?=null):JSONObject
    suspend fun raw(commit:String,path:String):ByteArray
}
/** Credentials go only to api.github.com. Public downloads never carry an authorization header. */
internal class HubGitHub(client:OkHttpClient=OkHttpClient()):HubRemote {
    companion object { const val REPOSITORY="cardchoosen/capri-skills" }
    private val http=client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(10,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
    private suspend fun bytes(request:Request,maximum:Int):ByteArray=suspendCancellableCoroutine { continuation ->
        val call=http.newCall(request);continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWith(Result.failure(IOException("GitHub connection failed; inspect the saved receipt before retrying."))) }
            override fun onResponse(call:Call,response:Response) {
                val result=runCatching { response.use { r ->
                    if(!r.isSuccessful)throw HubHttpError(r.code)
                    val body=r.body?:error("GitHub returned an empty response.");require(body.contentLength()<=maximum) { "GitHub response exceeds its byte limit." }
                    body.byteStream().use { input -> val data=HubPackage.read(input,maximum);require(data.size<=maximum) { "GitHub response exceeds its byte limit." };data }
                } }
                if(continuation.isActive)continuation.resumeWith(result)
            }
        })
    }
    override suspend fun api(method:String,path:String,body:JSONObject?,token:String?):JSONObject {
        require(path.startsWith('/') && !path.startsWith("//") && '\\' !in path) { "Invalid GitHub API path." }
        val request=Request.Builder().url("https://api.github.com$path").header("Accept","application/vnd.github+json").header("User-Agent","Capri-Skills")
        token?.let { request.header("Authorization","Bearer $it") }
        request.method(method,body?.toString()?.toRequestBody("application/json".toMediaType()))
        val data=bytes(request.build(),2_000_000)
        // Some endpoints return arrays; preserve them without weakening the object port.
        val text=data.toString(Charsets.UTF_8)
        return if(text.trimStart().startsWith('['))JSONObject().put("items",org.json.JSONArray(text))else JSONObject(text)
    }
    override suspend fun raw(commit:String,path:String):ByteArray {
        require(commit.matches(Regex("[a-f0-9]{40}"))) { "Public downloads require an immutable commit." }
        val safe=path.split('/').joinToString("/") { java.net.URLEncoder.encode(it,"UTF-8").replace("+","%20") }
        return bytes(Request.Builder().url("https://raw.githubusercontent.com/$REPOSITORY/$commit/$safe").header("User-Agent","Capri-Skills").build(),2_000_000)
    }
}
