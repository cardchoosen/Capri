package ai.capri.app

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** APK-owned, versioned tools. Never unpack over a user's base rootfs or session files. */
internal object DevelopmentTools {
    @Synchronized fun prepare(context: Context): File {
        val expected = context.assets.open("runtime/devtools.sha256").bufferedReader().use { it.readText().trim() }
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "开发工具清单无效" }
        val parent = File(context.filesDir, "runtime/tools").apply { mkdirs() }
        val target = File(parent, expected)
        fun complete(folder: File) = File(folder, ".complete").readTextOrEmpty() == expected &&
            listOf("usr/bin/git", "usr/bin/curl", "usr/bin/ssh", "usr/bin/sftp", "usr/bin/scp", "usr/bin/ssh-keygen", "usr/libexec/git-core/git-remote-https", "manifest.json")
                .all { File(folder, it).exists() }
        if (complete(target)) return target
        check(!target.exists()) { "开发工具文件损坏，请先诊断；不自动覆盖已有版本" }
        val stage = File(parent, "$expected.staging")
        stage.deleteRecursively() // Only this install's private staging directory, never the active version.
        check(stage.mkdirs()) { "无法创建开发工具安装目录" }
        val archive = File.createTempFile("capri-tools-", ".tar.gz", context.cacheDir)
        val log = File(context.cacheDir, "devtools-install.log")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("runtime/devtools.bundle").use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "开发工具包校验失败" }
            val process = ProcessBuilder("/system/bin/tar", "-xzf", archive.path, "-C", stage.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "开发工具解包超时" }
                check(process.exitValue() == 0) { "开发工具解包失败：${log.readTextOrEmpty().take(1500)}" }
            } finally { process.destroyForcibly() }
            File(stage, ".complete").writeText(expected)
            check(complete(stage)) { "开发工具包不完整" }
            check(stage.renameTo(target)) { "开发工具安装提交失败" }
            return target
        } finally { archive.delete(); stage.deleteRecursively() }
    }
    private fun File.readTextOrEmpty() = if (isFile) readText() else ""
}
