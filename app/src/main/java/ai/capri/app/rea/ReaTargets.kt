package ai.capri.app.rea

import ai.capri.app.RuntimeFiles
import ai.capri.app.RuntimeGate
import ai.capri.app.Workspace
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Copies visible APKs only. Never starts the target or reads its private data. */
internal class ReaTargets(private val context: Context, private val workspace: Workspace) {
    companion object {
        const val MAX_BYTES = 1_073_741_824L
        fun validate(args: JSONObject) {
            require(args.keys().asSequence().all { it in setOf("action", "package_name", "path") }) { "Unknown REA target argument." }
            require(args.has("package_name") != args.has("path")) { "Provide exactly one of package_name or workspace APK path." }
            val key = if (args.has("package_name")) "package_name" else "path"
            require(args.opt(key) is String && args.getString(key).isNotBlank()) { "$key must be a non-empty string." }
            if (key == "package_name") require(args.getString(key).matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Invalid Android package name." }
        }
    }
    @Suppress("DEPRECATION")
    suspend fun acquire(chat: String, args: JSONObject): JSONObject {
        validate(args)
        val lease = RuntimeGate.beginFiles(context, chat, "REA target acquisition")
        var staging: File? = null
        try {
            val packageName = args.optString("package_name").takeIf { args.has("package_name") }
            fun installed() = try { context.packageManager.getPackageInfo(packageName!!, PackageManager.GET_SIGNING_CERTIFICATES) }
                catch (_: PackageManager.NameNotFoundException) { error("Package is not installed or not visible to Capri; provide an APK in this workspace instead.") }
            val info = packageName?.let { installed() }
            fun sources(): List<File> = if (packageName != null) {
                val app = installed().applicationInfo ?: error("Package source information is unavailable.")
                listOf(File(app.sourceDir)) + app.splitSourceDirs.orEmpty().map(::File)
            } else listOf(workspace.file(chat, args.getString("path").removePrefix("/workspace/")))
            val inputs = sources()
            require(inputs.size in 1..128 && inputs.all { it.isFile && it.name.endsWith(".apk", true) && !Files.isSymbolicLink(it.toPath()) }) { "Expected readable regular APK files; at most 128 base/split files." }
            val before = inputs.map { Triple(it.canonicalPath, it.length(), it.lastModified()) }
            val total = before.sumOf { it.second }
            require(total in 1..MAX_BYTES) { "APK set exceeds the 1 GiB acquisition limit or is empty." }
            val relative = ".capri/rea/targets/${UUID.randomUUID()}"
            val target = workspace.file(chat, relative)
            require(target.parentFile!!.canonicalPath.startsWith(workspace.folder(chat, "workspace").canonicalPath + File.separator)) { "Target directory escapes the workspace." }
            target.parentFile!!.mkdirs()
            require(target.parentFile!!.usableSpace > total + 33_554_432L) { "Insufficient storage for the APK snapshot." }
            check(target.mkdir()) { "Could not create the APK snapshot directory." }
            staging = target
            val files = JSONArray()
            var copied = 0L
            var retainedEntries = 0
            inputs.forEachIndexed { index, source ->
                currentCoroutineContext().ensureActive()
                val name = if (index == 0) "base.apk" else "split-${index.toString().padStart(3, '0')}.apk"
                val destination = File(target, name)
                val digest = MessageDigest.getInstance("SHA-256")
                source.inputStream().buffered().use { input -> destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        copied += count; require(copied <= total) { "APK changed during acquisition; retry after the package update finishes." }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
                require(destination.length() == before[index].second) { "APK changed during acquisition." }
                val inventory = JSONArray(); var entries = 0; var matches = 0
                ZipFile(destination).use { zip ->
                    require(zip.getEntry("AndroidManifest.xml")?.isDirectory == false) { "APK has no AndroidManifest.xml entry." }
                    val iterator = zip.entries()
                    while (iterator.hasMoreElements()) {
                        currentCoroutineContext().ensureActive()
                        require(++entries <= 100_000) { "APK exceeds the 100000 ZIP entry limit." }
                        val entry = iterator.nextElement()
                        if (entry.name == "AndroidManifest.xml" || entry.name == "resources.arsc" || entry.name.startsWith("lib/") || entry.name.matches(Regex("classes[0-9]*\\.dex"))) {
                            matches++
                            if (retainedEntries < 1000) {
                                inventory.put(JSONObject().put("name", entry.name.take(512)).put("name_truncated", entry.name.length > 512).put("size", entry.size))
                                retainedEntries++
                            }
                        }
                    }
                }
                files.put(JSONObject().put("path", "/workspace/$relative/$name").put("source", source.path)
                    .put("size", destination.length()).put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                    .put("role", if (info == null) "selected" else if (index == 0) "base" else "split").put("inventory", inventory).put("inventory_matches", matches).put("inventory_truncated", matches > inventory.length()))
            }
            require(inputs.map { Triple(it.canonicalPath, it.length(), it.lastModified()) } == before && sources().map { it.canonicalPath } == before.map { it.first }) { "APK sources changed during acquisition." }
            val manifest = JSONObject().put("status", "acquired").put("manifest_path", "/workspace/$relative/manifest.json")
                .put("source_kind", if (info == null) "workspace" else "installed_package").put("files", files)
                .put("created_at", System.currentTimeMillis()).put("signature_verification", "not_performed")
                .put("note", "Snapshot only; input APKs are unchanged. ZIP inventory is not extracted or executed. REA signature inspection does not verify cryptographic signatures or publisher trust.")
            if (info != null) {
                val after = installed()
                require(after.longVersionCode == info.longVersionCode && after.lastUpdateTime == info.lastUpdateTime) { "Package was updated during acquisition." }
                val signing = info.signingInfo
                fun fingerprints(certificates: Array<android.content.pm.Signature>?) = JSONArray(certificates.orEmpty().map { cert ->
                    MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
                })
                manifest.put("package_name", packageName).put("version_code", info.longVersionCode).put("version_name", info.versionName)
                    .put("last_update_time", info.lastUpdateTime)
                    .put("installed_identity", JSONObject().put("source", "Android PackageManager; refers to the installed package, not independent verification of the copied files")
                        .put("current_signer_sha256", fingerprints(signing?.apkContentsSigners))
                        .put("signing_history_sha256", fingerprints(signing?.signingCertificateHistory)))
            }
            currentCoroutineContext().ensureActive()
            File(target, "manifest.json").writeText(manifest.toString(2))
            staging = null
            // The complete, bounded inventory stays in the workspace; keep the model reply small.
            return JSONObject(manifest.toString()).put("files", JSONArray((0 until files.length()).map { i ->
                JSONObject(files.getJSONObject(i).toString()).apply { remove("inventory") }
            }))
        } finally {
            try { staging?.let(RuntimeFiles::delete) } finally { RuntimeGate.endFiles(lease) }
        }
    }
}
