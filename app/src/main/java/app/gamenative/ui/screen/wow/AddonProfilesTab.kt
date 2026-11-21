package app.gamenative.ui.screen.wow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PROFILE_TITLE = Color(0xFFE2E8F0)
private val PROFILE_ACTIVE = Color(0xFF4CAF50)
private val PROFILE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private const val PROFILES_HELP =
    "这些插件不能由外部切换内部配置。方案会整套替换 WTF（WA、Plater、Details、VuhDo 等插件的设置与" +
        "SavedVariables、布局、按键、宏、Config.wtf），再同步插件启用列表；切换前请先退出游戏。"

private const val TAKE_OVER_HELP =
    "开启时启动前写入 synchronize* 0，让本地 WTF 生效；代价是这些设置不再跨电脑同步。"

/**
 * Configuration profiles: each one stores the WTF snapshot and the add-on set that goes with it. All store calls
 * run on IO; the parent uses [onBusyChange] to keep installs and toggles out of an in-flight switch.
 */
@Composable
internal fun AddonProfilesTab(
    gameRoot: File,
    addonsDir: File,
    blocked: Boolean,
    onBusyChange: (Boolean) -> Unit,
    onProfileApplied: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ConfigProfileStore.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ConfigProfileStore.Profile?>(null) }
    var pendingSwitch by remember { mutableStateOf<ConfigProfileStore.Profile?>(null) }
    var pendingDelete by remember { mutableStateOf<ConfigProfileStore.Profile?>(null) }

    // The tab can be left while a call is in flight; never leave the parent's mutual exclusion stuck on.
    DisposableEffect(Unit) {
        onDispose { onBusyChange(false) }
    }

    val currentName = state?.let { loaded -> loaded.profiles.firstOrNull { it.id == loaded.activeId }?.name }

    fun start(label: String) {
        busy = true
        busyLabel = label
        progress = null
        error = null
        onBusyChange(true)
    }

    fun finish() {
        progress = null
        busyLabel = ""
        busy = false
        onBusyChange(false)
    }

    fun reload() {
        if (busy) return
        start("正在读取方案…")
        scope.launch {
            try {
                state = withContext(Dispatchers.IO) { ConfigProfileStore.load(gameRoot, addonsDir) }
                loadFailed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadFailed = true
                error = "读取方案失败：${e.message}"
            } finally {
                finish()
            }
        }
    }

    LaunchedEffect(gameRoot.absolutePath, addonsDir.absolutePath) {
        start("正在读取方案…")
        try {
            state = withContext(Dispatchers.IO) { ConfigProfileStore.load(gameRoot, addonsDir) }
            loadFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
            error = "读取方案失败：${e.message}"
        } finally {
            finish()
        }
    }

    fun openCreate() {
        createOpen = true
        nameInput = ""
        nameError = null
    }

    fun closeNameDialog() {
        if (busy) return
        createOpen = false
        renameTarget = null
        nameInput = ""
        nameError = null
    }

    fun submitName() {
        val renaming = renameTarget
        val name = nameInput
        if (renaming == null) {
            start("正在保存当前配置…")
            scope.launch {
                try {
                    val next = withContext(Dispatchers.IO) {
                        ConfigProfileStore.create(gameRoot, addonsDir, name) { value -> progress = value }
                    }
                    state = next
                    createOpen = false
                    nameInput = ""
                    nameError = null
                    onProfileApplied()
                    SnackbarManager.show("已创建方案「${name.trim()}」")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    nameError = e.message ?: "创建失败"
                } finally {
                    finish()
                }
            }
            return
        }
        start("正在重命名…")
        scope.launch {
            try {
                state = withContext(Dispatchers.IO) {
                    ConfigProfileStore.rename(gameRoot, addonsDir, renaming.id, name)
                }
                renameTarget = null
                nameInput = ""
                nameError = null
                SnackbarManager.show("已重命名为「${name.trim()}」")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nameError = e.message ?: "重命名失败"
            } finally {
                finish()
            }
        }
    }

    fun activate(profile: ConfigProfileStore.Profile) {
        pendingSwitch = null
        start("正在切换配置…")
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    ConfigProfileStore.activate(gameRoot, addonsDir, profile.id) { value -> progress = value }
                }
                state = next
                onProfileApplied()
                SnackbarManager.show("已切换到「${profile.name}」")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "切换失败"
            } finally {
                finish()
            }
        }
    }

    fun delete(profile: ConfigProfileStore.Profile) {
        pendingDelete = null
        start("正在删除…")
        scope.launch {
            try {
                state = withContext(Dispatchers.IO) {
                    ConfigProfileStore.delete(gameRoot, addonsDir, profile.id)
                }
                SnackbarManager.show("已删除方案「${profile.name}」")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "删除失败"
            } finally {
                finish()
            }
        }
    }

    fun setTakeOver(enabled: Boolean) {
        if (busy) return
        start("正在更新设置…")
        scope.launch {
            try {
                state = withContext(Dispatchers.IO) {
                    ConfigProfileStore.setTakeOverBlizzardSettings(gameRoot, enabled).also {
                        // Off also rewrites Config.wtf right away, so the next launch syncs again.
                        ConfigProfileStore.applyLaunchPolicy(gameRoot)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "更新失败"
            } finally {
                finish()
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(PROFILES_HELP, color = WowSubtle, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "当前使用：${currentName ?: "未归属任何方案"}",
            color = PROFILE_TITLE,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )

        if (busy) {
            LinearProgressIndicator(
                color = WowGold,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Text(
                progress?.let { "$busyLabel ${(it * 100).toInt()}%" } ?: busyLabel,
                color = WowMuted,
                fontSize = 11.sp,
            )
        }

        error?.let {
            Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (loadFailed) {
            TextButton(onClick = { reload() }, enabled = !busy) { Text("重试") }
        }

        val loaded = state
        when {
            loaded == null -> Unit
            loaded.profiles.isEmpty() -> {
                Spacer(Modifier.height(8.dp))
                Text("还没有方案。当前 WTF 会随第一个方案保存。", color = WowSubtle, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Button(onClick = { openCreate() }, enabled = !busy && !blocked) {
                    Text("把当前配置保存为方案")
                }
            }
            else -> {
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(loaded.profiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            active = profile.id == loaded.activeId,
                            blocked = blocked,
                            busy = busy,
                            onUse = { pendingSwitch = profile },
                            onRename = {
                                renameTarget = profile
                                nameInput = profile.name
                                nameError = null
                            },
                            onDelete = { pendingDelete = profile },
                        )
                        HorizontalDivider(color = Color(0xFF1E2D44))
                    }
                }
                TextButton(onClick = { openCreate() }, enabled = !busy && !blocked) {
                    Text("新建方案（保存当前配置）")
                }
            }
        }

        Text(
            "每个非当前方案会保留一份 WTF 快照，占用与 WTF 相同的空间。",
            color = WowSubtle,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("接管暴雪按键/宏/界面设置", color = PROFILE_TITLE, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = loaded?.takeOverBlizzardSettings ?: true,
                onCheckedChange = { setTakeOver(it) },
                enabled = !busy && loaded != null,
            )
        }
        Text(TAKE_OVER_HELP, color = WowSubtle, fontSize = 11.sp)
    }

    if (createOpen || renameTarget != null) {
        val renaming = renameTarget
        AlertDialog(
            onDismissRequest = { closeNameDialog() },
            title = { Text(if (renaming == null) "新建配置方案" else "重命名方案") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            nameError = null
                        },
                        label = { Text("方案名称") },
                        placeholder = { Text("例如：手柄 / 键盘") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (renaming == null) {
                        val activeName = currentName
                        if (activeName != null) {
                            Text(
                                "新方案从当前配置开始；当前 WTF 会先复制保存到「$activeName」，文件较大时需要几秒。",
                                color = WowMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    nameError?.let {
                        Text(it, color = WowError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (busy) {
                        LinearProgressIndicator(
                            color = WowGold,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        Text(
                            progress?.let { "$busyLabel ${(it * 100).toInt()}%" } ?: busyLabel,
                            color = WowMuted,
                            fontSize = 11.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { submitName() }, enabled = !busy && nameInput.isNotBlank()) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { closeNameDialog() }, enabled = !busy) { Text("取消") }
            },
        )
    }

    pendingSwitch?.let { target ->
        val activeName = currentName
        AlertDialog(
            onDismissRequest = { if (!busy) pendingSwitch = null },
            title = { Text("切换到「${target.name}」？") },
            text = {
                Text(
                    if (activeName == null) {
                        "当前 WTF 会先另存为「当前配置」，再替换成「${target.name}」的快照。请确认游戏已完全退出。"
                    } else {
                        "当前 WTF 会先保存到「$activeName」，再替换成「${target.name}」的快照。请确认游戏已完全退出。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { activate(target) }, enabled = !busy) { Text("切换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }, enabled = !busy) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text("删除方案「${target.name}」？") },
            text = { Text("会删除该方案的 WTF 快照和插件列表，无法恢复。") },
            confirmButton = {
                TextButton(onClick = { delete(target) }, enabled = !busy) {
                    Text("删除", color = WowError)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, enabled = !busy) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ConfigProfileStore.Profile,
    active: Boolean,
    blocked: Boolean,
    busy: Boolean,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                profile.name,
                color = PROFILE_TITLE,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (active) Text("使用中", color = PROFILE_ACTIVE, fontSize = 11.sp)
        }
        val detail = when {
            active -> "插件启用状态实时生效"
            !profile.hasSnapshot -> "不完整：没有 WTF 快照，无法切换"
            else -> {
                val saved = Instant.ofEpochMilli(profile.updatedAt).atZone(ZoneId.systemDefault())
                    .toLocalDate().format(PROFILE_DATE)
                "${profile.enabledAddons.size} 个插件可用 · 保存于 $saved"
            }
        }
        Text(
            detail,
            color = if (!active && !profile.hasSnapshot) WowError else WowSubtle,
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!active) {
                TextButton(onClick = onUse, enabled = profile.hasSnapshot && !busy && !blocked) { Text("使用") }
            }
            TextButton(onClick = onRename, enabled = !busy && !blocked) { Text("重命名") }
            if (!active) {
                TextButton(onClick = onDelete, enabled = !busy && !blocked) { Text("删除") }
            }
        }
    }
}
