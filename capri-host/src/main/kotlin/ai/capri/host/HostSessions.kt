package ai.capri.host

import ai.capri.core.KernelSchedule
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal class HostSessions(private val root: Path, legacy: Path?) : AutoCloseable {
    private val sessions = root.resolve("sessions")
    private val owner: FileChannel

    init {
        Files.createDirectories(sessions, privateDirectory)
        owner = FileChannel.open(root.resolve("host.lock"), setOf(CREATE, WRITE), privateFile)
        try {
            check(owner.tryLock() != null) { "The Capri data directory is already in use." }
            val initial = directory(DEFAULT)
            if (Files.notExists(initial, NOFOLLOW_LINKS)) {
                val history = legacy?.takeIf(Files::isRegularFile)?.let { JSONArray(Files.readString(it)) } ?: JSONArray()
                create(DEFAULT, "默认对话", history)
            }
        } catch (error: Exception) {
            owner.close()
            throw error
        }
    }

    override fun close() = owner.close()

    @Synchronized fun list(): JSONArray = JSONArray(Files.list(sessions).use { paths ->
        paths.filter { valid(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS) }
            .map { path ->
                val metadata = JSONObject(Files.readString(path.resolve("session.json")))
                metadata.put("updated_at", Files.getLastModifiedTime(path.resolve("messages.json")).toMillis())
            }.toList().sortedByDescending { it.getLong("updated_at") }
    })

    @Synchronized fun create(title: String): JSONObject {
        require(title.isNotBlank() && title.length <= 80) { "Invalid session title." }
        check(list().length() < 100) { "Session limit reached." }
        return create(UUID.randomUUID().toString(), title, JSONArray())
    }

    @Synchronized fun rename(id: String, title: String): JSONObject? {
        if (!exists(id)) return null
        val path = directory(id).resolve("session.json")
        val metadata = JSONObject(Files.readString(path)).put("title", title)
        atomicWrite(path, metadata.toString())
        return metadata
    }

    @Synchronized fun delete(id: String) {
        val directory = directory(id)
        if (jsonObjects(readReminders(directory.resolve("reminders.json"))).any { it.optString("state") == "running" })
            throw WorkspaceFailure("chat_busy", "A scheduled report is running in this session.")
        val removed = sessions.resolve(".deleted-${UUID.randomUUID()}")
        Files.move(directory, removed, StandardCopyOption.ATOMIC_MOVE)
        try {
            if (id == DEFAULT) create(DEFAULT, "默认对话", JSONArray())
        } catch (failure: Exception) {
            Files.move(removed, directory, StandardCopyOption.ATOMIC_MOVE)
            throw failure
        }
        Files.walk(removed).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    fun exists(id: String): Boolean =
        valid(id) && Files.isDirectory(directory(id), NOFOLLOW_LINKS)

    fun rulesPath(id: String?): Path =
        if (id == null) root.resolve("user-rules.md") else directory(id).resolve("rules.md")

    @Synchronized fun saveRules(id: String, scope: String, content: String): Boolean {
        if (!exists(id)) return false
        atomicWrite(rulesPath(if (scope == "user") null else id), content)
        return true
    }

    fun history(id: String): JSONArray =
        JSONArray(Files.readString(directory(id).resolve("messages.json")))

    fun save(id: String, history: JSONArray) {
        atomicWrite(directory(id).resolve("messages.json"), history.toString())
    }

    fun readingPosition(id: String): JSONObject? {
        val path = directory(id).resolve("reading-position.json")
        return if (Files.isRegularFile(path, NOFOLLOW_LINKS)) JSONObject(Files.readString(path)) else null
    }

    fun saveReadingPosition(id: String, position: JSONObject): JSONObject {
        val key = position.opt("key") as? String
        val offset = position.opt("offset") as? Number
        val saved = if (position.opt("bottom") == true) JSONObject().put("bottom", true)
        else {
            val displacement = offset?.toDouble()
            require(key != null && (artifactId(key) || key.toIntOrNull()?.let { it in 0..1000 } == true) &&
                displacement != null && displacement in -1_000_000.0..1_000_000.0)
            JSONObject().put("key", key).put("offset", displacement)
        }
        saved.put("updatedAt", System.currentTimeMillis())
        atomicWrite(directory(id).resolve("reading-position.json"), saved.toString())
        return saved
    }

    fun latestTurn(id: String): JSONObject? {
        val path = directory(id).resolve("latest-turn.json")
        return if (Files.isRegularFile(path, NOFOLLOW_LINKS)) JSONObject(Files.readString(path)) else null
    }

    fun saveTurn(id: String, turn: JSONObject) {
        atomicWrite(directory(id).resolve("latest-turn.json"), turn.toString())
    }

    fun childPlan(id: String, planId: String): JSONObject? {
        if (!artifactId(planId)) return null
        val path = directory(id).resolve("run/plan-$planId.json")
        return if (Files.isRegularFile(path, NOFOLLOW_LINKS)) JSONObject(Files.readString(path)) else null
    }

    fun childPlans(id: String): List<JSONObject> =
        childPlanPaths(id).map { JSONObject(Files.readString(it)) }

    @Synchronized fun saveChildPlan(id: String, receipt: JSONObject) {
        val planId = receipt.getString("planId")
        require(artifactId(planId))
        val target = directory(id).resolve("run/plan-$planId.json")
        atomicWrite(target, receipt.toString())
        childPlanPaths(id).filter { it != target }.drop(31).forEach(Files::deleteIfExists)
    }

    private fun childPlanPaths(id: String): List<Path> = Files.list(directory(id).resolve("run")).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) &&
            it.fileName.toString().startsWith("plan-") &&
            artifactId(it.fileName.toString().removePrefix("plan-").removeSuffix(".json")) }
            .toList().sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
    }

    @Synchronized fun archiveTurn(id: String, turn: JSONObject) {
        val turnId = turn.getString("id")
        require(artifactId(turnId))
        val run = directory(id).resolve("run")
        val archive = JSONObject().put("id", turnId).put("state", turn.getString("state"))
            .put("result", turn.getJSONObject("result"))
        val evidence = jsonObjects(turn.optJSONArray("messages") ?: JSONArray())
            .filter { it.optString("role") == "tool" }
        archive.put("evidence", JSONArray(evidence))
        atomicWrite(run.resolve("turn-$turnId.json"), archive.toString())
        Files.list(run).use { entries ->
            entries.filter { it.fileName.toString().startsWith("turn-") &&
                artifactId(it.fileName.toString().removePrefix("turn-").removeSuffix(".json")) &&
                Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .toList().sortedByDescending { Files.getLastModifiedTime(it).toMillis() }.drop(32)
                .forEach(Files::deleteIfExists)
        }
    }

    fun archivedTurn(id: String, turnId: String): JSONObject? {
        if (!artifactId(turnId)) return null
        val path = directory(id).resolve("run/turn-$turnId.json")
        return if (Files.isRegularFile(path, NOFOLLOW_LINKS)) JSONObject(Files.readString(path)) else null
    }

    @Synchronized fun reminders(id: String, args: JSONObject): JSONObject {
        val path = directory(id).resolve("reminders.json")
        val jobs = readReminders(path)
        when (args.getString("action")) {
            "list" -> return JSONObject().put("jobs", jobs)
            "create" -> {
                val label = args.optString("label").trim()
                val due = args.optLong("dueAt")
                val repeat = args.optInt("repeatMinutes")
                val kind = args.optString("kind", "reminder")
                val now = System.currentTimeMillis()
                if (label.isEmpty() || due !in (now + 1000)..(now + 366L * 24 * 60 * 60 * 1000))
                    throw WorkspaceFailure("invalid_reminder", "Provide a label and a due time within the next year.")
                if (jobs.length() >= 50) throw WorkspaceFailure("reminder_limit", "This session has reached its reminder limit.")
                val job = JSONObject().put("id", UUID.randomUUID().toString()).put("label", label)
                    .put("nextAt", due).put("repeatMinutes", repeat).put("state", "scheduled").put("receipts", JSONArray())
                    .put("kind", kind)
                if (kind == "document_report") {
                    val paths = args.optJSONArray("paths")
                    if (paths == null || paths.length() == 0)
                        throw WorkspaceFailure("missing_report_sources", "Name the workspace text documents for this report.")
                    val workspace = workspace(id)
                    for (i in 0 until paths.length()) workspace.delegationSource(paths.getString(i))
                    job.put("paths", paths)
                }
                jobs.put(job)
                atomicWrite(path, jobs.toString())
                return job
            }
            "cancel", "pause", "resume" -> {
                val job = jsonObjects(jobs).firstOrNull { it.getString("id") == args.optString("id") }
                    ?: throw WorkspaceFailure("reminder_not_found", "No reminder has that ID in this session.")
                when (args.getString("action")) {
                    "cancel" -> job.put("state", "cancelled")
                    "pause" -> {
                        if (job.getString("state") != "scheduled")
                            throw WorkspaceFailure("job_not_scheduled", "Only a scheduled job can be paused.")
                        job.put("state", "paused")
                    }
                    "resume" -> {
                        if (job.getString("state") != "paused")
                            throw WorkspaceFailure("job_not_paused", "Only a paused job can be resumed.")
                        job.put("state", "scheduled").put("nextAt", maxOf(job.getLong("nextAt"), System.currentTimeMillis() + 1000))
                    }
                }
                atomicWrite(path, jobs.toString())
                return job
            }
            else -> throw WorkspaceFailure("invalid_reminder_action", "Choose list, create, pause, resume, or cancel.")
        }
    }

    @Synchronized fun tickReminders(now: Long = System.currentTimeMillis()): Pair<String, JSONObject>? {
        var claimed: Pair<String, JSONObject>? = null
        Files.list(sessions).use { directories ->
            directories.filter { valid(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { directory ->
                val path = directory.resolve("reminders.json")
                val jobs = readReminders(path)
                var changed = false
                for (job in jsonObjects(jobs)) {
                    val due = job.getLong("nextAt")
                    if (job.getString("state") != "scheduled" || due > now) continue
                    if (job.optString("kind") == "document_report") {
                        if (claimed != null) continue
                        job.put("state", "running")
                        claimed = directory.fileName.toString() to JSONObject(job.toString())
                    } else settleJob(job, now, "due", null)
                    changed = true
                }
                if (changed) atomicWrite(path, jobs.toString())
            }
        }
        return claimed
    }

    @Synchronized fun finishReport(id: String, jobId: String, outcome: String, report: JSONObject?) {
        val path = directory(id).resolve("reminders.json")
        val jobs = readReminders(path)
        val job = jsonObjects(jobs).firstOrNull { it.getString("id") == jobId && it.getString("state") == "running" } ?: return
        settleJob(job, System.currentTimeMillis(), outcome, report)
        atomicWrite(path, jobs.toString())
    }

    @Synchronized fun recoverReports() {
        Files.list(sessions).use { directories ->
            directories.filter { valid(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { directory ->
                val path = directory.resolve("reminders.json")
                val jobs = readReminders(path)
                val interrupted = jsonObjects(jobs).filter { it.optString("state") == "running" }
                if (interrupted.isNotEmpty()) {
                    interrupted.forEach { settleJob(it, System.currentTimeMillis(), "interrupted", null) }
                    atomicWrite(path, jobs.toString())
                }
            }
        }
    }

    private fun settleJob(job: JSONObject, now: Long, state: String, report: JSONObject?) {
        val receipts = job.getJSONArray("receipts")
        if (receipts.length() >= 10) receipts.remove(0)
        receipts.put(JSONObject().put("scheduledAt", job.getLong("nextAt")).put("firedAt", now)
            .put("outcome", state).putOpt("report", report))
        val repeat = job.getInt("repeatMinutes")
        if (repeat > 0) job.put("nextAt", KernelSchedule.nextInterval(now, repeat * 60_000L)).put("state", "scheduled")
        else job.put("state", state)
    }

    private fun readReminders(path: Path): JSONArray =
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) JSONArray(Files.readString(path)) else JSONArray()

    fun workspace(id: String): WorkspaceTools = WorkspaceTools(directory(id).resolve("workspace")) { path, size ->
        publish(id, path, size)
    }

    @Synchronized fun workspaceInventory(id: String): JSONObject {
        val workspace = workspace(id).inventory()
        val published = jsonObjects(attachmentLinks(id)).map { it.getString("path") }.toSet()
        for (file in jsonObjects(workspace.getJSONArray("files"))) {
            file.put("published", file.getString("path") in published)
        }
        return workspace.put("legacy", JSONArray(legacyArtifacts(id)))
    }

    @Synchronized fun records(id: String, kind: String, args: JSONObject): JSONObject {
        val path = recordsPath(id, kind)
        val records = readRecords(path)
        if (args.getString("action") == "search") {
            val query = args.optString("query").trim()
            val matches = jsonObjects(records).asReversed().map { entry ->
                entry to if (query.isEmpty()) 1 else lexicalMemoryScore(query, entry)
            }.filter { it.second > 0 }.sortedByDescending { it.second }.take(6).map { it.first }
            return memoryResults(withSourceState(id, matches))
        }
        val title = args.optString("title").trim()
        val content = args.optString("content").trim()
        val source = args.optString("source").trim()
        if (title.isEmpty() || content.isEmpty() || source.isEmpty())
            throw WorkspaceFailure("missing_record_fields", "Saving a record requires a title, content, and source.")
        val sourcePath = args.optString("sourcePath").trim().takeIf(String::isNotEmpty)
        val sourceRevision = sourcePath?.let { workspace(id).sourceRevision(it) }
        for (i in 0 until records.length()) {
            val existing = records.getJSONObject(i)
            if (existing.getString("title") == title && existing.getString("content") == content &&
                existing.getString("source") == source && (existing.opt("sourcePath") as? String) == sourcePath &&
                (existing.opt("sourceRevision") as? String) == sourceRevision)
                return JSONObject().put("id", existing.getString("id")).put("created", false)
        }
        if (records.length() >= 100) throw WorkspaceFailure("record_limit", "This store has reached its record limit.")
        val record = JSONObject().put("id", UUID.randomUUID().toString()).put("title", title)
            .put("content", content).put("source", source).put("createdAt", System.currentTimeMillis())
            .putOpt("sourcePath", sourcePath).putOpt("sourceRevision", sourceRevision)
        records.put(record)
        atomicWrite(path, records.toString())
        return JSONObject().put("id", record.getString("id")).put("created", true)
    }

    @Synchronized fun recordSnapshot(id: String, kind: String): List<JSONObject> =
        withSourceState(id, jsonObjects(readRecords(recordsPath(id, kind))).asReversed())

    private fun withSourceState(id: String, entries: List<JSONObject>): List<JSONObject> {
        val workspace = workspace(id)
        val revisions = mutableMapOf<String, String?>()
        return entries.map { entry ->
            val path = entry.opt("sourcePath") as? String ?: return@map entry
            val revision = if (revisions.containsKey(path)) revisions[path] else {
                val current = try { workspace.sourceRevision(path) }
                catch (_: java.io.IOException) { null }
                catch (_: java.nio.file.InvalidPathException) { null }
                revisions[path] = current
                current
            }
            JSONObject(entry.toString()).put("sourceState", when (revision) {
                null -> "unavailable"
                entry.optString("sourceRevision") -> "current"
                else -> "changed"
            })
        }
    }

    private fun recordsPath(id: String, kind: String): Path =
        when (kind) {
            "knowledge", "task_experience" -> directory(id).resolve("$kind.json")
            "personal_memory" -> root.resolve("personal-memory.json")
            else -> error("Unknown record store.")
        }

    private fun readRecords(path: Path): JSONArray =
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) JSONArray(Files.readString(path)) else JSONArray()

    fun artifacts(id: String): JSONArray {
        val workspace = workspace(id)
        val links = attachmentLinks(id)
        val current = (0 until links.length()).mapNotNull { index ->
            val item = links.getJSONObject(index)
            val size = try { workspace.attachmentSize(item.getString("path")) } catch (_: java.io.IOException) { null }
            if (size == null || size > WorkspaceTools.maxBytes) null else item.put("size", size)
        }
        val legacy = legacyArtifacts(id)
        return JSONArray((current + legacy).sortedByDescending { it.optLong("createdAt") })
    }

    private fun legacyArtifacts(id: String): List<JSONObject> {
        val root = directory(id).resolve("artifacts")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return emptyList()
        val list = Files.list(root).use { paths ->
            paths.filter { artifactId(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS) }
                .map { JSONObject(Files.readString(it.resolve("metadata.json"))) }
                .toList()
        }
        return buildList {
            addAll(list)
            val path = root.resolve("conversation.md")
            if (Files.isRegularFile(path, NOFOLLOW_LINKS)) add(JSONObject().put("id", "conversation")
                .put("name", "conversation.md").put("path", "artifacts/conversation.md").put("size", Files.size(path)))
        }
    }

    private fun attachmentLinks(id: String): JSONArray {
        val index = directory(id).resolve("attachments.json")
        return if (Files.isRegularFile(index, NOFOLLOW_LINKS)) JSONArray(Files.readString(index)) else JSONArray()
    }

    @Synchronized private fun publish(id: String, path: String, size: Long): JSONObject {
        val links = attachmentLinks(id)
        for (i in 0 until links.length()) {
            val existing = links.getJSONObject(i)
            if (existing.getString("path") == path) return existing.put("size", size)
        }
        if (links.length() >= 100) throw WorkspaceFailure("artifact_limit", "This session has reached its attachment limit.")
        val metadata = JSONObject().put("id", UUID.randomUUID().toString())
            .put("name", path.substringAfterLast('/')).put("path", path).put("createdAt", System.currentTimeMillis())
        links.put(metadata)
        atomicWrite(directory(id).resolve("attachments.json"), links.toString())
        return metadata.put("size", size)
    }

    fun artifact(id: String, artifact: String): Pair<String, ByteArray>? {
        if (artifactId(artifact)) {
            val links = attachmentLinks(id)
            for (i in 0 until links.length()) {
                val item = links.getJSONObject(i)
                if (item.getString("id") != artifact) continue
                val content = try { workspace(id).attachmentBytes(item.getString("path")) } catch (_: java.io.IOException) { return null }
                return item.getString("name") to content
            }
        }
        val root = directory(id).resolve("artifacts")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return null
        if (artifact == "conversation") {
            val path = root.resolve("conversation.md")
            return if (Files.isRegularFile(path, NOFOLLOW_LINKS)) "conversation.md" to Files.readAllBytes(path) else null
        }
        if (!artifactId(artifact)) return null
        val snapshot = root.resolve(artifact)
        val content = snapshot.resolve("content")
        if (!Files.isDirectory(snapshot, NOFOLLOW_LINKS) || !Files.isRegularFile(content, NOFOLLOW_LINKS)) return null
        val metadata = JSONObject(Files.readString(snapshot.resolve("metadata.json")))
        return metadata.getString("name") to Files.readAllBytes(content)
    }

    fun export(id: String): String {
        val history = history(id)
        val markdown = buildString {
            appendLine("# Capri 对话记录")
            for (i in 0 until history.length()) {
                val message = history.getJSONObject(i)
                appendLine()
                appendLine(if (message.getString("role") == "user") "## 用户" else "## Capri")
                appendLine()
                appendLine(message.getString("content"))
            }
        }
        return markdown
    }

    private fun directory(id: String): Path {
        require(valid(id)) { "Invalid session ID." }
        return sessions.resolve(id)
    }

    private fun create(id: String, title: String, history: JSONArray): JSONObject {
        val stage = Files.createTempDirectory(sessions, ".session-", privateDirectory)
        try {
            val metadata = JSONObject().put("id", id).put("title", title)
            atomicWrite(stage.resolve("session.json"), metadata.toString())
            atomicWrite(stage.resolve("messages.json"), history.toString())
            for (name in listOf("workspace", "run")) Files.createDirectory(stage.resolve(name), privateDirectory)
            Files.move(stage, directory(id), StandardCopyOption.ATOMIC_MOVE)
            return metadata
        } finally {
            if (Files.exists(stage, NOFOLLOW_LINKS)) Files.walk(stage).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    companion object {
        const val DEFAULT = "default"
        private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        private fun valid(id: String): Boolean =
            id == DEFAULT || artifactId(id)

        private fun artifactId(id: String): Boolean =
            id.length == 36 && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

        private fun atomicWrite(target: Path, value: String) {
            val temporary = Files.createTempFile(target.parent, ".write-", ".tmp", privateFile)
            try {
                Files.writeString(temporary, value)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
