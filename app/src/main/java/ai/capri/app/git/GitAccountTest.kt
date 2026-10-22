package ai.capri.app.git

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object GitAccountTest {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    suspend fun check(accounts: GitAccounts, host: String, repository: String, http: OkHttpClient = client): String {
        val url = GitCredentialPolicy.repository(repository, host)
        val secret = accounts.secret(host) ?: return "请先保存当前主机的账号"
        val endpoint = url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/info/refs")
            .addQueryParameter("service", "git-upload-pack").build()
        val call = http.newCall(Request.Builder().url(endpoint)
            .header("Authorization", Credentials.basic(secret.username, secret.token)).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resume("连接失败，请检查网络、证书或主机地址")
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        when {
                            it.code == 200 && it.header("Content-Type", "")!!.startsWith("application/x-git-upload-pack-advertisement") -> "仓库可读取；此检查不验证推送权限"
                            it.code == 200 -> "服务已响应，但不是预期的 Git 仓库接口"
                            it.code == 401 -> "认证失败，请检查用户名和 Token"
                            it.code == 403 -> "访问被拒绝，请检查 Token 权限或组织策略"
                            it.code == 404 -> "仓库不存在或当前账号无权访问"
                            it.isRedirect -> "服务返回重定向，请使用最终的 HTTPS 仓库地址"
                            else -> "仓库检查返回 HTTP ${it.code}"
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}
