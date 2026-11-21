package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Reads CurseForge project metadata through the public cfwidget mirror, which needs no API key, and downloads files
 * from the Forge CDN. cfwidget filters files by the `version` query, so only builds tagged with the current WoW
 * Forever patch are offered; the parsed file is validated again before it is used.
 */
internal object CurseForgeSource {
    const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    private const val WIDGET_BASE = "https://api.cfwidget.com"
    private const val CDN_BASE = "https://edge.forgecdn.net/files"
    private const val INDEX_ASSET = "curseforge-index.json"
    private const val CACHE_DIR = "wow_curseforge"
    private const val USER_AGENT = "WoWForeverAndroid/2.4 (+https://github.com/hyylanlian/wow-forever-android)"
    private val SLUG = Regex("[a-z0-9][a-z0-9-]{0,99}")
    private val PROJECT_ID = Regex("\\d{1,12}")
    private val PROJECT_URL = Regex(
        """(?:https?://)?(?:www\.)?curseforge\.com/wow/addons/([a-z0-9][a-z0-9-]{0,99})(?:[/?#].*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val NON_CACHE_CHARS = Regex("[^a-z0-9]+")

    data class Ref(val path: String) // "wow/addons/details" or "61284"

    data class ProjectFile(
        val id: Long,
        val name: String,
        val display: String,
        val type: String,
        val size: Long,
        val versions: List<String>,
        val uploadedAt: String,
    )

    data class Project(
        val id: Long,
        val slug: String,
        val title: String,
        val summary: String,
        val author: String,
        val file: ProjectFile?,
    )

    data class IndexEntry(val id: Long, val slug: String, val title: String, val summary: String)

    /** Accepts a CurseForge project URL, a bare slug or a numeric project id (optionally prefixed with `#`). */
    fun parseInput(raw: String): Ref? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        PROJECT_URL.matchEntire(text)?.let { match ->
            return Ref("wow/addons/" + match.groupValues[1].lowercase(Locale.ROOT))
        }
        val id = text.removePrefix("#").trim()
        if (PROJECT_ID.matches(id)) return Ref("wow/addons/$id")
        val lower = text.lowercase(Locale.ROOT)
        if (SLUG.matches(lower)) return Ref("wow/addons/$lower")
        return null
    }

    /**
     * True for inputs that name one project outright: a CurseForge URL or a numeric project id. Anything else is
     * searched in the shipped index and through Wago first, so partial names still find projects.
     */
    internal fun isDirectQuery(raw: String): Boolean {
        val text = raw.trim()
        return text.contains("curseforge.com", ignoreCase = true) || PROJECT_ID.matches(text.removePrefix("#").trim())
    }

    internal fun parseProject(json: String, gameVersion: String): Project? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val id = root.optLong("id", 0L)
        if (id <= 0L) return null
        val authors = LinkedHashSet<String>()
        root.optJSONArray("members")?.let { members ->
            for (index in 0 until members.length()) {
                members.optJSONObject(index)?.let { member ->
                    member.text("username").takeIf { it.isNotEmpty() }?.let(authors::add)
                }
            }
        }
        val urlSlug = root.optJSONObject("urls")
            ?.text("curseforge")
            ?.substringAfterLast('/')
            ?.takeIf { SLUG.matches(it) }
        // cfwidget occasionally reports `"title": null`; a raw optString would render the literal "null".
        val title = root.text("title").ifBlank { urlSlug ?: "CurseForge #$id" }
        val download = root.optJSONObject("download")
        val file = download?.let(::fileOf)?.takeIf { gameVersion in it.versions }
        return Project(
            id = id,
            slug = urlSlug ?: id.toString(),
            title = title,
            summary = root.text("summary"),
            author = authors.joinToString(", "),
            file = file,
        )
    }

    internal fun downloadUrl(file: ProjectFile): String =
        "$CDN_BASE/${file.id / 1000}/${file.id % 1000}/" +
            URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")

    internal fun parseIndex(json: String): List<IndexEntry> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val addons = root.optJSONArray("addons") ?: return emptyList()
        val entries = LinkedHashMap<String, IndexEntry>()
        for (index in 0 until addons.length()) {
            val item = addons.optJSONObject(index) ?: continue
            val id = item.optLong("id", 0L)
            val slug = item.text("slug")
            val title = item.text("title")
            if (id <= 0L || !SLUG.matches(slug) || title.isBlank()) continue
            entries.putIfAbsent(slug, IndexEntry(id, slug, title, item.text("summary")))
        }
        return entries.values.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun index(context: Context): List<IndexEntry> = runCatching {
        context.assets.open(INDEX_ASSET).bufferedReader().use { it.readText() }
    }.mapCatching(::parseIndex).getOrDefault(emptyList())

    suspend fun project(
        context: Context,
        input: String,
        gameVersion: String,
        maxAgeMs: Long = CACHE_TTL_MS,
    ): Project = withContext(Dispatchers.IO) {
        val ref = parseInput(input) ?: throw IOException("无法识别的 CurseForge 项目：$input")
        if (gameVersion.isBlank()) throw IOException("无法读取游戏版本，CurseForge 不可用")
        val cache = File(
            File(context.filesDir, CACHE_DIR),
            ref.path.replace(NON_CACHE_CHARS, "_") + "_" + gameVersion.replace(NON_CACHE_CHARS, "_") + ".json",
        )
        if (maxAgeMs > 0 && cache.isFile && System.currentTimeMillis() - cache.lastModified() <= maxAgeMs) {
            runCatching { decodeCached(cache.readText()) }.getOrNull()?.let { return@withContext it }
        }
        val url = "$WIDGET_BASE/${ref.path}?version=" + URLEncoder.encode(gameVersion, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val body = Net.http.newCall(request).execute().use { response ->
            if (response.code == 404) {
                val numeric = PROJECT_ID.matches(ref.path.substringAfterLast('/'))
                throw IOException(
                    if (numeric) "CurseForge 没有项目：$input（若这是文件 ID，请粘贴完整文件链接）"
                    else "CurseForge 没有项目：$input",
                )
            }
            if (!response.isSuccessful) throw IOException("CurseForge HTTP ${response.code}")
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("CurseForge 返回了空内容")
        }
        val project = parseProject(body, gameVersion)
            ?: throw IOException("CurseForge 返回了无法解析的数据")
        runCatching {
            cache.parentFile?.mkdirs()
            cache.writeText(encodeCached(project))
        }.onFailure { Timber.w(it, "Couldn't cache CurseForge project ${project.slug}") }
        project
    }

    suspend fun fetchZip(
        context: Context,
        input: String,
        gameVersion: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ): Project {
        val project = project(context, input, gameVersion, maxAgeMs = 0)
        val file = project.file ?: throw IOException("${project.title} 没有 Forever 版本")
        Net.fetchFile(downloadUrl(file), dest) { progress -> onProgress(progress * 0.9f) }
        return project
    }

    internal fun encodeCached(project: Project): String {
        val root = JSONObject()
            .put("id", project.id)
            .put("slug", project.slug)
            .put("title", project.title)
            .put("summary", project.summary)
            .put("author", project.author)
        project.file?.let { file ->
            val versions = JSONArray()
            file.versions.forEach(versions::put)
            root.put(
                "file",
                JSONObject()
                    .put("id", file.id)
                    .put("name", file.name)
                    .put("display", file.display)
                    .put("type", file.type)
                    .put("size", file.size)
                    .put("versions", versions)
                    .put("uploadedAt", file.uploadedAt),
            )
        }
        return root.toString()
    }

    internal fun decodeCached(json: String): Project? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val id = root.optLong("id", 0L)
        val slug = root.text("slug")
        val title = root.text("title")
        if (id <= 0L || slug.isBlank() || title.isBlank()) return null
        return Project(
            id = id,
            slug = slug,
            title = title,
            summary = root.text("summary"),
            author = root.text("author"),
            file = root.optJSONObject("file")?.let(::fileOf),
        )
    }

    /** `optString` turns JSON null into the literal "null"; treat both null and that literal as empty. */
    private fun jsonText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value.trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
        else -> value.toString().trim()
    }

    private fun JSONObject.text(key: String): String = jsonText(opt(key))

    private fun JSONArray.text(index: Int): String = jsonText(opt(index))

    private fun fileOf(json: JSONObject): ProjectFile? {
        val id = json.optLong("id", 0L)
        val name = json.text("name")
        if (id <= 0L || name.isBlank() || name.contains('/') || name.contains('\\')) return null
        val versions = ArrayList<String>()
        json.optJSONArray("versions")?.let { array ->
            for (index in 0 until array.length()) {
                array.text(index).takeIf { it.isNotEmpty() }?.let(versions::add)
            }
        }
        return ProjectFile(
            id = id,
            name = name,
            display = json.text("display").ifBlank { id.toString() },
            type = json.text("type"),
            size = json.optLong("filesize", -1L).takeIf { it >= 0L } ?: json.optLong("size", -1L),
            versions = versions,
            uploadedAt = json.text("uploaded_at").ifBlank { json.text("uploadedAt") },
        )
    }
}
