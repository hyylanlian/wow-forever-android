package app.gamenative.ui.screen.wow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

private val TITLE_COLOR = Color(0xFFE2E8F0)
private val SUCCESS = Color(0xFF4CAF50)
private val CHECK_TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Add-on browser and updater. Installs replace the add-on's folder in place through [AddonImporter], so a running
 * game keeps its SavedVariables while the code is refreshed.
 */
@Composable
internal fun AddonManagerDialog(
    addonsDir: File,
    gameVersion: String,
    refreshKey: Int,
    onDismiss: () -> Unit,
    onImportZip: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }
    var installed by remember { mutableStateOf<List<AddonManager.InstalledAddon>>(emptyList()) }
    var loadingInstalled by remember { mutableStateOf(true) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var batchUpdating by remember { mutableStateOf(false) }
    var updates by remember { mutableStateOf<Map<String, AddonManager.AvailableUpdate>>(emptyMap()) }
    var checkWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var installedFilter by remember { mutableStateOf("") }
    var sortByStatus by remember { mutableStateOf(false) }
    var failedUpdates by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchDone by remember { mutableStateOf(0) }
    var batchTotal by remember { mutableStateOf(0) }
    var lastCheckedAt by remember { mutableStateOf<Long?>(null) }
    var pendingUninstall by remember { mutableStateOf<AddonManager.InstalledAddon?>(null) }
    var uninstallingKey by remember { mutableStateOf<String?>(null) }
    var togglingKey by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<WagoAddonSource.SearchResult>>(emptyList()) }
    var searchPage by remember { mutableStateOf(0) }
    var searchLastPage by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    var cfQuery by remember { mutableStateOf("") }
    var cfIndex by remember { mutableStateOf<List<CurseForgeSource.IndexEntry>>(emptyList()) }
    var cfIndexLoaded by remember { mutableStateOf(false) }
    var cfRows by remember { mutableStateOf<List<CfRow>>(emptyList()) }
    var cfSearching by remember { mutableStateOf(false) }
    var cfSearched by remember { mutableStateOf(false) }
    var cfError by remember { mutableStateOf<String?>(null) }

    suspend fun loadInstalled(check: Boolean, force: Boolean = false) {
        checkWarnings = emptyList()
        loadingInstalled = true
        installed = withContext(Dispatchers.IO) { AddonManager.installed(context, addonsDir) }
        failedUpdates = failedUpdates.intersect(installed.mapNotNull { it.slug }.toSet())
        loadingInstalled = false
        if (check) {
            if (installed.any { it.slug != null }) {
                checkingUpdates = true
                try {
                    val result = AddonManager.checkUpdates(context, installed, gameVersion, force)
                    updates = result.updates
                    lastCheckedAt = System.currentTimeMillis()
                    checkWarnings = buildList {
                        if (result.failures.isNotEmpty()) add("${result.failures.size} 个插件检查失败：${result.failures.joinToString()}")
                        if (result.unsupported.isNotEmpty()) add("没有 Forever 版本：${result.unsupported.joinToString()}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Add-on update check failed")
                    checkWarnings = listOf("检查更新失败：${e.message}")
                } finally {
                    checkingUpdates = false
                }
            } else {
                updates = emptyMap()
            }
        }
    }

    LaunchedEffect(refreshKey) {
        loadInstalled(true)
    }

    LaunchedEffect(Unit) {
        cfIndex = withContext(Dispatchers.IO) { CurseForgeSource.index(context) }
        cfIndexLoaded = true
    }

    fun install(slug: String, key: String, source: String, checkAfter: Boolean) {
        if (busy.containsKey(key)) return
        actionError = null
        busy = busy + (key to 0f)
        scope.launch {
            try {
                val result = AddonManager.install(context, addonsDir, slug, gameVersion, source) { progress ->
                    busy = busy + (key to progress)
                }
                failedUpdates = failedUpdates - slug
                SnackbarManager.show("已安装 ${result.name}（${result.folders.joinToString()}）")
                loadInstalled(checkAfter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Add-on install failed: $slug")
                actionError = "安装失败：${e.message}"
            } finally {
                busy = busy - key
            }
        }
    }

    fun update(slugs: Collection<String>) {
        if (batchUpdating || busy.isNotEmpty()) return
        val wanted = slugs.toSet()
        val targets = installed
            .filter { it.slug?.let { slug -> slug in wanted } == true }
            .distinctBy { it.slug }
        if (targets.isEmpty()) return
        actionError = null
        scope.launch {
            batchUpdating = true
            batchDone = 0
            batchTotal = targets.size
            var updated = 0
            val failed = ArrayList<String>()
            try {
                targets.forEach { addon ->
                    val slug = addon.slug ?: return@forEach
                    busy = busy + (slug to 0f)
                    try {
                        AddonManager.install(context, addonsDir, slug, gameVersion, addon.source) { progress ->
                            busy = busy + (slug to progress)
                        }
                        updated++
                        failedUpdates = failedUpdates - slug
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Add-on update failed: $slug")
                        failed += addon.title
                        failedUpdates = failedUpdates + slug
                    } finally {
                        busy = busy - slug
                        batchDone++
                    }
                }
            } finally {
                batchUpdating = false
            }
            loadInstalled(true)
            SnackbarManager.show(
                if (failed.isEmpty()) "已更新 $updated 个插件"
                else "已更新 $updated 个插件，${failed.size} 个失败：${failed.joinToString()}",
            )
        }
    }

    fun toggle(addon: AddonManager.InstalledAddon, enabled: Boolean) {
        if (togglingKey != null || uninstallingKey != null || batchUpdating || busy.isNotEmpty()) return
        val key = addon.slug ?: addon.folders.first()
        actionError = null
        togglingKey = key
        scope.launch {
            try {
                val failed = withContext(Dispatchers.IO) {
                    AddonManager.setEnabled(addonsDir, addon, enabled)
                }
                if (failed.isNotEmpty()) {
                    actionError = "无法${if (enabled) "启用" else "禁用"}：${failed.joinToString()}"
                }
                loadInstalled(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Add-on toggle failed: ${addon.title}")
                actionError = "切换失败：${e.message}"
            } finally {
                togglingKey = null
            }
        }
    }

    fun search(page: Int) {
        val text = query.trim()
        if (text.isEmpty() || searching) return
        actionError = null
        searchError = null
        searching = true
        if (page <= 1) {
            searchResults = emptyList()
            searchPage = 0
            searchLastPage = 0
            searched = true
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { WagoAddonSource.search(text, page) }
                searchResults = if (page <= 1) result.results else searchResults + result.results
                searchPage = result.page
                searchLastPage = result.lastPage
            } catch (e: Exception) {
                Timber.e(e, "Add-on search failed")
                searchError = "搜索失败：${e.message}"
            } finally {
                searching = false
            }
        }
    }

    suspend fun mappedRow(slug: String): CfRow? = try {
        val addon = WagoAddonSource.addon(slug)
        val ref = CurseForgeSource.parseInput(addon.website)
        if (ref != null && ref.path.startsWith("wow/addons/")) {
            CfRow(ref.path.removePrefix("wow/addons/"), addon.name, addon.summary, "Wago 匹配")
        } else {
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Couldn't map Wago add-on $slug to CurseForge")
        null
    }

    fun searchCurseForge() {
        if (cfSearching) return
        val text = cfQuery.trim()
        if (text.isEmpty()) return
        val ref = CurseForgeSource.parseInput(text)
        cfError = null
        cfSearched = true
        cfSearching = true
        cfRows = emptyList()
        scope.launch {
            try {
                if (ref != null) {
                    val project = withContext(Dispatchers.IO) {
                        CurseForgeSource.project(context, text, gameVersion, maxAgeMs = 0)
                    }
                    val suffix = if (project.file == null) " · 没有 Forever 版本" else " · ${project.file.display}"
                    cfRows = listOf(CfRow(project.slug, project.title, project.summary + suffix, "链接"))
                } else {
                    val q = text.lowercase(Locale.ROOT)
                    val local = cfIndex
                        .filter { entry ->
                            entry.title.lowercase(Locale.ROOT).contains(q) ||
                                entry.slug.contains(q) ||
                                entry.summary.lowercase(Locale.ROOT).contains(q)
                        }
                        .map { CfRow(it.slug, it.title, it.summary, "CurseForge 目录") }
                    // Wago detail pages often link a CurseForge project; those projects need no index entry.
                    val mapped = try {
                        val results = withContext(Dispatchers.IO) { WagoAddonSource.search(text, 1) }.results
                        coroutineScope {
                            val semaphore = Semaphore(4)
                            results.map { result ->
                                async { semaphore.withPermit { mappedRow(result.slug) } }
                            }.awaitAll().filterNotNull()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Wago to CurseForge mapping failed")
                        cfError = "Wago 匹配失败：${e.message}"
                        emptyList()
                    }
                    val seen = local.map { it.slug }.toSet()
                    cfRows = local + mapped.filter { it.slug !in seen }.distinctBy { it.slug }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "CurseForge lookup failed")
                cfError = "查找失败：${e.message}"
                cfRows = emptyList()
            } finally {
                cfSearching = false
            }
        }
    }

    val bySlug = installed
        .filter { it.slug != null && it.source == AddonManager.SOURCE_WAGO }
        .associateBy { it.slug!! }
    val forgeBySlug = installed
        .filter { it.slug != null && it.source == AddonManager.SOURCE_CURSEFORGE }
        .associateBy { it.slug!! }
    val indexRows = cfIndex.map { CfRow(it.slug, it.title, it.summary, "CurseForge 目录") }
    val updateCount = installed.count { it.slug?.let { slug -> updates.containsKey(slug) } == true }
    val filter = installedFilter.trim().lowercase(Locale.ROOT)
    val visibleInstalled = installed
        .filter { addon ->
            filter.isEmpty() ||
                addon.title.lowercase(Locale.ROOT).contains(filter) ||
                addon.folders.any { it.lowercase(Locale.ROOT).contains(filter) } ||
                addon.slug?.lowercase(Locale.ROOT)?.contains(filter) == true
        }
        .sortedWith(
            if (sortByStatus) {
                compareBy<AddonManager.InstalledAddon> { addon ->
                    val slug = addon.slug
                    when {
                        slug != null && updates.containsKey(slug) -> 0
                        slug != null && slug in failedUpdates -> 1
                        slug == null -> 3
                        else -> 2
                    }
                }.thenBy { it.title.lowercase(Locale.ROOT) }
            } else {
                compareBy<AddonManager.InstalledAddon> { it.title.lowercase(Locale.ROOT) }
            },
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, contentDescription = null, tint = WowGold, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("插件管理", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TabButton("已安装 ${installed.size}", tab == 0) { tab = 0 }
                    TabButton("搜索", tab == 1) { tab = 1 }
                    TabButton("CurseForge", tab == 2) { tab = 2 }
                }
                HorizontalDivider(color = Color(0xFF1E2D44))
                Spacer(Modifier.height(6.dp))

                when (tab) {
                    0 -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(
                            onClick = { scope.launch { loadInstalled(true, force = true) } },
                            enabled = !loadingInstalled && !checkingUpdates && !batchUpdating && togglingKey == null,
                        ) { Text("检查更新") }
                        TextButton(onClick = onImportZip, enabled = !batchUpdating && busy.isEmpty() && togglingKey == null) {
                            Text("从 ZIP 安装")
                        }
                        TextButton(
                            onClick = { scope.launch { loadInstalled(false) } },
                            enabled = !loadingInstalled && !batchUpdating && togglingKey == null,
                        ) { Text("刷新") }
                        Spacer(Modifier.weight(1f))
                        lastCheckedAt?.let { checked ->
                            Text(
                                "检查于 ${Instant.ofEpochMilli(checked).atZone(ZoneId.systemDefault()).format(CHECK_TIME)}",
                                color = WowSubtle,
                                fontSize = 10.sp,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = installedFilter,
                            onValueChange = { installedFilter = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("筛选已安装", fontSize = 12.sp) },
                            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp),
                        )
                        TextButton(onClick = { sortByStatus = !sortByStatus }) {
                            Text(if (sortByStatus) "排序：状态" else "排序：名称", fontSize = 12.sp)
                        }
                    }

                    if (checkingUpdates || batchUpdating) {
                        LinearProgressIndicator(
                            color = WowGold,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    if (batchUpdating) {
                        Text(
                            "正在更新 $batchDone/$batchTotal…",
                            color = WowMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }

                    when {
                        loadingInstalled && installed.isEmpty() -> BoxProgress("正在读取已安装插件…")
                        installed.isEmpty() -> Text(
                            "还没有安装插件。到“搜索”里找，或点“从 ZIP 安装”。",
                            color = WowSubtle,
                            fontSize = 12.sp,
                        )
                        visibleInstalled.isEmpty() -> Text(
                            "没有匹配的插件。",
                            color = WowSubtle,
                            fontSize = 12.sp,
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                            items(visibleInstalled, key = { it.slug ?: it.folders.first() }) { addon ->
                                val key = addon.slug ?: addon.folders.first()
                                val progress = addon.slug?.let { busy[it] }
                                InstalledRow(
                                    addon = addon,
                                    update = addon.slug?.let { updates[it] },
                                    progress = progress,
                                    failed = addon.slug?.let { it in failedUpdates } == true,
                                    uninstalling = uninstallingKey == key,
                                    toggling = togglingKey == key,
                                    actionsEnabled = progress == null &&
                                        uninstallingKey == null &&
                                        togglingKey == null &&
                                        !batchUpdating &&
                                        busy.isEmpty(),
                                    onUpdate = { addon.slug?.let { update(listOf(it)) } },
                                    onToggle = { enabled -> toggle(addon, enabled) },
                                    onUninstall = { pendingUninstall = addon },
                                )
                                HorizontalDivider(color = Color(0xFF1E2D44))
                            }
                        }
                    }

                    if (failedUpdates.isNotEmpty() && !batchUpdating) {
                        TextButton(
                            onClick = { update(failedUpdates) },
                            enabled = busy.isEmpty() && !checkingUpdates && uninstallingKey == null && togglingKey == null,
                        ) { Text("重试失败（${failedUpdates.size}）") }
                    }
                    if (updateCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                update(
                                    installed.mapNotNull { it.slug }
                                        .filter { updates.containsKey(it) }
                                        .distinct(),
                                )
                            },
                            enabled = !batchUpdating && busy.isEmpty() && !checkingUpdates &&
                                uninstallingKey == null && togglingKey == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("全部更新（$updateCount）") }
                    }

                    checkWarnings.forEach { warning ->
                        Text(warning, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    actionError?.let { Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Text(
                        "安装或更新后重启游戏生效。插件来自 addons.wago.io，只显示支持 WoW Forever 的版本。",
                        color = WowSubtle,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                    1 -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("插件名称，如 Details", fontSize = 12.sp) },
                            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp),
                        )
                        Button(
                            onClick = { search(1) },
                            enabled = !searching && query.isNotBlank(),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("搜索")
                        }
                    }

                    if (searching) {
                        LinearProgressIndicator(
                            color = WowGold,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                    searchError?.let { Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                    actionError?.let { Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }

                    when {
                        searched && !searching && searchResults.isEmpty() && searchError == null -> Text(
                            "没有找到支持 WoW Forever 的插件。",
                            color = WowSubtle,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        !searched -> Text(
                            "输入名称搜索 Wago 插件目录。",
                            color = WowSubtle,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                            items(searchResults, key = { it.slug }) { result ->
                                SearchRow(
                                    result = result,
                                    installed = bySlug[result.slug],
                                    progress = busy[result.slug],
                                    onInstall = {
                                        install(result.slug, result.slug, AddonManager.SOURCE_WAGO, checkAfter = true)
                                    },
                                )
                                HorizontalDivider(color = Color(0xFF1E2D44))
                            }
                        }
                    }

                    if (searchPage in 1 until searchLastPage && !searching) {
                        TextButton(onClick = { search(searchPage + 1) }, modifier = Modifier.fillMaxWidth()) {
                            Text("加载更多")
                        }
                    }
                }
                    2 -> {
                        if (gameVersion.isBlank()) {
                            Text(
                                "无法读取游戏版本，CurseForge 暂不可用。",
                                color = WowSubtle,
                                fontSize = 12.sp,
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedTextField(
                                    value = cfQuery,
                                    onValueChange = { cfQuery = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text("插件名或 CurseForge 链接", fontSize = 12.sp) },
                                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp),
                                )
                                Button(
                                    onClick = { searchCurseForge() },
                                    enabled = !cfSearching && cfQuery.isNotBlank(),
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("查找")
                                }
                            }

                            if (cfSearching) {
                                LinearProgressIndicator(
                                    color = WowGold,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                )
                            }
                            Text(
                                "通过社区镜像读取项目信息；只安装标记了 Forever $gameVersion 的文件。",
                                color = WowSubtle,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            cfError?.let {
                                Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            actionError?.let {
                                Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }

                            when {
                                cfQuery.isBlank() && cfIndexLoaded -> LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                                ) {
                                    items(indexRows, key = { it.slug }) { row ->
                                        CurseForgeRow(
                                            row = row,
                                            installed = forgeBySlug[row.slug],
                                            updateAvailable = updates.containsKey(row.slug),
                                            progress = busy[row.slug],
                                            onInstall = {
                                                install(
                                                    row.slug,
                                                    row.slug,
                                                    AddonManager.SOURCE_CURSEFORGE,
                                                    checkAfter = true,
                                                )
                                            },
                                        )
                                        HorizontalDivider(color = Color(0xFF1E2D44))
                                    }
                                }
                                cfQuery.isBlank() -> BoxProgress("正在读取插件目录…")
                                cfSearched && cfRows.isEmpty() && !cfSearching -> Text(
                                    "没有匹配的 CurseForge 插件。",
                                    color = WowSubtle,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                                    items(cfRows, key = { it.slug }) { row ->
                                        CurseForgeRow(
                                            row = row,
                                            installed = forgeBySlug[row.slug],
                                            updateAvailable = updates.containsKey(row.slug),
                                            progress = busy[row.slug],
                                            onInstall = {
                                                install(
                                                    row.slug,
                                                    row.slug,
                                                    AddonManager.SOURCE_CURSEFORGE,
                                                    checkAfter = true,
                                                )
                                            },
                                        )
                                        HorizontalDivider(color = Color(0xFF1E2D44))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    pendingUninstall?.let { addon ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("卸载 ${addon.title}？") },
            text = {
                Text("将删除 ${addon.folders.joinToString()}。角色配置（SavedVariables）会保留。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val key = addon.slug ?: addon.folders.first()
                        pendingUninstall = null
                        actionError = null
                        uninstallingKey = key
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    AddonManager.uninstall(context, addonsDir, addon)
                                }
                                if (result.remaining.isNotEmpty()) {
                                    actionError = "部分文件无法删除：${result.remaining.joinToString()}"
                                } else {
                                    SnackbarManager.show("已卸载 ${addon.title}")
                                    loadInstalled(false)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "Add-on uninstall failed: ${addon.title}")
                                actionError = "卸载失败：${e.message}"
                            } finally {
                                uninstallingKey = null
                            }
                        }
                    },
                ) { Text("卸载", color = WowError) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text,
            color = if (selected) WowGold else WowMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun BoxProgress(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    ) {
        CircularProgressIndicator(color = WowGold, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = WowSubtle, fontSize = 12.sp)
    }
}

@Composable
private fun InstalledRow(
    addon: AddonManager.InstalledAddon,
    update: AddonManager.AvailableUpdate?,
    progress: Float?,
    failed: Boolean,
    uninstalling: Boolean,
    toggling: Boolean,
    actionsEnabled: Boolean,
    onUpdate: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    addon.title,
                    color = TITLE_COLOR,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val installedAt = addon.installedAt?.let { " · 安装于 ${installedDate(it)}" }.orEmpty()
                val disabled = if (addon.enabled) "" else " · 已禁用"
                val sourceLabel = when {
                    addon.slug == null -> "手动"
                    addon.source == AddonManager.SOURCE_CURSEFORGE -> "CurseForge"
                    else -> "Wago"
                }
                Text(
                    "${addon.folders.joinToString(", ")} · " +
                        "${if (addon.version.isBlank()) "版本未知" else "v${addon.version}"} · " +
                        "$sourceLabel$installedAt$disabled",
                    color = WowSubtle,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                update?.let {
                    val patch = it.patch?.takeIf { value -> value.isNotBlank() }
                        ?.let { value -> "（支持补丁 $value）" }
                        .orEmpty()
                    Text(
                        "${addon.version.ifBlank { "当前版本" }} → ${it.version}$patch",
                        color = WowMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                when {
                    uninstalling -> Text("卸载中…", color = WowMuted, fontSize = 11.sp)
                    progress != null -> Text("${(progress * 100).toInt()}%", color = WowGold, fontSize = 12.sp)
                    failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("更新失败", color = WowError, fontSize = 11.sp)
                        TextButton(onClick = onUpdate, enabled = actionsEnabled) { Text("重试") }
                    }
                    update != null -> TextButton(onClick = onUpdate, enabled = actionsEnabled) { Text("更新") }
                    addon.slug == null -> Text("手动", color = WowMuted, fontSize = 11.sp)
                    else -> Text("已最新", color = SUCCESS, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (toggling) {
                        CircularProgressIndicator(
                            color = WowGold,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Switch(
                        checked = addon.enabled,
                        onCheckedChange = onToggle,
                        enabled = actionsEnabled,
                    )
                }
                TextButton(onClick = onUninstall, enabled = actionsEnabled) { Text("卸载") }
            }
        }
    }
}

@Composable
private fun SearchRow(
    result: WagoAddonSource.SearchResult,
    installed: AddonManager.InstalledAddon?,
    progress: Float?,
    onInstall: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    result.name,
                    color = TITLE_COLOR,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val installedLabel = installed?.let {
                    " · 已安装 ${if (it.version.isBlank()) "版本未知" else "v${it.version}"}"
                }.orEmpty()
                Text(
                    listOf(result.author, result.updated).filter { it.isNotBlank() }
                        .joinToString(" · ").ifBlank { result.slug } + installedLabel,
                    color = WowSubtle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                progress != null -> Text("${(progress * 100).toInt()}%", color = WowGold, fontSize = 12.sp)
                else -> TextButton(onClick = onInstall) {
                    Text(if (installed == null) "安装" else "重新安装")
                }
            }
        }
        if (result.summary.isNotBlank()) {
            Text(
                result.summary,
                color = WowMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private data class CfRow(
    val slug: String,
    val title: String,
    val summary: String,
    val origin: String,
)

@Composable
private fun CurseForgeRow(
    row: CfRow,
    installed: AddonManager.InstalledAddon?,
    updateAvailable: Boolean,
    progress: Float?,
    onInstall: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    color = TITLE_COLOR,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val installedLabel = installed?.let {
                    " · 已安装 ${if (it.version.isBlank()) "版本未知" else "v${it.version}"}"
                }.orEmpty()
                Text(
                    "${row.origin} · ${row.slug}$installedLabel",
                    color = WowSubtle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                progress != null -> Text("${(progress * 100).toInt()}%", color = WowGold, fontSize = 12.sp)
                else -> TextButton(onClick = onInstall) {
                    Text(
                        when {
                            updateAvailable -> "更新"
                            installed != null -> "重新安装"
                            else -> "安装"
                        },
                    )
                }
            }
        }
        if (row.summary.isNotBlank()) {
            Text(
                row.summary,
                color = WowMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun installedDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
