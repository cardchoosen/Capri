package ai.capri.host

import java.io.InputStream
import java.net.JarURLConnection
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

internal class KernelRules(private val directory: Path, private val sourceDirectory: Path? = null) {
    data class Rule(val name: String, val content: String, val source: String)

    private val bundled by lazy { bundledRules() }

    init {
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }

    fun load(): List<Rule> {
        val defaults = sourceDirectory?.let { directoryRules(it, "source") } ?: bundled
        val rules = defaults + directoryRules(directory, "local")
        require(rules.sumOf { it.content.toByteArray(Charsets.UTF_8).size } <= 65_536) {
            "Kernel rules exceed the combined 64 KiB limit."
        }
        return rules
    }

    private fun directoryRules(path: Path, source: String): List<Rule> {
        require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "The $source kernel rules directory is unavailable." }
        val files = Files.list(path).use { paths ->
            paths.filter { isRule(it.fileName.toString()) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .limit(65).toList().sortedBy { it.fileName.toString() }
        }
        require(files.size <= 64) { "The $source kernel rules directory exceeds 64 files." }
        return files.mapNotNull { file ->
            Files.newInputStream(file, NOFOLLOW_LINKS).use { read(it, file.fileName.toString(), source) }
        }
    }

    fun prompt(): String = load().joinToString("\n\n") { it.content }

    fun bundledPrompt(): String = bundled.joinToString("\n\n") { it.content }

    private fun bundledRules(): List<Rule> {
        val root = checkNotNull(javaClass.getResource("/kernel-rules")) { "Bundled kernel rules are missing." }
        val names = if (root.protocol == "jar") {
            val connection = (root.openConnection() as JarURLConnection).apply { useCaches = false }
            connection.jarFile.use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("kernel-rules/") }
                    .map { it.name.removePrefix("kernel-rules/") }.filter(::isRule).toList()
            }
        } else {
            Files.list(Path.of(root.toURI())).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .map { it.fileName.toString() }.filter(::isRule).toList()
            }
        }
        return names.sorted().mapNotNull { name ->
            checkNotNull(javaClass.getResourceAsStream("/kernel-rules/$name")).use { read(it, name, "bundled") }
        }
    }

    private fun isRule(name: String): Boolean =
        name.endsWith(".txt", ignoreCase = true) && !name.startsWith('.') && '/' !in name

    private fun read(input: InputStream, name: String, source: String): Rule? {
        val bytes = input.readNBytes(LocalChat.MAX_RULE_BYTES + 1)
        require(bytes.size <= LocalChat.MAX_RULE_BYTES) { "Kernel rule $name exceeds 16 KiB." }
        val content = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().trim()
        return content.takeIf(String::isNotEmpty)?.let { Rule(name, it, source) }
    }
}
