package ai.capri.app.git

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object GitCredentialPolicy {
    fun host(value: String): String {
        val input = value.trim()
        require(input.length in 1..300 && input.none { it.isWhitespace() || it.isISOControl() }) { "请填写有效的 HTTPS Git 主机" }
        val url = (if (input.contains("://")) input else "https://$input").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Git 主机无效")
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) { "只填写 HTTPS 主机和可选端口，不含账号、仓库路径或参数" }
        val hostname = if (':' in url.host) "[${url.host}]" else url.host
        return hostname + if (url.port == 443) "" else ":${url.port}"
    }
    fun username(value: String): String {
        val result = value.trim()
        require(result.length in 1..200 && result.none { it.isISOControl() || it == ':' }) { "Git 用户名无效" }
        return result
    }
    fun token(value: String): String {
        val result = value.trim()
        require(result.length in 1..8192 && result.all { it.code in 33..126 }) { "请填写有效的 Token" }
        return result
    }
    fun requestedHost(protocol: String, value: String): String? =
        if (protocol != "https") null else runCatching { host(value) }.getOrNull()
    fun repository(value: String, accountHost: String): okhttp3.HttpUrl {
        val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("请填写完整的 HTTPS 仓库地址")
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.encodedPath.trim('/').isNotBlank()) { "仓库地址须为 HTTPS，不含账号、密码、查询参数或片段" }
        require(host("https://${url.toUri().rawAuthority}") == accountHost) { "仓库主机与当前账号不匹配" }
        return url
    }
}

internal data class GitAccountSummary(val host: String, val username: String)
internal class GitSecret(val username: String, val token: String) {
    override fun toString() = "GitSecret(<redacted>)"
}

/** Application-wide accounts. Token plaintext is never stored in a session or returned by list(). */
internal class GitAccounts(context: Context, preferencesName: String = "git-accounts") {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    companion object { private val lock = Any() }
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey("capri-git-accounts", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("capri-git-accounts", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun list(): List<GitAccountSummary> = synchronized(lock) {
        prefs.all.mapNotNull { (host, raw) -> runCatching {
            GitAccountSummary(host, JSONObject(raw as String).getString("username"))
        }.getOrNull() }.sortedBy { it.host }
    }
    suspend fun saveFromSettings(rawHost: String, rawUsername: String, rawToken: String,
                                 resolveGitHub: suspend (String) -> String = { GitHubAccountResolver.username(it) }): GitAccountSummary {
        val host = GitCredentialPolicy.host(rawHost)
        val username = if (host == "github.com") {
            if (rawToken.isBlank()) list().firstOrNull { it.host == host }?.username
                ?: throw IllegalArgumentException("请填写 Token")
            else resolveGitHub(GitCredentialPolicy.token(rawToken))
        } else GitCredentialPolicy.username(rawUsername)
        currentCoroutineContext().ensureActive()
        save(host, username, rawToken)
        return GitAccountSummary(host, username)
    }
    fun save(rawHost: String, rawUsername: String, rawToken: String) = synchronized(lock) {
        val host = GitCredentialPolicy.host(rawHost)
        val username = GitCredentialPolicy.username(rawUsername)
        // Empty edit keeps the saved token, including when the display username changes.
        val token = if (rawToken.isBlank()) secret(host)?.token ?: throw IllegalArgumentException("请填写 Token") else GitCredentialPolicy.token(rawToken)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD("$host\n$username".toByteArray())
        }
        val record = JSONObject().put("username", username)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
        check(prefs.edit().putString(host, record.toString()).commit()) { "Git 账号保存失败" }
    }
    fun remove(host: String) = synchronized(lock) {
        check(prefs.edit().remove(GitCredentialPolicy.host(host)).commit()) { "Git 账号删除失败" }
    }
    fun secret(host: String, requestedUsername: String = ""): GitSecret? = synchronized(lock) {
        val raw = prefs.getString(host, null) ?: return@synchronized null
        val record = JSONObject(raw)
        val username = record.getString("username")
        if (requestedUsername.isNotEmpty() && requestedUsername != username) return@synchronized null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(record.getString("iv"), Base64.NO_WRAP)))
            updateAAD("$host\n$username".toByteArray())
        }
        GitSecret(username, cipher.doFinal(Base64.decode(record.getString("ciphertext"), Base64.NO_WRAP)).toString(Charsets.UTF_8))
    }
}
