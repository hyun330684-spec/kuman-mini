package com.kuman.mini

import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal web search for KUMAN: DuckDuckGo Lite HTML results +
 * Korean Wikipedia fallback. No API key required.
 *
 * Privacy: only the user's query text is sent. Conversation history,
 * memories and personal data are never transmitted.
 */
object WebSearcher {

    private const val TIMEOUT_MS = 8000
    private const val MAX_RESULTS = 3

    /**
     * @return Short Korean summary of top web results, or null on failure.
     */
    fun search(query: String): String? {
        val duck = searchDuckDuckGo(query)
        if (!duck.isNullOrBlank()) return duck
        return searchWikipedia(query)
    }

    // ------------------------------------------------------------------
    // DuckDuckGo Lite (HTML scrape)
    // ------------------------------------------------------------------

    private fun searchDuckDuckGo(query: String): String? = try {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://lite.duckduckgo.com/lite/?q=$q") ?: return null

        // result links: <a rel="nofollow" href="..." class='result-link'>Title</a>
        // snippets:     <td class='result-snippet'>...</td>
        val linkRe = Regex(
            "<a[^>]*class=['\"]result-link['\"][^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        val snippetRe = Regex(
            "<td[^>]*class=['\"]result-snippet['\"][^>]*>(.*?)</td>",
            RegexOption.DOT_MATCHES_ALL
        )

        val titles = linkRe.findAll(body).take(MAX_RESULTS)
            .map { stripHtml(it.groupValues[1]) }.toList()
        val snippets = snippetRe.findAll(body).take(MAX_RESULTS)
            .map { stripHtml(it.groupValues[1]) }.toList()

        if (titles.isEmpty()) return null

        buildString {
            for (i in titles.indices) {
                append("${i + 1}. ${titles[i]}")
                if (i < snippets.size && snippets[i].isNotBlank()) {
                    append(" — ${snippets[i]}")
                }
                append(". ")
            }
        }.trim()
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // Korean Wikipedia (opensearch -> summary)
    // ------------------------------------------------------------------

    private fun searchWikipedia(query: String): String? = try {
        val q = URLEncoder.encode(query, "UTF-8")
        val searchJson = httpGet(
            "https://ko.wikipedia.org/w/api.php?action=opensearch" +
                "&search=$q&limit=1&namespace=0&format=json"
        ) ?: return null

        // opensearch returns a bare JSON array: [query, [titles], [descs], [urls]]
        val arr = org.json.JSONArray(searchJson)
        val titles = arr.optJSONArray(1)
        if (titles == null || titles.length() == 0) return null

        val title = titles.getString(0)
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val summaryJson = httpGet(
            "https://ko.wikipedia.org/api/rest_v1/page/summary/$encodedTitle"
        ) ?: return null

        val obj = JSONObject(summaryJson)
        val extract = obj.optString("extract")
        if (extract.isBlank()) return null

        // Trim to ~300 chars for prompt injection
        if (extract.length > 300) extract.take(300) + "…" else extract
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------

    private fun httpGet(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "kuman-mini/1.0")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        body
    } catch (e: Exception) {
        null
    }

    private fun stripHtml(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\\s+"), " ").trim()
}
