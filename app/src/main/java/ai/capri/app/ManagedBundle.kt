package ai.capri.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class BundleSpec(val id: String, val title: String, val folder: String, val asset: String,
                               val required: List<String>, val timeout: Long = 120) {
    fun target(context: Context) = File(context.filesDir, folder)
    fun expected(context: Context) = context.assets.open("runtime/$asset.sha256").bufferedReader().use { it.readText().trim() }
    fun parts(context: Context): List<String> = if (asset == "devtools") {
        val parts = context.assets.open("runtime/devtools.parts.json").bufferedReader().use { JSONArray(it.readText()) }
        check(parts.length() in 1..8)
        (0 until parts.length()).map { i -> parts.getString(i).also {
            check(it == "devtools-" + (i + 1).toString().padStart(3, '0') + ".bundle")
        } }
    } else listOf("$asset.bundle")
}

/** One installed copy; staging/previous are temporary transaction files, never selectable versions. */
internal object ManagedBundle {
    val base = BundleSpec("base", "基础 Python", "runtime/rootfs", "rootfs",
        listOf("usr/bin/python3", "usr/include/python3.12/pyconfig.h"))
    val tools = BundleSpec("tools", "开发工具", "runtime/tools/current", "devtools",
        listOf("usr/bin/git", "usr/bin/curl", "usr/bin/ssh", "usr/bin/npm", "usr/bin/node", "capri-bin/gcc", "capri-bin/g++", "usr/share/misc/magic.mgc", "manifest.json"))
    val science = BundleSpec("science", "Python 预编译库", "runtime/python-packages/current", "python-packages",
        listOf("wheels", "manifest.json", "usr/lib/libopenblas.so.3"), 60)
    val all = listOf(base, tools, science)
    fun spec(id: String) = all.firstOrNull { it.id == id } ?: throw IllegalArgumentException("未知环境组件")
    private fun File.textOrEmpty() = if (isFile) readText().trim() else ""
    private fun marker(spec: BundleSpec, directory: File, expected: String) =
        !Files.isSymbolicLink(directory.toPath()) && File(directory, ".complete").textOrEmpty() == expected &&
            spec.required.all { File(directory, it).exists() }
    fun legacy(spec: BundleSpec, context: Context): List<File> = if (spec == base) emptyList() else
        spec.target(context).parentFile!!.listFiles().orEmpty().filter {
            it.name.matches(Regex("[0-9a-f]{64}(\\.staging)?"))
        }
    fun prepare(context: Context, spec: BundleSpec): File = RuntimeGate.observe {
        val target = spec.target(context); val expected = spec.expected(context)
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "${spec.title}资源摘要无效" }
        if (marker(spec, target, expected)) {
            if (RuntimeGate.active(context).isEmpty()) RuntimeGate.mutate(context) { cleanup(context, spec) }
            target
        } else {
            // Existing rootfs belongs to the user too: never implicitly discard manual system changes.
            if (spec == base && target.exists()) error("基础环境需要检查或修复；不会自动覆盖已有基础系统。")
            if (target.exists() && File(target, ".complete").textOrEmpty() == expected)
                error("${spec.title}文件缺失，请在执行环境中修复组件。")
            RuntimeGate.mutate(context) { install(context, spec) }
        }
    }
    fun repair(context: Context, spec: BundleSpec): File = RuntimeGate.mutate(context) { install(context, spec) }

    private fun install(context: Context, spec: BundleSpec): File {
        val target = spec.target(context); val parent = target.parentFile!!.apply { mkdirs() }
        check(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(target.toPath())) { "组件目录为符号链接，拒绝替换" }
        val stage = File(parent, target.name + ".staging")
        val previous = File(parent, target.name + ".previous")
        // Resume interrupted swap before starting a new one. Nothing in sessions/ssh is touched.
        if (!target.exists() && previous.exists()) check(previous.renameTo(target)) { "恢复组件失败" }
        deleteTree(stage); check(stage.mkdirs()) { "无法创建组件暂存目录" }
        val archive = File.createTempFile("capri-${spec.id}-", ".tar.gz", context.cacheDir)
        val expected = spec.expected(context)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            archive.outputStream().use { output ->
                val buffer = ByteArray(65536)
                for (part in spec.parts(context)) context.assets.open("runtime/$part").use { input ->
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                }
            }
            check(hex(digest.digest()) == expected) { "${spec.title}资源校验失败" }
            val log = File(context.cacheDir, "${spec.id}-install.log")
            val process = ProcessBuilder("/system/bin/tar", "-xzf", archive.path, "-C", stage.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                check(process.waitFor(spec.timeout, TimeUnit.SECONDS)) { "${spec.title}解包超时" }
                check(process.exitValue() == 0) { "${spec.title}解包失败：${log.textOrEmpty().take(1000)}" }
            } finally { process.destroyForcibly() }
            val checked = integrity(context, spec, stage)
            check(checked.problems == 0) { "${spec.title}解包不完整：${checked.details.joinToString()}" }
            File(stage, ".complete").writeText(expected)
            check(marker(spec, stage, expected)) { "组件完整标记无效" }
            deleteTree(previous)
            if (target.exists()) check(target.renameTo(previous)) { "无法替换当前组件" }
            if (!stage.renameTo(target)) {
                if (previous.exists()) check(previous.renameTo(target)) { "提交失败且恢复失败，请重新修复" }
                error("组件提交失败，已保留原组件")
            }
            cleanup(context, spec)
            return target
        } finally { archive.delete(); deleteTree(stage) }
    }
    private fun cleanup(context: Context, spec: BundleSpec) {
        val target = spec.target(context)
        deleteTree(File(target.parentFile, target.name + ".previous"))
        deleteTree(File(target.parentFile, target.name + ".staging"))
        legacy(spec, context).forEach(::deleteTree)
    }
    data class Integrity(val problems: Int, val details: List<String>)
    fun integrity(context: Context, spec: BundleSpec, target: File = spec.target(context)): Integrity {
        if (Files.isSymbolicLink(target.toPath())) return Integrity(1, listOf("组件目录为符号链接"))
        val reference = context.assets.open("runtime/${spec.asset}.files.json").bufferedReader().use { JSONObject(it.readText()) }
        check(reference.getString("archive_sha256") == spec.expected(context)) { "组件校验清单不匹配" }
        val files = reference.getJSONArray("files"); var count = 0; val details = mutableListOf<String>()
        for (index in 0 until files.length()) {
            val entry = files.getJSONObject(index); val path = entry.getString("path")
            require(path.isNotBlank() && !File(path).isAbsolute && ".." !in path.split('/'))
            val file = File(target, path)
            val valid = runCatching {
                val parent = file.parentFile!!.canonicalFile
                check(parent == target.canonicalFile || parent.path.startsWith(target.canonicalPath + File.separator))
                if (entry.has("link")) Files.isSymbolicLink(file.toPath()) && Files.readSymbolicLink(file.toPath()).toString() == entry.getString("link")
                else !Files.isSymbolicLink(file.toPath()) && file.canonicalPath.startsWith(target.canonicalPath + File.separator) &&
                    file.isFile && (!entry.getBoolean("executable") || file.canExecute()) && sha(file) == entry.getString("sha256")
            }.getOrDefault(false)
            if (!valid) { count++; if (details.size < 12) details += path }
        }
        return Integrity(count, details)
    }
    fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun size(file: File): Long {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return 0
        var total = 0L
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size(); return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(path: java.nio.file.Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE
        })
        return total
    }
    fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(path); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(path: java.nio.file.Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error; Files.delete(path); return FileVisitResult.CONTINUE
            }
        })
    }
}
