package ai.capri.core

import java.util.UUID

/** Existing revision and legacy-branch namespaces; no new storage or migration. */
object HistoryScope {
    fun parse(key:String):Triple<String,String?,Boolean> {
        val legacy=key.contains(":branch:")
        val parts=key.split(if(legacy) ":branch:" else ":history:")
        require(parts.size in 1..2 && parts[0].matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid history scope." }
        val revision=parts.getOrNull(1)
        require(revision==null || revision!="main" && runCatching { UUID.fromString(revision).toString()==revision }.getOrDefault(false)) { "Invalid history revision." }
        return Triple(parts[0],revision,legacy)
    }
}
