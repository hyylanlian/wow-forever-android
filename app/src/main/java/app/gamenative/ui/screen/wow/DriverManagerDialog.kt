package app.gamenative.ui.screen.wow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

private val DRIVER_TITLE = Color(0xFFE2E8F0)

/** Lists bundled, downloaded and online Turnip drivers; downloads or deletes them without leaving the launcher. */
@Composable
internal fun DriverManagerDialog(
    family: String,
    active: DriverEntry,
    onSelect: (DriverEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf<List<InstalledDriver>>(emptyList()) }
    var installedLoaded by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf<Map<String, DownloadedDriver>>(emptyMap()) }
    var catalog by remember { mutableStateOf<RemoteCatalog?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<InstalledDriver?>(null) }

    val recommended = DriverCatalog.candidates(family).first { it.recommended }

    suspend fun reloadInstalled() {
        val pair = withContext(Dispatchers.IO) {
            DriverCatalog.installed(context) to DriverCatalog.downloaded(context)
        }
        installed = pair.first
        downloaded = pair.second
        installedLoaded = true
    }

    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        refreshError = null
        try {
            DriverCatalog.fetchRemote(context)
                .onSuccess { catalog = it }
                .onFailure { error ->
                    refreshError = "无法刷新驱动目录：${error.message}"
                }
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) {
            Triple(
                DriverCatalog.installed(context),
                DriverCatalog.downloaded(context),
                DriverCatalog.cachedRemote(context),
            )
        }
        installed = cached.first
        downloaded = cached.second
        catalog = cached.third
        installedLoaded = true
        val cachedCatalog = catalog
        if (cachedCatalog == null ||
            cachedCatalog.version < DriverCatalog.MIN_CATALOG_VERSION ||
            DriverCatalog.catalogAgeMs(context) > DriverCatalog.CATALOG_TTL_MS
        ) {
            refresh()
        }
    }

    fun use(option: DriverOption) {
        onSelect(
            DriverEntry(
                name = option.name,
                family = option.family,
                tuDebug = option.tuDebug,
                note = option.note,
                recommended = option.recommended,
                bundled = option.bundled,
                turnip = option.turnip,
            ),
        )
    }

    fun download(option: DriverOption) {
        val remote = option.remote ?: return
        if (progress.containsKey(option.name)) return
        actionError = null
        progress = progress + (option.name to 0f)
        scope.launch {
            try {
                DriverCatalog.download(context, remote) { value ->
                    progress = progress + (option.name to value)
                }
                reloadInstalled()
                SnackbarManager.show("已下载 ${option.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Driver download failed: ${option.name}")
                actionError = "下载 ${option.name} 失败：${e.message}"
            } finally {
                progress = progress - option.name
            }
        }
    }

    val activeInstalled = installed.firstOrNull { it.name == active.name }
    val missing = installedLoaded && !DriverCatalog.isBundled(active.name) && activeInstalled == null
    val options = DriverCatalog.mergeOptions(
        family = family,
        bundled = DriverCatalog.candidates(family),
        installed = installed,
        remote = catalog?.drivers.orEmpty(),
        downloaded = downloaded,
        active = active,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("图形驱动（$family）", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("当前：${active.name}", color = DRIVER_TITLE, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val markers = buildList {
                    if (active.recommended) add("推荐")
                    if (DriverCatalog.isBundled(active.name)) add("内置")
                    if (activeInstalled != null) add("已安装")
                }
                if (markers.isNotEmpty()) {
                    Text(markers.joinToString(" · "), color = WowGold, fontSize = 11.sp)
                }
                Text("TU_DEBUG=${active.tuDebug}", color = WowSubtle, fontSize = 11.sp)
                if (active.note.isNotBlank()) {
                    Text(active.note, color = WowMuted, fontSize = 11.sp)
                }
                if (active.name != recommended.name) {
                    TextButton(onClick = { onSelect(recommended) }) { Text("恢复推荐") }
                }
                if (missing) {
                    Text(
                        "驱动文件缺失，启动时将自动回退到推荐驱动",
                        color = WowError,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                HorizontalDivider(color = Color(0xFF1E2D44), modifier = Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("可用驱动", color = DRIVER_TITLE, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { refresh() } }, enabled = !refreshing) {
                        Text("刷新")
                    }
                }
                when {
                    catalog == null -> Text("尚未获取在线目录", color = WowSubtle, fontSize = 11.sp)
                    catalog?.drivers?.isEmpty() == true -> Text("在线目录为空", color = WowSubtle, fontSize = 11.sp)
                    else -> Text(
                        "目录更新：${catalog?.updatedAt.orEmpty()}",
                        color = WowSubtle,
                        fontSize = 11.sp,
                    )
                }
                if (refreshing) {
                    LinearProgressIndicator(
                        color = WowGold,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                refreshError?.let {
                    Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }

                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(options, key = { it.name }) { option ->
                        DriverRow(
                            option = option,
                            progress = progress[option.name],
                            onUse = { use(option) },
                            onDownload = { download(option) },
                            onDelete = {
                                option.installed?.takeIf { !option.bundled && !option.active }
                                    ?.let { pendingDelete = it }
                            },
                        )
                        HorizontalDivider(color = Color(0xFF1E2D44))
                    }
                }

                actionError?.let {
                    Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    "切换驱动后下次启动生效；所选驱动缺失时启动会自动回退到推荐驱动。",
                    color = WowSubtle,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    pendingDelete?.let { driver ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除驱动 ${driver.name}？") },
            text = { Text("仅删除本机下载的驱动包，不影响游戏文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        actionError = null
                        scope.launch {
                            try {
                                val removed = withContext(Dispatchers.IO) {
                                    DriverCatalog.delete(context, family, driver.name)
                                }
                                if (removed) {
                                    reloadInstalled()
                                    SnackbarManager.show("已删除 ${driver.name}")
                                } else {
                                    actionError = "删除失败"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "Driver delete failed: ${driver.name}")
                                actionError = "删除失败"
                            }
                        }
                    },
                ) { Text("删除", color = WowError) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DriverRow(
    option: DriverOption,
    progress: Float?,
    onUse: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val markers = buildList {
                    if (option.recommended) add("推荐")
                    if (option.bundled) add("内置")
                    if (option.installed != null) add("已安装")
                    if (option.active) add("当前")
                }.joinToString(" · ")
                Text(
                    option.name + if (markers.isNotEmpty()) "（$markers）" else "",
                    color = if (option.active) WowGold else DRIVER_TITLE,
                    fontSize = 13.sp,
                    fontWeight = if (option.active) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (option.note.isNotBlank()) {
                    Text(
                        option.note,
                        color = WowSubtle,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val size = formatSize(option.installed?.sizeBytes ?: option.remote?.size ?: -1L)
                val detail = when {
                    option.installed != null -> {
                        val version = option.installed.version.ifBlank { "未知" }
                        listOf("v$version", size).filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    option.remote != null -> size
                    option.bundled -> "首次启动自动安装"
                    else -> ""
                }
                if (detail.isNotBlank()) {
                    Text(detail, color = WowMuted, fontSize = 11.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                when {
                    progress != null -> Text("${(progress * 100).toInt()}%", color = WowGold, fontSize = 12.sp)
                    option.active -> Text("已选", color = WowGold, fontSize = 11.sp)
                    option.installed != null || option.bundled -> {
                        TextButton(onClick = onUse) { Text("使用") }
                        if (option.remote != null) {
                            DriverDownloadAction(option, onDownload)
                        }
                    }
                    option.remote != null -> DriverDownloadAction(option, onDownload)
                    else -> Text("文件缺失", color = WowError, fontSize = 11.sp)
                }
                if (option.installed != null && !option.bundled && !option.active) {
                    TextButton(onClick = onDelete) { Text("删除", color = WowError) }
                }
            }
        }
    }
}

@Composable
private fun DriverDownloadAction(option: DriverOption, onDownload: () -> Unit) {
    val remote = option.remote ?: return
    val downloaded = option.downloaded
    val current = downloaded?.sha256 != null &&
        remote.sha256 != null &&
        downloaded.sha256.equals(remote.sha256, true) &&
        option.installed != null
    if (current) {
        Text("已最新", color = WowMuted, fontSize = 11.sp)
    } else {
        val label = when {
            option.installed == null -> "下载"
            downloaded == null -> "重新下载"
            else -> "更新"
        }
        TextButton(onClick = onDownload) { Text(label) }
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes > 0) String.format(Locale.US, "%.1f MB", bytes / 1048576f) else ""
