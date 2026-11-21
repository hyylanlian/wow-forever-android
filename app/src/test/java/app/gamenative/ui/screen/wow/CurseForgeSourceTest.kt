package app.gamenative.ui.screen.wow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurseForgeSourceTest {
    private fun projectJson(versions: List<String>): String = """
        {
          "id": 61284,
          "title": "Details! Damage Meter",
          "summary": "Complete combat analysis",
          "urls": {"curseforge": "https://www.curseforge.com/wow/addons/details"},
          "members": [
            {"title": "Owner", "username": "jaliborc"},
            {"title": "Author", "username": "jaliborc"},
            {"title": "Author", "username": "terciob"}
          ],
          "download": {
            "id": 9675720,
            "name": "Details-20261109.zip",
            "display": "Details.20261109.15410.172",
            "type": "release",
            "filesize": 7225522,
            "versions": ${versions.joinToString(",", "[", "]") { "\"$it\"" }},
            "uploaded_at": "2026-11-18T00:06:48.903Z"
          }
        }
    """.trimIndent()

    @Test
    fun parseInput_acceptsUrlsSlugsAndIds() {
        assertEquals(
            CurseForgeSource.Ref("wow/addons/details"),
            CurseForgeSource.parseInput("https://www.curseforge.com/wow/addons/details/files/9675720?tab=files#top"),
        )
        assertEquals(
            CurseForgeSource.Ref("wow/addons/pawn"),
            CurseForgeSource.parseInput("www.curseforge.com/wow/addons/pawn"),
        )
        assertEquals(CurseForgeSource.Ref("wow/addons/details"), CurseForgeSource.parseInput("Details"))
        assertEquals(CurseForgeSource.Ref("61284"), CurseForgeSource.parseInput("61284"))

        assertNull(CurseForgeSource.parseInput("https://github.com/hyylanlian/wow-forever-android"))
        assertNull(CurseForgeSource.parseInput(""))
        assertNull(CurseForgeSource.parseInput("../x"))
        assertNull(CurseForgeSource.parseInput("a/b"))
    }

    @Test
    fun parseProject_readsForeverFile() {
        val project = CurseForgeSource.parseProject(projectJson(listOf("12.1.5", "1.60.1")), "1.60.1")!!

        assertEquals(61284L, project.id)
        assertEquals("details", project.slug)
        assertEquals("Details! Damage Meter", project.title)
        assertEquals("Complete combat analysis", project.summary)
        assertEquals("jaliborc, terciob", project.author)
        val file = project.file!!
        assertEquals(9675720L, file.id)
        assertEquals("Details-20261109.zip", file.name)
        assertEquals("Details.20261109.15410.172", file.display)
        assertEquals("release", file.type)
        assertEquals(7225522L, file.size)
        assertEquals("2026-11-18T00:06:48.903Z", file.uploadedAt)
        assertTrue("1.60.1" in file.versions)
    }

    @Test
    fun parseProject_marksMissingForeverFile() {
        assertNull(CurseForgeSource.parseProject(projectJson(listOf("12.1.5")), "1.60.1")?.file)
    }

    @Test
    fun downloadUrl_usesCdnLayout() {
        val file = CurseForgeSource.ProjectFile(
            id = 9675720,
            name = "Details-X.zip",
            display = "Details-X",
            type = "release",
            size = 1,
            versions = listOf("1.60.1"),
            uploadedAt = "",
        )

        assertEquals(
            "https://edge.forgecdn.net/files/9675/720/Details-X.zip",
            CurseForgeSource.downloadUrl(file),
        )
        assertEquals(
            "https://edge.forgecdn.net/files/9675/720/My%20Addon.zip",
            CurseForgeSource.downloadUrl(file.copy(name = "My Addon.zip")),
        )
    }

    @Test
    fun parseIndex_skipsBadEntries() {
        val json = """
            {
              "addons": [
                {"id": 2, "slug": "pawn", "title": "Pawn", "summary": "Stat weights"},
                {"id": 0, "slug": "zero", "title": "Zero"},
                {"id": 3, "slug": "../evil", "title": "Evil"},
                {"id": 4, "slug": "untitled"},
                {"id": 1, "slug": "details", "title": "Details! Damage Meter"}
              ]
            }
        """.trimIndent()

        val entries = CurseForgeSource.parseIndex(json)

        assertEquals(listOf("details", "pawn"), entries.map { it.slug })
        assertEquals("Details! Damage Meter", entries[0].title)
        assertEquals("", entries[0].summary)
        assertEquals(1L, entries[0].id)
        assertEquals("Stat weights", entries[1].summary)
    }

    @Test
    fun cachedProject_roundTrips() {
        val project = CurseForgeSource.parseProject(projectJson(listOf("1.60.1")), "1.60.1")!!

        assertEquals(project, CurseForgeSource.decodeCached(CurseForgeSource.encodeCached(project)))
        val withoutFile = project.copy(file = null)
        assertEquals(withoutFile, CurseForgeSource.decodeCached(CurseForgeSource.encodeCached(withoutFile)))
        assertNull(CurseForgeSource.decodeCached("not json"))
    }
}
