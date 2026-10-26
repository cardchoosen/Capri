package ai.capri.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Entry(val role: String, val text: String, internal val reveal: FinalReplyReveal? = null, internal val finalReply: Boolean = reveal != null)
data class Session(val id: String = UUID.randomUUID().toString(), var title: String = "新会话", val messages: MutableList<Entry> = mutableListOf())
data class ModelConfig(val endpoint: String = "https://api.openai.com/v1", val model: String = "", val key: String = "", val protocol: String = "CHAT", val accountId: String = "", val contextWindow: Int? = null, val maxOutput: Int? = null, val contextSource: String = "models_api", val providerId: String = "", val modelName: String = "")

class Workspace(private val base: File) {
    fun root(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(base, id).apply { mkdirs() }
    }
    fun folder(id: String, name: String) = File(root(id), name).apply { mkdirs() }
    fun file(id: String, path: String): File {
        val root = folder(id, "workspace").canonicalFile
        require(path.isNotBlank() && !File(path).isAbsolute) { "仅允许工作区相对路径" }
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "路径超出当前会话工作区" }
        return file
    }
    fun list(id: String): List<File> {
        val root = folder(id, "workspace").canonicalFile
        return root.walkTopDown().onEnter { it.canonicalPath.startsWith(root.path) && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalPath.startsWith(root.path + File.separator) }.take(200).toList()
    }
}

class Store(val context: Context) {
    val workspace = Workspace(File(context.filesDir, "sessions"))
    private val index = AtomicFile(File(context.filesDir, "sessions.json"))
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    fun load(): MutableList<Session> = runCatching {
        val data = JSONArray(index.openRead().bufferedReader().use { it.readText() })
        (0 until data.length()).map { i ->
            val obj = data.getJSONObject(i)
            val messages = obj.getJSONArray("messages")
            Session(obj.getString("id"), obj.getString("title"), (0 until messages.length()).map { j ->
                messages.getJSONObject(j).let { Entry(it.getString("role"), it.getString("text"), finalReply = it.optBoolean("final_reply", false)) }
            }.toMutableList())
        }.toMutableList()
    }.getOrElse { mutableListOf(Session()) }
    fun save(sessions: List<Session>) {
        val data = JSONArray()
        sessions.forEach { s ->
            val messages = JSONArray()
            s.messages.forEach { messages.put(JSONObject().put("role", it.role).put("text", it.text).put("final_reply", it.finalReply)) }
            data.put(JSONObject().put("id", s.id).put("title", s.title).put("messages", messages))
        }
        val stream = index.startWrite()
        try { stream.write(data.toString().toByteArray()); index.finishWrite(stream) }
        catch (e: Exception) { index.failWrite(stream); throw e }
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("capri-model", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("capri-model", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    private fun legacyConfig(): ModelConfig {
        val token = runCatching {
            val raw = prefs.getString("token", null) ?: return@runCatching ""
            val parts = raw.split(":")
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
            }
        }.getOrDefault("")
        return ModelConfig(prefs.getString("endpoint", "https://api.openai.com/v1")!!, prefs.getString("model", "")!!, token)
    }
    private val modelCatalog by lazy { ai.capri.app.model.ModelCatalog(context.assets.open("model_catalog.json").bufferedReader().use { it.readText() }) }
    internal val models = ai.capri.app.model.ModelAccounts(
        read = { prefs.getString("model_accounts", null)?.let(::decrypt) },
        write = { raw -> check(prefs.edit().putString("model_accounts", encrypt(raw)).remove("token").remove("endpoint").remove("model").commit()) { "模型配置保存失败" } },
        legacy = ::legacyConfig,
        decorate = { account -> account.copy(models = modelCatalog.enrich(account, account.models)) }
    )
    fun config(chat: String? = null): ModelConfig = models.config(chat)
    fun configure(config: ModelConfig) {
        val provider = ai.capri.app.model.Providers.detect(config.endpoint)
        val previous = models.selected()?.first
        models.save(ai.capri.app.model.ModelAccount(id = previous?.id ?: UUID.randomUUID().toString(), provider = provider.id,
            name = provider.name, endpoint = config.endpoint, key = config.key, protocol = ai.capri.app.model.ModelProtocol.valueOf(config.protocol),
            models = listOf(ai.capri.app.model.ModelOption(config.model, manual = true))))
    }
    private fun encrypt(raw: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(raw.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(raw: String): String {
        val parts = raw.split(":")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
        }
    }
    fun profile(query: String): String {
        val archive = JSONArray(context.assets.open("persona/archive.json").bufferedReader().use { it.readText() })
        val terms = query.lowercase().split(Regex("\\s+"))
        val matches = (0 until archive.length()).map { archive.getJSONObject(it) }.filter { obj -> terms.any { obj.toString().lowercase().contains(it) } }
        return matches.joinToString("\n") { it.getString("title") + "：" + it.getString("body") }.ifEmpty { "档案中没有对应记录；请明确说明，不要编造。" }
    }
}
