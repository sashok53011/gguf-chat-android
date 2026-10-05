package com.devhorizon.online.ggufchat.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * One downloadable GGUF file found on Hugging Face.
 */
data class GgufSearchResult(
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloads: Int,
    val url: String
)

/**
 * Searches Hugging Face for GGUF models by keyword.
 */
object HuggingFaceClient {
    private const val BASE = "https://huggingface.co"
    private const val USER_AGENT = "GGUF-Chat-Android"
    private const val REPO_LIMIT = 60
    private const val MAX_CONCURRENT_REQUESTS = 6

    /**
     * Suspends while searching. Repositories are scanned in parallel.
     *
     * Results are grouped by repository popularity (most downloaded first) and
     * within each repository listed by size ascending. A plain global
     * size-ascending sort used to hide the useful quants (Q4/Q5) behind
     * hundreds of tiny files once the result list was truncated.
     *
     * @param maxFileSizeBytes 0 or less = no size limit
     */
    suspend fun searchGgufModels(
        query: String,
        maxFileSizeBytes: Long,
        maxResults: Int = 500,
        includeProjectors: Boolean = false
    ): List<GgufSearchResult> = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()

        val searchUrl = "$BASE/api/models?search=${URLEncoder.encode(q, "UTF-8")}" +
                "&filter=gguf&sort=downloads&direction=-1&limit=$REPO_LIMIT"
        val reposJson = httpGetJsonArray(searchUrl) ?: return@coroutineScope emptyList()

        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
        val deferreds = (0 until reposJson.length()).map { i ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    collectRepoFiles(reposJson.optJSONObject(i), maxFileSizeBytes, includeProjectors)
                }
            }
        }
        deferreds.awaitAll()
            .flatten()
            .sortedWith(
                compareByDescending<GgufSearchResult> { it.downloads }
                    .thenBy { it.sizeBytes }
            )
            .take(maxResults)
    }

    private fun collectRepoFiles(repo: JSONObject?, maxFileSizeBytes: Long, includeProjectors: Boolean): List<GgufSearchResult> {
        repo ?: return emptyList()
        val repoId = repo.optString("id")
        if (repoId.isEmpty()) return emptyList()
        val downloads = repo.optInt("downloads", 0)

        // Skip repos declaring architectures this llama.cpp build cannot run
        val tags = buildList {
            val tagsArr = repo.optJSONArray("tags")
            if (tagsArr != null) for (k in 0 until tagsArr.length()) add(tagsArr.optString(k))
        }
        if (ModelCompatibility.repoHasUnsupportedArchTag(tags)) return emptyList()

        val treeUrl = "$BASE/api/models/${encodePath(repoId)}/tree/main?recursive=true"
        val tree = httpGetJsonArray(treeUrl) ?: return emptyList()

        val found = mutableListOf<GgufSearchResult>()
        for (j in 0 until tree.length()) {
            val entry = tree.optJSONObject(j) ?: continue
            val path = entry.optString("path")
            if (!path.endsWith(".gguf", ignoreCase = true)) continue

            // Skip mmproj/lora/adapter/vocab files and split model parts.
            // Projectors are kept only in projector-search mode.
            val baseName = path.substringAfterLast('/')
            val isProj = ModelCompatibility.isProjectorFileName(baseName)
            if (isProj) {
                if (!includeProjectors) continue
            } else if (!ModelCompatibility.isModelFileName(baseName)) {
                continue
            }

            var size = entry.optLong("size", -1L)
            if (size <= 0) size = entry.optJSONObject("lfs")?.optLong("size", -1L) ?: -1L
            if (size <= 0) continue
            if (maxFileSizeBytes > 0 && size > maxFileSizeBytes) continue

            found.add(
                GgufSearchResult(
                    repoId = repoId,
                    fileName = path.substringAfterLast('/'),
                    sizeBytes = size,
                    downloads = downloads,
                    url = "$BASE/${encodePath(repoId)}/resolve/main/${encodePath(path)}"
                )
            )
        }
        return found
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun httpGetJsonArray(urlString: String): JSONArray? {
        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONArray(text)
        } catch (_: Exception) {
            null
        }
    }
}
