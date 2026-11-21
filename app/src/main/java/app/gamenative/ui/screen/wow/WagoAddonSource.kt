package app.gamenative.ui.screen.wow

import app.gamenative.utils.Net
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the public Wago Addons catalogue for the WoW Forever flavor. Wago has no public search API, so the
 * server-rendered pages are read instead: the page's Inertia payload carries the add-on cards and the latest
 * per-flavor release, including a short-lived download link.
 */
internal object WagoAddonSource {
    private const val BASE = "https://addons.wago.io"
    private const val GAME_VERSION = "forever"

    private val http: OkHttpClient by lazy {
        Net.http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class SearchResult(
        val name: String,
        val slug: String,
        val summary: String,
        val author: String,
        val updated: String,
    )

    data class SearchPage(
        val results: List<SearchResult>,
        val page: Int,
        val lastPage: Int,
    )

    data class Release(
        val version: String,
        val downloadUrl: String,
        val patch: String?,
    )

    data class Addon(
        val name: String,
        val slug: String,
        val summary: String,
        val author: String,
        val website: String,
        val release: Release?,
    )

    suspend fun search(query: String, page: Int = 1): SearchPage = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE/?search=$encoded&game_version=$GAME_VERSION&page=$page"
        parseSearch(fetchText(url))
    }

    suspend fun addon(slug: String): Addon = withContext(Dispatchers.IO) {
        val path = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
        parseAddon(fetchText("$BASE/addons/$path?game_version=$GAME_VERSION"))
            ?: throw IOException("Wago 没有返回插件信息")
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WoWForeverAndroid/2.4 (+https://github.com/hyylanlian/wow-forever-android)")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Wago HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Wago 返回了空内容")
        }
    }

    internal fun parseSearch(html: String): SearchPage {
        val props = parseInertiaProps(html)
        val addons = props.optJSONObject("addons")
        val page = addons?.optInt("current_page", 1) ?: 1
        val lastPage = addons?.optInt("last_page", page) ?: page
        val data = addons?.optJSONArray("data") ?: JSONArray()
        val results = ArrayList<SearchResult>(data.length())
        for (i in 0 until data.length()) {
            parseCard(data.optString(i))?.let(results::add)
        }
        return SearchPage(results, page, lastPage)
    }

    internal fun parseAddon(html: String): Addon? {
        val props = parseInertiaProps(html)
        val addon = props.optJSONObject("addon") ?: return null
        val metadata = props.optJSONObject("metadata")
        val release = metadata
            ?.optJSONObject("recent_releases")
            ?.optJSONObject(GAME_VERSION)
            ?.let { entry ->
                val version = entry.optString("label").takeIf { it.isNotBlank() }
                val url = entry.optString("download_link").takeIf { it.isNotBlank() }
                if (version != null && url != null) {
                    Release(version, url, entry.optString("supported_forever_patch").takeIf { it.isNotBlank() })
                } else {
                    null
                }
            }
        return Addon(
            name = addon.optString("display_name").ifBlank { addon.optString("slug") },
            slug = addon.optString("slug"),
            summary = addon.optString("summary"),
            author = authorOf(metadata),
            website = addon.optString("website"),
            release = release,
        )
    }

    private fun authorOf(metadata: JSONObject?): String {
        val owner = metadata?.optString("owner").orEmpty()
        if (owner.isNotBlank()) return owner
        return metadata?.optJSONArray("developers")?.optJSONObject(0)?.optString("name").orEmpty()
    }

    private fun parseCard(card: String): SearchResult? {
        val slug = CARD_LINK.find(card)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
        val name = H3.find(card)?.groupValues?.get(1)?.let(::stripHtml)?.takeIf { it.isNotBlank() } ?: slug
        val summary = P.find(card)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
        val author = AUTHOR.find(card)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
        val updated = UPDATED.find(card)?.groupValues?.get(1)?.let(::stripHtml).orEmpty()
        return SearchResult(name, slug, summary, author, updated)
    }

    private fun parseInertiaProps(html: String): JSONObject {
        val encoded = DATA_PAGE.find(html)?.groupValues?.get(1)
            ?: throw IOException("Wago 页面格式已变化，找不到目录数据")
        return runCatching { JSONObject(decodeEntities(encoded)).optJSONObject("props") }
            .getOrNull()
            ?: throw IOException("Wago 页面格式已变化，目录数据无法解析")
    }

    // The Inertia payload is an HTML attribute: quotes, angle brackets and ampersands arrive entity-encoded.
    internal fun decodeEntities(value: String): String = ENTITY.replace(value) { match ->
        val decimal = match.groupValues[1]
        val hex = match.groupValues[2]
        val named = match.groupValues[3]
        when {
            decimal.isNotEmpty() -> codePoint(decimal.toIntOrNull())
            hex.isNotEmpty() -> codePoint(hex.toIntOrNull(16))
            else -> when (named.lowercase()) {
                "quot" -> "\""
                "apos" -> "'"
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "nbsp" -> " "
                else -> match.value
            }
        }
    }

    private fun codePoint(value: Int?): String =
        if (value != null && Character.isValidCodePoint(value)) String(Character.toChars(value)) else ""

    private fun stripHtml(value: String): String =
        value.replace(TAG, "").let(::decodeEntities).trim()

    private val DATA_PAGE = Regex("""data-page="([^"]*)"""")
    private val CARD_LINK = Regex("""href="[^"]*/addons/([^"?#/]+)"""")
    private val H3 = Regex("""<h3[^>]*>(.*?)</h3>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val P = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val AUTHOR = Regex("""<strong>\s*Author:\s*</strong>\s*([^<]+)""", RegexOption.IGNORE_CASE)
    private val UPDATED = Regex("""<strong>\s*Updated:\s*</strong>\s*([^<]+)""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val ENTITY = Regex("""&(?:#(\d+)|#x([0-9a-fA-F]+)|([a-zA-Z]+));""")
}
