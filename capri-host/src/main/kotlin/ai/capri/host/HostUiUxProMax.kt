package ai.capri.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Runner for the upstream UI UX Pro Max search.py and its read-only CSV catalog. */
internal class HostUiUxProMax(private val root: Path, private val python: String = System.getenv("CAPRI_PYTHON_BIN") ?: "python3") {
    private val script = root.resolve("scripts/search.py")
    private val domains = setOf("style", "color", "chart", "landing", "product", "ux", "typography", "icons", "gsap", "react", "web", "google-fonts")
    private val stacks = setOf("react", "nextjs", "vue", "svelte", "astro", "swiftui", "react-native", "flutter", "nuxtjs", "nuxt-ui", "html-tailwind", "shadcn", "jetpack-compose", "threejs", "angular", "laravel", "javafx", "wpf", "winui", "avalonia", "uno", "uwp")

    companion object {
        private const val RESOURCE_ROOT = "ui-ux-pro-max/"

        fun installBundled(data: Path): Path {
            val loader = HostUiUxProMax::class.java.classLoader
            val manifestBytes = loader.getResourceAsStream(RESOURCE_ROOT + "MANIFEST.txt")?.use { it.readNBytes(65_537) }
                ?: throw IllegalStateException("The bundled UI UX Pro Max manifest is missing.")
            require(manifestBytes.size <= 65_536)
            val entries = manifestBytes.toString(Charsets.UTF_8).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(entries.size in 1..500)
            val marker = loader.getResourceAsStream(RESOURCE_ROOT + "UPSTREAM.txt")?.use { it.readNBytes(4096) }
                ?: throw IllegalStateException("The bundled UI UX Pro Max provenance file is missing.")
            val target = data.toAbsolutePath().resolve("ui-ux-pro-max")
            val markerText = marker.toString(Charsets.UTF_8)
            val commit = Regex("Commit: [a-f0-9]{40}").find(markerText)?.value
                ?: throw IllegalStateException("The bundled UI UX Pro Max commit is invalid.")
            if (Files.isRegularFile(target.resolve("UPSTREAM.txt"), NOFOLLOW_LINKS) &&
                Files.readString(target.resolve("UPSTREAM.txt")).contains(commit))
                return target
            Files.createDirectories(data)
            val staging = Files.createTempDirectory(data, ".ui-ux-pro-max-")
            var total = 0L
            try {
                for (entry in entries) {
                    val relative = Path.of(entry)
                    require(!relative.isAbsolute && relative.none { it.toString() == ".." } && '\\' !in entry)
                    val bytes = loader.getResourceAsStream(RESOURCE_ROOT + entry)?.use { it.readNBytes(10_000_001) }
                        ?: throw IllegalStateException("A bundled UI UX Pro Max file is missing.")
                    require(bytes.size <= 10_000_000)
                    total += bytes.size
                    require(total <= 64L * 1024 * 1024)
                    val destination = staging.resolve(relative).normalize()
                    require(destination.startsWith(staging))
                    Files.createDirectories(destination.parent)
                    Files.write(destination, bytes)
                }
                Files.write(staging.resolve("UPSTREAM.txt"), marker)
                val backup = data.resolve(".ui-ux-pro-max-old-${UUID.randomUUID()}")
                if (Files.exists(target, NOFOLLOW_LINKS)) Files.move(target, backup, ATOMIC_MOVE)
                try {
                    Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (failure: Exception) {
                    if (Files.exists(backup, NOFOLLOW_LINKS)) Files.move(backup, target, ATOMIC_MOVE, REPLACE_EXISTING)
                    throw failure
                }
                if (Files.exists(backup, NOFOLLOW_LINKS)) deleteTree(backup)
                return target
            } finally {
                if (Files.exists(staging, NOFOLLOW_LINKS)) deleteTree(staging)
            }
        }

        private fun deleteTree(root: Path) {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    suspend fun execute(args: JSONObject, workspace: Path): JSONObject = withContext(Dispatchers.IO) {
        val query = args.optString("query").trim()
        if (query.isEmpty() || query.length > 1000) throw WorkspaceFailure("invalid_design_query", "Provide a design query between 1 and 1000 characters.")
        if (!Files.isRegularFile(script, NOFOLLOW_LINKS) || Files.isSymbolicLink(script) ||
            !Files.isDirectory(root.resolve("data"), NOFOLLOW_LINKS))
            throw WorkspaceFailure("uiux_data_unavailable", "The UI UX Pro Max runtime or design catalog is not installed.")
        val argv = mutableListOf(python, script.toString(), query, "--json")
        args.optString("domain").takeIf(String::isNotBlank)?.let {
            if (it !in domains) throw WorkspaceFailure("invalid_design_domain", "Choose a supported UI UX Pro Max search domain.")
            argv.addAll(listOf("--domain", it))
        }
        args.optString("stack").takeIf(String::isNotBlank)?.let {
            if (it !in stacks) throw WorkspaceFailure("invalid_design_stack", "Choose a supported UI UX Pro Max technology stack.")
            argv.addAll(listOf("--stack", it))
        }
        args.optString("rtl").takeIf(String::isNotBlank)?.let {
            if (it !in setOf("all", "full", "partial", "caveats") || args.optBoolean("designSystem") || args.optString("stack").isNotBlank())
                throw WorkspaceFailure("invalid_rtl_filter", "RTL filtering supports search mode in the style or product domains only.")
            argv.addAll(listOf("--rtl", it))
        }
        val maxResults = args.optInt("maxResults", 5).coerceIn(1, 20)
        argv.addAll(listOf("--max-results", maxResults.toString()))
        if (args.optBoolean("designSystem")) argv.add("--design-system")
        if (args.optBoolean("full")) argv.add("--full")
        args.optString("format").takeIf(String::isNotBlank)?.let {
            if (it !in setOf("ascii", "markdown")) throw WorkspaceFailure("invalid_output_format", "Choose ascii or markdown output.")
            argv.addAll(listOf("--format", it))
        }
        listOf("variance" to "--variance", "motion" to "--motion", "density" to "--density").forEach { (key, flag) ->
            if (args.has(key)) {
                val value = args.optInt(key, 0)
                if (value !in 1..10 || !args.optBoolean("designSystem"))
                    throw WorkspaceFailure("invalid_design_dial", "Design-system dials must be integers from 1 to 10 and require designSystem.")
                argv.addAll(listOf(flag, value.toString()))
            }
        }
        args.optString("projectName").takeIf(String::isNotBlank)?.let {
            if (it.length > 100) throw WorkspaceFailure("invalid_project_name", "The project name must be at most 100 characters.")
            argv.addAll(listOf("--project-name", it))
        }
        if (args.optBoolean("persist")) {
            if (!args.optBoolean("designSystem")) throw WorkspaceFailure("design_system_required", "Set designSystem before persisting a recommendation.")
            val output = workspace.toAbsolutePath().normalize()
            val designDir = output.resolve("design-system")
            if (Files.isSymbolicLink(designDir)) throw WorkspaceFailure("unsafe_design_output", "The design-system output directory cannot be a symbolic link.")
            if (Files.isDirectory(designDir, NOFOLLOW_LINKS) && Files.walk(designDir).use { paths -> paths.anyMatch(Files::isSymbolicLink) })
                throw WorkspaceFailure("unsafe_design_output", "The design-system output tree cannot contain symbolic links.")
            argv.addAll(listOf("--persist", "--output-dir", output.toString()))
        }
        val process = ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true).apply {
            environment().keys.filter { it.contains(Regex("(?i)(key|token|secret|password|credential)")) }.forEach(environment()::remove)
        }.start()
        val captured = java.io.ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var retained = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val keep = minOf(count, 32_001 - retained)
                    if (keep > 0) { captured.write(buffer, 0, keep); retained += keep }
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            throw WorkspaceFailure("uiux_search_timeout", "The UI UX Pro Max search exceeded 20 seconds.")
        }
        reader.join(1000)
        val output = captured.toString(Charsets.UTF_8)
        if (process.exitValue() != 0) throw WorkspaceFailure("uiux_search_failed", output.take(1000).ifBlank { "The UI UX Pro Max search failed." })
        JSONObject().put("query", query).put("result", output.take(32_000)).put("truncated", output.length > 32_000)
    }
}
