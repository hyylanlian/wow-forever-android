package app.gamenative.ui.screen.wow

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun page(json: JSONObject): String {
        // Blade entity-encodes the whole Inertia payload inside the attribute, including the card markup.
        val encoded = json.toString()
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return """<html><body><div id="app" data-page="$encoded"></div></body></html>"""
    }

    private fun searchPage(cards: List<String>, page: Int = 1, lastPage: Int = 1): String = page(
        JSONObject().put(
            "props",
            JSONObject().put(
                "addons",
                JSONObject()
                    .put("data", JSONArray(cards))
                    .put("current_page", page)
                    .put("last_page", lastPage),
            ),
        ),
    )

    @Test
    fun search_readsCardsFromTheInertiaPayload() {
        val html = searchPage(
            listOf(
                """
                <a href="https://addons.wago.io/addons/details-damage-meter-standalone" class="plain-link">
                    <h3 class="font-bold">Details! Damage Meter</h3>
                    <p class="truncate">Famous Combat addon &amp; analyzer.</p>
                    <span><strong>Updated:</strong> Nov 18, 2026</span>
                    <span><strong>Author:</strong> Terciob</span>
                </a>
                """.trimIndent(),
                """
                <a href="https://addons.wago.io/addons/forever-details">
                    <h3>Forever Details</h3>
                    <p>Lightweight meter for WoW Forever.</p>
                    <span><strong>Updated:</strong> Nov 19, 2026</span>
                    <span><strong>Author:</strong> Dldvlpr</span>
                </a>
                """.trimIndent(),
            ),
            page = 2,
            lastPage = 3,
        )

        val result = WagoAddonSource.parseSearch(html)

        assertEquals(2, result.results.size)
        assertEquals(2, result.page)
        assertEquals(3, result.lastPage)
        assertEquals("Details! Damage Meter", result.results[0].name)
        assertEquals("details-damage-meter-standalone", result.results[0].slug)
        assertEquals("Famous Combat addon & analyzer.", result.results[0].summary)
        assertEquals("Terciob", result.results[0].author)
        assertEquals("Nov 18, 2026", result.results[0].updated)
    }

    @Test
    fun addon_readsTheForeverReleaseAndAuthor() {
        val html = page(
            JSONObject().put(
                "props",
                JSONObject()
                    .put(
                        "addon",
                        JSONObject()
                            .put("display_name", "Details! Damage Meter")
                            .put("slug", "details-damage-meter-standalone")
                            .put("summary", "Combat analyzer"),
                    )
                    .put(
                        "metadata",
                        JSONObject()
                            .put("owner", "Terciob")
                            .put(
                                "recent_releases",
                                JSONObject().put(
                                    "forever",
                                    JSONObject()
                                        .put("label", "Details.20261109.15410.172")
                                        .put("download_link", "https://addons.wago.io/download/abc")
                                        .put("supported_forever_patch", "1.60.1"),
                                ),
                            ),
                    ),
            ),
        )

        val addon = WagoAddonSource.parseAddon(html)!!

        assertEquals("Details! Damage Meter", addon.name)
        assertEquals("details-damage-meter-standalone", addon.slug)
        assertEquals("Terciob", addon.author)
        assertEquals("Details.20261109.15410.172", addon.release?.version)
        assertEquals("https://addons.wago.io/download/abc", addon.release?.downloadUrl)
        assertEquals("1.60.1", addon.release?.patch)
    }

    @Test
    fun addon_withoutForeverBuildHasNoRelease() {
        val html = page(
            JSONObject().put(
                "props",
                JSONObject()
                    .put("addon", JSONObject().put("display_name", "Retail Only").put("slug", "retail-only"))
                    .put("metadata", JSONObject().put("recent_releases", JSONObject().put("retail", JSONObject()))),
            ),
        )

        assertNull(WagoAddonSource.parseAddon(html)?.release)
    }

    @Test
    fun toc_readsTitleVersionAndInterfaceAndIgnoresBundledLibraries() {
        val dir = File(tempFolder.root, "AddOns/Details").apply { mkdirs() }
        File(dir, "Details.toc").writeText(
            "\uFEFF## Interface: 120105\n## Title: Details! Damage Meter\n## Version: #Details.20261109.15410.172\n",
        )
        File(dir, "Libs/LibStub/LibStub.toc").apply { parentFile.mkdirs() }.writeText("## Title: LibStub\n")

        assertEquals("Details.toc", AddonManager.findToc(dir)?.name)
        val toc = AddonManager.readToc(File(dir, "Details.toc"))!!
        assertEquals("Details! Damage Meter", toc.title)
        assertEquals("Details.20261109.15410.172", toc.version)
        assertEquals("120105", toc.interfaceVersion)
    }

    @Test
    fun records_roundTripFoldersAndVersions() {
        val records = mapOf(
            "details-damage-meter-standalone" to AddonManager.Record(
                slug = "details-damage-meter-standalone",
                name = "Details! Damage Meter",
                version = "Details.20261109.15410.172",
                folders = listOf("Details"),
                installedAt = 1_700_000_000_000,
            ),
        )

        val decoded = AddonManager.decodeRecords(AddonManager.encodeRecords(records))

        assertEquals(records, decoded)
        assertTrue(AddonManager.decodeRecords("not json").isEmpty())
    }
}
