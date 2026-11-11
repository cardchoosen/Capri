package ai.capri.host

import ai.capri.app.knowledge.TextIndex
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

internal fun lexicalMemoryScore(query: String, entry: JSONObject): Int =
    TextIndex.score(query, entry.getString("title")) * 2 + TextIndex.score(query, entry.getString("content")) +
        TextIndex.score(query, entry.getString("source"))

internal fun memoryResults(entries: List<JSONObject>): JSONObject =
    JSONObject().put("records", JSONArray(entries.take(5))).put("truncated", entries.size > 5)

/** Optional semantic ranking for session-scoped notes. Personal memory always stays on the local lexical path. */
internal class HostMemoryRanking(private val endpoint: HttpUrl, private val model: String, private val token: String?) {
    private val cache = LinkedHashMap<String, DoubleArray>(64, .75f, true)

    suspend fun search(query: String, entries: List<JSONObject>): JSONObject {
        val ranked = entries.map { it to lexicalMemoryScore(query, it) }.sortedByDescending { it.second }
        val primary = ranked.take(16)
        val seen = primary.map { it.first.getString("id") }.toSet()
        val candidates = primary + entries.asSequence().filter { it.getString("id") !in seen }.take(8)
            .map { it to lexicalMemoryScore(query, it) }.toList()
        val fallback = memoryResults(ranked.filter { it.second > 0 }.map { it.first }).put("ranking", "lexical")
        if (candidates.isEmpty()) return fallback
        val texts = candidates.map { (entry, _) -> (entry.getString("title") + "\n" + entry.getString("content")).take(1500) }
        val vectors = try {
            val missing = synchronized(cache) { texts.distinct().filterNot(cache::containsKey) }
            val response = embeddings(listOf(query) + missing)
            synchronized(cache) {
                for ((index, text) in missing.withIndex()) cache[text] = response[index + 1]
                while (cache.size > 128) cache.remove(cache.keys.first())
                texts.map { cache[it] } to response[0]
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return fallback
        }
        val maximumLexical = maxOf(1, candidates.maxOf { it.second })
        val matching = candidates.mapIndexed { index, (entry, lexical) ->
            val semantic = vectors.first[index]?.let { cosine(vectors.second, it) } ?: 0.0
            val score = 0.55 * lexical / maximumLexical + 0.45 * semantic.coerceAtLeast(0.0)
            Triple(entry, score, lexical > 0 || semantic > 0.25)
        }.filter { it.third }.sortedByDescending { it.second }.map { it.first }
        return memoryResults(matching).put("ranking", "hybrid")
    }

    private suspend fun embeddings(texts: List<String>): List<DoubleArray> {
        val payload = JSONObject().put("model", model).put("input", JSONArray(texts))
        val request = Request.Builder().url(endpoint).post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (token != null) request.header("Authorization", "Bearer $token")
        return HostIntegrationHttp.execute(request.build()) { response ->
            check(response.isSuccessful)
            val bytes = checkNotNull(response.body).byteStream().readNBytes(1_048_577)
            check(bytes.size <= 1_048_576)
            val data = jsonObjects(JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("data"))
                .associateBy { it.getInt("index") }
            check(data.size == texts.size)
            texts.indices.map { index ->
                val vector = data.getValue(index).getJSONArray("embedding")
                check(vector.length() in 1..4096)
                DoubleArray(vector.length()) { i -> vector.getDouble(i).also { check(it.isFinite()) } }
            }
        }
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var aNorm = 0.0
        var bNorm = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aNorm += a[i] * a[i]
            bNorm += b[i] * b[i]
        }
        val denominator = sqrt(aNorm * bNorm)
        return if (denominator.isFinite() && denominator > 0) (dot / denominator).coerceIn(-1.0, 1.0) else 0.0
    }
}
