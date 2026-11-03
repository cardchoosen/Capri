package ai.capri.app.rea

import ai.capri.app.PythonRuntime
import ai.capri.app.extensions.ExtensionStore
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One shared installation, independent MCP homes and evidence per chat/profile. No Root escalation. */
internal class ReaIntegration(private val context: Context, private val runtime: PythonRuntime, private val store: ExtensionStore) {
    companion object {
        val profiles = linkedMapOf("apk" to "APK / DEX", "javascript" to "JavaScript", "native" to "Native binaries", "runtime" to "Runtime instrumentation")
        fun profile(id: String): String? = id.removePrefix("capri-rea-").takeIf { id == "capri-rea-$it" && it in profiles }
        fun temporary(context: Context, chat: String, id: String): File {
            val key = java.security.MessageDigest.getInstance("SHA-256").digest("$chat:$id".toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            return File(context.cacheDir,"rea-tmp/$key")
        }
        private val prepareLock = Mutex()
        val analysisLock = Mutex()
    }
    private val prefs = context.getSharedPreferences("rea", Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled", false)
    private fun tools(profile: String): List<String> {
        val items = context.assets.open("rea/profile-tools.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray(profile) }
        return (0 until items.length()).map { items.getString(it) }
    }
    @Volatile var progress = "Not prepared."
        private set
    init {
        val configs = store.configs().associateBy { it.getString("id") }
        for ((profile, name) in profiles) {
            val id = "capri-rea-$profile"
            val existing = configs[id]
            val desired = JSONObject()
                .put("id", id).put("name", "REA · $name").put("type", "local").put("enabled", enabled)
                .put("description", "Managed on-device REA $profile analysis. Enable the REA enhancement first; activation prepares dependencies. Installation is not proof of provider readiness.")
                .put("command", JSONArray(listOf("/usr/bin/python3", "/opt/capri/rea/runtime.py", "serve", profile)))
                .put("tools", JSONArray(tools(profile))).put("timeout_seconds", 300).put("managed", "capri-rea")
            if ((existing == null || existing.optString("managed") == "capri-rea") && existing?.toString() != desired.toString()) store.saveConfig(desired)
        }
    }
    fun clearTemporary(chat: String) {
        for (profile in profiles.keys) ai.capri.app.RuntimeFiles.delete(temporary(context,chat,"capri-rea-$profile"))
    }
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        for (config in store.configs().filter { profile(it.getString("id")) != null && it.optString("managed") == "capri-rea" }) store.saveConfig(config.put("enabled", value))
    }
    private fun installed(root: File, profile: String): Boolean {
        val required = listOf("node/bin/node", "engine/node_modules/rea-agents/dist/mcp/main.js") + when(profile) {
            "apk" -> listOf("jadx.jar", "apktool.jar", "apktool")
            "native" -> listOf("ghidra/Ghidra/Features/Decompiler/os/linux_arm_64/decompile")
            "runtime" -> listOf("frida/node_modules/frida/build/frida_binding.node")
            else -> emptyList()
        }
        return File(root,"installed-$profile.json").isFile && required.all { File(root,it).isFile }
    }
    private fun currentProgress(): String = runCatching { JSONObject(File(context.filesDir,"runtime/ubuntu/opt/capri/rea/progress.json").readText()).getString("stage") }.getOrDefault(progress)
    fun snapshot(): JSONObject = JSONObject().put("enabled", enabled).put("progress", currentProgress()).put("profiles", JSONArray(profiles.map { (profile, name) ->
        val marker = File(context.filesDir, "runtime/ubuntu/opt/capri/rea/installed-$profile.json")
        JSONObject().put("id", "capri-rea-$profile").put("profile", profile).put("name", name).put("installed", installed(marker.parentFile!!,profile))
            .put("validation", "unverified").put("note", "Provider readiness requires doctor and a real target; an installation marker is not verification.")
    }))
    suspend fun prepare(chat: String, profile: String) = withContext(Dispatchers.IO) {
        require(profile in profiles && enabled) { "REA enhancement is disabled or the profile is unknown." }
        prepareLock.withLock {
            val root = File(context.filesDir, "runtime/ubuntu/opt/capri/rea")
            runtime.ensureSession(chat)
            root.mkdirs()
            for (name in listOf("runtime.py", "source.json", "engine-package.json", "engine-lock.json", "profile-tools.json", "frida-package.json", "frida-lock.json")) {
                val bytes = context.assets.open("rea/$name").use { it.readBytes() }
                val target = File(root, name)
                if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
                    val atomic = android.util.AtomicFile(target); val out = atomic.startWrite()
                    try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
                }
            }
            val marker = File(root, "installed-$profile.json")
            val source = JSONObject(File(root, "source.json").readText()).toString()
            val cached = runCatching { JSONObject(marker.readText()).getJSONObject("source").toString() == source }.getOrDefault(false)
            if (cached && installed(root,profile)) return@withLock
            progress = "Preparing REA: ${profiles[profile]}"
            try {
                val result = runtime.runCommand(chat, listOf("/usr/bin/python3", "/opt/capri/rea/runtime.py", "prepare", profile), timeout = 3600, exclusive = true)
                check(result.startsWith("exit=0\n")) { "REA preparation failed; inspect before retrying: ${result.takeLast(2400)}" }
                progress = "Dependencies installed; provider verification required."
            } catch (e: Exception) { progress = "Preparation incomplete: ${e.message?.take(500)}"; throw e }
        }
    }
}
