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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private val TITLE_COLOR = Color(0xFFE2E8F0)
private val SUCCESS = Color(0xFF4CAF50)

/**
 * Add-on browser and updater. Installs replace the add-on's folder in place through [AddonImporter], so a running
 * game keeps its SavedVariables while the code is refreshed.
 */
@Composable
internal fun AddonManagerDialog(
    addonsDir: File,
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
    var updates by remember { mutableStateOf<Map<String, WagoAddonSource.Release>>(emptyMap()) }
    var checkWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var actionError by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<WagoAddonSource.SearchResult>>(emptyList()) }
    var searchPage by remember { mutableStateOf(0) }
    var searchLastPage by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    suspend fun loadInstalled(check: Boolean) {
        checkWarnings = emptyList()
        loadingInstalled = true
        installed = withContext(Dispatchers.IO) { AddonManager.installed(context, addonsDir) }
        loadingInstalled = false
        if (check && installed.any { it.slug != null }) {
            checkingUpdates = true
            try {
                val result = AddonManager.checkUpdates(context, installed)
                updates = result.updates
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
        }
    }

    LaunchedEffect(refreshKey) {
        loadInstalled(true)
    }

    fun install(slug: String, key: String, checkAfter: Boolean) {
        if (busy.containsKey(key)) return
        actionError = null
        busy = busy + (key to 0f)
        scope.launch {
            try {
                val result = AddonManager.install(context, addonsDir, slug) { progress ->
                    busy = busy + (key to progress)
                }
                SnackbarManager.show("已安装 ${result.addon.name}（${result.folders.joinToString()}）")
                loadInstalled(checkAfter)
            } catch (e: Exception) {
                Timber.e(e, "Add-on install failed: $slug")
                actionError = "安装失败：${e.message}"
            } finally {
                busy = busy - key
            }
        }
    }

    fun updateAll() {
        if (batchUpdating) return
        val targets = installed
            .filter { it.slug != null && updates.containsKey(it.slug) }
            .distinctBy { it.slug }
        if (targets.isEmpty()) return
        actionError = null
        scope.launch {
            batchUpdating = true
            var updated = 0
            val failed = ArrayList<String>()
            targets.forEach { addon ->
                val slug = addon.slug ?: return@forEach
                busy = busy + (slug to 0f)
                try {
                    AddonManager.install(context, addonsDir, slug) { progress ->
                        busy = busy + (slug to progress)
                    }
                    updated++
                } catch (e: Exception) {
                    Timber.e(e, "Add-on update failed: $slug")
                    failed += addon.title
                } finally {
                    busy = busy - slug
                }
            }
            loadInstalled(true)
            batchUpdating = false
            SnackbarManager.show(
                if (failed.isEmpty()) "已更新 $updated 个插件"
                else "已更新 $updated 个插件，${failed.size} 个失败：${failed.joinToString()}",
            )
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

    val bySlug = installed.mapNotNull { addon -> addon.slug?.let { it to addon } }.toMap()
    val updateCount = installed.mapNotNull { it.slug }.distinct().count { updates.containsKey(it) }

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
                }
                HorizontalDivider(color = Color(0xFF1E2D44))
                Spacer(Modifier.height(6.dp))

                if (tab == 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(
                            onClick = { scope.launch { loadInstalled(true) } },
                            enabled = !loadingInstalled && !checkingUpdates && !batchUpdating,
                        ) { Text("检查更新") }
                        TextButton(onClick = onImportZip, enabled = !batchUpdating && busy.isEmpty()) {
                            Text("从 ZIP 安装")
                        }
                        TextButton(
                            onClick = { scope.launch { loadInstalled(false) } },
                            enabled = !loadingInstalled && !batchUpdating,
                        ) { Text("刷新") }
                    }

                    if (checkingUpdates || batchUpdating) {
                        LinearProgressIndicator(
                            color = WowGold,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }

                    when {
                        loadingInstalled && installed.isEmpty() -> BoxProgress("正在读取已安装插件…")
                        installed.isEmpty() -> Text(
                            "还没有安装插件。到“搜索”里找，或点“从 ZIP 安装”。",
                            color = WowSubtle,
                            fontSize = 12.sp,
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                            items(installed, key = { it.folder }) { addon ->
                                InstalledRow(
                                    addon = addon,
                                    update = addon.slug?.let { updates[it] },
                                    progress = addon.slug?.let { busy[it] },
                                    onUpdate = {
                                        addon.slug?.let { install(it, it, checkAfter = true) }
                                    },
                                )
                                HorizontalDivider(color = Color(0xFF1E2D44))
                            }
                        }
                    }

                    if (updateCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = ::updateAll,
                            enabled = !batchUpdating && busy.isEmpty() && !checkingUpdates,
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
                } else {
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
                                    onInstall = { install(result.slug, result.slug, checkAfter = true) },
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
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
    update: WagoAddonSource.Release?,
    progress: Float?,
    onUpdate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    addon.title,
                    color = TITLE_COLOR,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${addon.folder} · ${if (addon.version.isBlank()) "版本未知" else "v${addon.version}"}",
                    color = WowSubtle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                progress != null -> Text("${(progress * 100).toInt()}%", color = WowGold, fontSize = 12.sp)
                update != null -> TextButton(onClick = onUpdate) { Text("更新") }
                addon.slug == null -> Text("手动", color = WowMuted, fontSize = 11.sp)
                else -> Text("已最新", color = SUCCESS, fontSize = 11.sp)
            }
        }
        update?.let {
            Text(
                "${addon.version.ifBlank { "当前版本" }} → ${it.version}",
                color = WowMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                Text(
                    listOf(result.author, result.updated).filter { it.isNotBlank() }
                        .joinToString(" · ").ifBlank { result.slug },
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
