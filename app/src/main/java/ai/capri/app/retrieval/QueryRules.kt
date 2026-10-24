package ai.capri.app.retrieval

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Honor explicit domain constraints even when a public engine ignores them. Coverage is only a heuristic. */
internal object QueryRules {
    fun sites(query: String, hits: List<SearchHit>): List<SearchHit> {
        val positive = Regex("(?i)(?<![\\w-])site:([^\\s()]+)").findAll(query).map { it.groupValues[1].trimEnd('/', '.', ',').lowercase() }.toList()
        val negative = Regex("(?i)(?<!\\w)-site:([^\\s()]+)").findAll(query).map { it.groupValues[1].trimEnd('/', '.', ',').lowercase() }.toList()
        fun matches(url: String, site: String): Boolean {
            val target = url.toHttpUrlOrNull() ?: return false
            val domain = site.substringBefore('/'); val path = site.substringAfter('/', "")
            return (target.host == domain || target.host.endsWith(".$domain")) && (path.isEmpty() || target.encodedPath.startsWith("/$path"))
        }
        return hits.filter { hit -> (positive.isEmpty() || positive.any { matches(hit.url, it) }) && negative.none { matches(hit.url, it) } }
    }
    fun relevant(query: String, hits: List<SearchHit>): Boolean {
        val stripped = query.replace(Regex("(?i)-?(?:site|after|before|lang):[^\\s]+"), " ")
        val stop = setOf("the", "and", "for", "how", "what", "with", "are", "does", "official", "documentation", "please", "search", "about", "官方文档", "官方", "介绍", "搜索", "查询")
        val terms = Regex("[a-z0-9][a-z0-9_.-]{2,}|[\\p{IsHan}]{2,}").findAll(stripped.lowercase()).map { it.value }.filter { it !in stop }.toSet()
        if (terms.isEmpty()) return true
        return hits.any { hit ->
            val text = (hit.title + " " + hit.snippet).lowercase()
            terms.count { text.contains(it) } >= minOf(2, terms.size)
        }
    }
}
