package ai.capri.app

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** APK-owned Alpine wheel/library channel; never modifies the base rootfs or user venvs. */
internal object NativePythonPackages {
    @Synchronized fun prepare(context: Context): File {
        val expected = context.assets.open("runtime/python-packages.sha256").bufferedReader().use { it.readText().trim() }
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "Python原生包清单无效" }
        val parent = File(context.filesDir, "runtime/python-packages").apply { mkdirs() }
        val target = File(parent, expected)
        fun complete(folder: File) = File(folder, ".complete").readTextOrEmpty() == expected &&
            listOf("wheels", "manifest.json", "usr/lib/libopenblas.so.3")
                .all { File(folder, it).exists() }
        if (complete(target)) return target
        check(!target.exists()) { "Python原生包文件损坏，请先诊断；不自动覆盖已有版本" }
        val stage = File(parent, "$expected.staging")
        stage.deleteRecursively() // Only this install's private staging directory, never the active version.
        check(stage.mkdirs()) { "无法创建Python原生包安装目录" }
        val archive = File.createTempFile("capri-python-", ".tar.gz", context.cacheDir)
        val log = File(context.cacheDir, "python-packages-install.log")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("runtime/python-packages.bundle").use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Python原生包包校验失败" }
            val process = ProcessBuilder("/system/bin/tar", "-xzf", archive.path, "-C", stage.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "Python原生包解包超时" }
                check(process.exitValue() == 0) { "Python原生包解包失败：${log.readTextOrEmpty().take(1500)}" }
            } finally { process.destroyForcibly() }
            File(stage, ".complete").writeText(expected)
            check(complete(stage)) { "Python原生包包不完整" }
            check(stage.renameTo(target)) { "Python原生包安装提交失败" }
            return target
        } finally { archive.delete(); stage.deleteRecursively() }
    }
    private fun File.readTextOrEmpty() = if (isFile) readText() else ""
}
