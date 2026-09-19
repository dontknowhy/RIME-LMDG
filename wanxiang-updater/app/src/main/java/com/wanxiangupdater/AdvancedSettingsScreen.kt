@file:OptIn(ExperimentalMaterial3Api::class)

package com.wanxiangupdater

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val TEXT_TYPES = setOf(
    "str", "int", "float", "number", "multiline_str", "list_text", "raw_yaml"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen() {
    val context = LocalContext.current
    val sharedPref = context.getSharedPreferences("WanxiangPrefs", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()

    val targets = remember { loadDeployPaths(sharedPref) }

    var selectedTarget by remember { mutableStateOf(targets.firstOrNull() ?: "DEFAULT") }
    var access by remember { mutableStateOf<RimeFileAccess?>(null) }
    var workspace by remember { mutableStateOf("") }
    var managedFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var nav by remember { mutableStateOf<List<NavCategory>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf("patch") }
    var page by remember { mutableStateOf<PagePlan?>(null) }

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var navExpanded by remember { mutableStateOf(true) }
    var syncNonce by remember { mutableStateOf(0) }
    var reloadNonce by remember { mutableStateOf(0) }

    var scanErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var conflictLines by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var rawDialogFile by remember { mutableStateOf<String?>(null) }
    var rawText by remember { mutableStateOf("") }
    var rawError by remember { mutableStateOf("") }
    var rawSaving by remember { mutableStateOf(false) }

    val textState = remember { mutableStateMapOf<String, String>() }
    val boolState = remember { mutableStateMapOf<String, Int>() }
    val selectState = remember { mutableStateMapOf<String, String>() }
    val dynamicStates = remember { mutableStateMapOf<String, DynamicState>() }
    val dynamicOriginals = remember { mutableStateMapOf<String, String>() }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun runActionBtn(field: PageField) {
        if (field.path == "switcher/save_options") {
            scope.launch {
                AdvancedSettingsEngine.importSwitches(context, workspace)
                    .onSuccess { names ->
                        textState[field.path] = names.joinToString("\n")
                        toast("已提取 ${names.size} 个开关，请保存")
                    }
                    .onFailure { toast("提取失败：${it.message}") }
            }
        }
    }

    // 切换目标：建访问器 -> 拉取工作区 -> 导航
    LaunchedEffect(selectedTarget, syncNonce) {
        loading = true
        errorText = ""
        statusText = ""
        scanErrors = emptyList()
        conflictLines = emptyList()
        page = null
        selectedFile = null
        nav = emptyList()

        val targetAccess = createRimeAccess(context, selectedTarget)
        if (targetAccess == null) {
            errorText = "无法访问该目标目录"
            loading = false
            return@LaunchedEffect
        }
        access = targetAccess
        val ws = WorkspaceSync.workspaceDir(context).absolutePath
        workspace = ws

        val filesResult = AdvancedSettingsEngine.managedFiles(context)
        val files = filesResult.getOrElse {
            errorText = "加载 Python 引擎失败：${it.message}"
            loading = false
            return@LaunchedEffect
        }
        managedFiles = files

        val syncResult = withContext(Dispatchers.IO) {
            WorkspaceSync.syncFromTarget(context, targetAccess, files)
        }
        if (syncResult.pulled.isEmpty()) {
            errorText = "目标目录中未找到受管配置文件，请确认这是万象 Rime 用户目录。"
            loading = false
            return@LaunchedEffect
        }
        if (syncResult.errors.isNotEmpty()) {
            statusText = "部分文件拉取失败：" + syncResult.errors.joinToString("；")
        }

        AdvancedSettingsEngine.scan(context, ws).onSuccess { json ->
            val array = json.optJSONArray("errors")
            scanErrors = buildList {
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add("${item.optString("file")}: ${item.optString("error")}")
                    }
                }
            }
        }

        AdvancedSettingsEngine.getNav(context, ws)
            .onSuccess { nav = AdvancedSettingsParser.parseNav(it) }
            .onFailure { errorText = "读取导航失败：${it.message}" }

        selectedFile = nav.asSequence()
            .flatMap { it.files.asSequence() }
            .firstOrNull { it.exists }
            ?.file
        loading = false
    }

    // 载入当前文件页面
    LaunchedEffect(selectedFile, mode, reloadNonce, workspace) {
        val file = selectedFile ?: return@LaunchedEffect
        if (workspace.isBlank()) return@LaunchedEffect
        loading = true
        errorText = ""
        AdvancedSettingsEngine.loadPage(context, workspace, file, mode)
            .onSuccess { json ->
                val parsed = AdvancedSettingsParser.parsePage(json)
                page = parsed
                textState.clear()
                boolState.clear()
                selectState.clear()
                dynamicStates.clear()
                dynamicOriginals.clear()
                parsed.sections.forEach { section ->
                    section.fields.forEach { field ->
                        if (field.dynamic != null) {
                            val state = buildDynamicState(field)
                            if (state != null) {
                                dynamicStates[field.path] = state
                                dynamicOriginals[field.path] = state.signature()
                            }
                            return@forEach
                        }
                        when (field.type) {
                            "bool" -> boolState[field.path] =
                                (field.value as? Boolean)?.let { if (it) 1 else 0 } ?: -1
                            "select" -> selectState[field.path] = field.value?.toString() ?: ""
                            in TEXT_TYPES -> textState[field.path] = field.text
                        }
                    }
                }
                AdvancedSettingsEngine.detectConflicts(context, workspace, file)
                    .onSuccess { conflictLines = it }
                    .onFailure { conflictLines = emptyList() }
            }
            .onFailure { errorText = "加载页面失败：${it.message}" }
        loading = false
    }

    fun save() {
        val current = page ?: return
        val edits = JSONArray()
        current.sections.forEach { section ->
            section.fields.forEach { field ->
                if (field.dynamic != null) {
                    val state = dynamicStates[field.path]
                    if (state != null && state.signature() != dynamicOriginals[field.path]) {
                        buildDynamicEdit(field, state)?.let { edits.put(it) }
                    }
                    return@forEach
                }
                when (field.type) {
                    "bool" -> {
                        val cur = boolState[field.path] ?: -1
                        val orig = (field.value as? Boolean)?.let { if (it) 1 else 0 } ?: -1
                        if (cur != orig && cur != -1) {
                            edits.put(
                                JSONObject().apply {
                                    put("path", field.path)
                                    put("type", "bool")
                                    put("value", cur == 1)
                                }
                            )
                        }
                    }
                    "select" -> {
                        val cur = selectState[field.path] ?: ""
                        val orig = field.value?.toString() ?: ""
                        if (cur != orig && cur.isNotBlank() && cur != "默认/不指定") {
                            edits.put(
                                JSONObject().apply {
                                    put("path", field.path)
                                    put("type", "select")
                                    put("value", cur)
                                }
                            )
                        }
                    }
                    in TEXT_TYPES -> {
                        val cur = textState[field.path] ?: field.text
                        if (cur != field.text) {
                            edits.put(
                                JSONObject().apply {
                                    put("path", field.path)
                                    put("type", field.type)
                                    put("text", cur)
                                }
                            )
                        }
                    }
                }
            }
        }

        if (edits.length() == 0) {
            toast("没有检测到改动")
            return
        }

        val targetAccess = access ?: return
        val file = current.file
        scope.launch {
            saving = true
            statusText = ""
            AdvancedSettingsEngine.savePage(context, workspace, file, mode, edits.toString())
                .onSuccess { result ->
                    val changed = result.optJSONArray("changed")?.let { array ->
                        buildList { for (i in 0 until array.length()) add(array.optString(i)) }
                    } ?: emptyList()
                    val pushErrors = withContext(Dispatchers.IO) {
                        WorkspaceSync.pushChanges(context, targetAccess, changed)
                    }
                    statusText = if (pushErrors.isEmpty()) {
                        (result.optString("summary").ifBlank { "保存成功" }) +
                            "。请到输入法中手动执行「部署」以生效。"
                    } else {
                        "写回目标部分失败：" + pushErrors.joinToString("；")
                    }
                    toast(if (pushErrors.isEmpty()) "保存成功" else "写回部分失败")
                    reloadNonce++
                }
                .onFailure {
                    statusText = "保存失败：${it.message}"
                    toast("保存失败")
                }
            saving = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      // 固定在顶部的搜索框：支持中文描述、英文路径/类型，粗粒度过滤。
      Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
      ) {
          SimpleTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              placeholder = "搜索字段：中文描述 / 英文路径 / 类型",
              modifier = Modifier.weight(1f)
          )
          if (searchQuery.isNotBlank()) {
              TextButton(onClick = { searchQuery = "" }) { Text("清除", fontSize = 12.sp) }
          }
      }
      LazyColumn(
          modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
          contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
      ) {
      item(key = "header") {
        Column {
        Text("⚙️ 高级设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MorandiDarkGreen)
        Text(
            "直接编辑万象 Rime 配置。保存后请到输入法执行部署。",
            fontSize = 11.sp,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(10.dp))
        }
      }

      item(key = "controls") {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = CardDefaults.outlinedCardBorder(true),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("目标目录", fontSize = 12.sp, color = Color.Gray)
                TargetSelector(
                    targets = targets,
                    selected = selectedTarget,
                    onSelect = { selectedTarget = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("保存模式", fontSize = 12.sp, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = mode == "patch",
                        enabled = !loading,
                        onClick = {
                            if (mode != "patch") {
                                mode = "patch"
                                statusText = "已切换到补丁模式，正在重新载入…"
                            }
                        },
                        label = { Text("补丁模式", fontSize = 12.sp) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == "direct",
                        enabled = !loading,
                        onClick = {
                            if (mode != "direct") {
                                mode = "direct"
                                statusText = "已切换到直写模式，正在重新载入…"
                            }
                        },
                        label = { Text("直写模式", fontSize = 12.sp) }
                    )
                }
                Text(
                    if (mode == "patch") "改动写入 *.custom.yaml，可随时恢复。" else "⚠️ 直接修改源文件，请谨慎操作。",
                    fontSize = 10.sp,
                    color = if (mode == "patch") Color.Gray else Color(0xFFC46A6A)
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { syncNonce++ },
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    ) { Text("🔄 重新同步", fontSize = 12.sp) }
                    Button(
                        onClick = { save() },
                        enabled = !saving && page != null,
                        colors = ButtonDefaults.buttonColors(containerColor = MorandiGreen),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (saving) "保存中…" else "💾 保存", fontSize = 12.sp) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        val file = page?.file ?: return@OutlinedButton
                        rawDialogFile = file
                        rawError = ""
                        scope.launch {
                            AdvancedSettingsEngine.readRaw(context, workspace, file)
                                .onSuccess { rawText = it }
                                .onFailure { rawError = "读取失败：${it.message}" }
                        }
                    },
                    enabled = !saving && page != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📝 原始文本编辑（修复重复键等问题）", fontSize = 12.sp) }
            }
        }
      }

      if (loading || saving) {
        item(key = "progress") {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (saving) "正在保存…" else "正在载入…", fontSize = 12.sp, color = MorandiDarkGreen)
            }
        }
      }

      if (scanErrors.isNotEmpty()) {
        item(key = "scan") {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4F4)),
                border = CardDefaults.outlinedCardBorder(true),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("⚠️ 检测到 YAML 解析问题", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC46A6A))
                    scanErrors.forEach { Text(it, fontSize = 10.sp, color = Color(0xFFC46A6A)) }
                    Text("可选中对应文件后用下方“原始文本编辑”修复。", fontSize = 10.sp, color = Color.Gray)
                }
            }
        }
      }

      if (conflictLines.isNotEmpty()) {
        item(key = "conflict") {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBF0)),
                border = CardDefaults.outlinedCardBorder(true),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "ℹ️ 按键复用提示（仅供参考，不影响保存）",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8A6D3B)
                    )
                    conflictLines.take(6).forEach { (severity, line) ->
                        Text(
                            "• $line",
                            fontSize = 10.sp,
                            color = if (severity >= 40) Color(0xFFC97828) else Color(0xFF8A6D3B)
                        )
                    }
                    if (conflictLines.size > 6) {
                        Text("…另有 ${conflictLines.size - 6} 处", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }
        }
      }

      if (nav.isNotEmpty()) {
        item(key = "nav") {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = CardDefaults.outlinedCardBorder(true),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextButton(
                        onClick = { navExpanded = !navExpanded },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            (if (navExpanded) "▼ " else "▶ ") + "文件导航",
                            color = MorandiDarkGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    AnimatedVisibility(visible = navExpanded) {
                        Column {
                            nav.forEach { category ->
                                Text(
                                    category.title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.DarkGray,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                                category.files.forEach { navFile ->
                                    val selected = selectedFile == navFile.file
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = (if (navFile.exists) "● " else "○ ") + navFile.name,
                                            fontSize = 13.sp,
                                            color = when {
                                                selected -> MorandiDarkGreen
                                                navFile.exists -> Color.DarkGray
                                                else -> Color.LightGray
                                            },
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { if (navFile.exists) selectedFile = navFile.file },
                                            enabled = navFile.exists && !loading
                                        ) {
                                            Text(
                                                if (navFile.exists) "编辑" else "缺失",
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
      }

      if (loading) {
        item(key = "loading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("载入中…", fontSize = 12.sp, color = Color.Gray)
            }
        }
      }

      if (errorText.isNotBlank()) {
        item(key = "error") {
            Text("⚠️ $errorText", fontSize = 12.sp, color = Color(0xFFC46A6A))
        }
      }
      if (statusText.isNotBlank()) {
        item(key = "status") {
            Text("✅ $statusText", fontSize = 12.sp, color = MorandiDarkGreen)
        }
      }

      val current = page
      if (current != null) {
        val query = searchQuery.trim()
        val visibleSections = if (query.isBlank()) {
            current.sections
        } else {
            current.sections.mapNotNull { section ->
                val sectionHit = section.title.contains(query, ignoreCase = true)
                val matching = section.fields.filter { fieldMatchesQuery(it, query) }
                when {
                    sectionHit -> section
                    matching.isNotEmpty() -> section.copy(fields = matching)
                    else -> null
                }
            }
        }
        item(key = "currentfile") {
            val matchCount = visibleSections.sumOf { it.fields.size }
            Text(
                "当前文件：${current.file}" +
                    (if (current.custom.isNotBlank()) "（补丁：${current.custom}）" else "") +
                    (if (query.isNotBlank()) "　匹配 $matchCount 项" else ""),
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }
        if (visibleSections.isEmpty()) {
            item(key = "nomatch") {
                Text("未找到匹配「$query」的字段", fontSize = 12.sp, color = Color.Gray)
            }
        }
        itemsIndexed(
            items = visibleSections,
            key = { index, _ -> "section_${current.file}_$index" }
        ) { _, section ->
            Column {
                SectionCard(
                    section = section,
                    textState = textState,
                    boolState = boolState,
                    selectState = selectState,
                    dynamicStates = dynamicStates,
                    mode = mode,
                    onActionBtn = { runActionBtn(it) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
      }
    }
    }

    // 悬浮保存：长列表下无需滚回顶部。
    ExtendedFloatingActionButton(
        onClick = { save() },
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        containerColor = MorandiGreen,
        contentColor = Color.White
    ) {
        Text(if (saving) "保存中…" else "💾 保存", fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    }

    val dialogFile = rawDialogFile
    if (dialogFile != null) {
        AlertDialog(
            onDismissRequest = {
                if (!rawSaving) {
                    rawDialogFile = null
                    rawText = ""
                    rawError = ""
                }
            },
            title = { Text("编辑原文：$dialogFile", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SimpleTextField(
                        value = rawText,
                        onValueChange = { rawText = it },
                        enabled = !rawSaving,
                        singleLine = false,
                        minHeight = 220.dp,
                        monospace = true,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    )
                    if (rawError.isNotBlank()) {
                        Text(rawError, fontSize = 11.sp, color = Color(0xFFC46A6A))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !rawSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = MorandiGreen),
                    onClick = {
                        val targetAccess = access
                        val file = dialogFile
                        scope.launch {
                            rawSaving = true
                            rawError = ""
                            AdvancedSettingsEngine.writeRaw(context, workspace, file, rawText)
                                .onSuccess {
                                    val pushErrors = withContext(Dispatchers.IO) {
                                        if (targetAccess != null) {
                                            WorkspaceSync.pushChanges(context, targetAccess, listOf(file))
                                        } else {
                                            emptyList()
                                        }
                                    }
                                    if (pushErrors.isEmpty()) {
                                        statusText = "已保存原文：$file。请到输入法执行部署。"
                                        rawDialogFile = null
                                        rawText = ""
                                        syncNonce++
                                    } else {
                                        rawError = "写回目标失败：" + pushErrors.joinToString("；")
                                    }
                                }
                                .onFailure { rawError = "保存失败：${it.message}" }
                            rawSaving = false
                        }
                    }
                ) { Text(if (rawSaving) "保存中…" else "保存") }
            },
            dismissButton = {
                TextButton(
                    enabled = !rawSaving,
                    onClick = {
                        rawDialogFile = null
                        rawText = ""
                        rawError = ""
                    }
                ) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TargetSelector(
    targets: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = deployPathDisplayName(selected)
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label, fontSize = 13.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            targets.forEach { target ->
                DropdownMenuItem(
                    text = { Text(deployPathDisplayName(target), fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        if (target != selected) onSelect(target)
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    section: PageSection,
    textState: MutableMap<String, String>,
    boolState: MutableMap<String, Int>,
    selectState: MutableMap<String, String>,
    dynamicStates: MutableMap<String, DynamicState>,
    mode: String,
    onActionBtn: (PageField) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = CardDefaults.outlinedCardBorder(true),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(section.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MorandiDarkGreen)
            if (section.desc.isNotBlank()) {
                Text(section.desc, fontSize = 10.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(6.dp))
            section.fields.forEach { field ->
                FieldEditor(field, textState, boolState, selectState, dynamicStates, mode, onActionBtn)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditor(
    field: PageField,
    textState: MutableMap<String, String>,
    boolState: MutableMap<String, Int>,
    selectState: MutableMap<String, String>,
    dynamicStates: MutableMap<String, DynamicState>,
    mode: String,
    onActionBtn: (PageField) -> Unit
) {
    val algebraTypes = setOf("algebra_patch", "reverse_algebra", "english_algebra", "mixed_algebra")
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(field.title, fontSize = 13.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
        if (field.desc.isNotBlank()) {
            Text(field.desc, fontSize = 10.sp, color = Color.Gray)
        }
        if (field.comment != null) {
            Text(
                "# ${field.comment}",
                fontSize = 10.sp,
                color = MorandiDarkGreen,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        if (field.dynamic != null) {
            val enabled = field.type !in algebraTypes || mode == "patch"
            DynamicFieldEditor(field, dynamicStates[field.path], enabled)
        } else when (field.type) {
            "bool" -> {
                val current = boolState[field.path] ?: -1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoolChip("开", current == 1) { boolState[field.path] = 1 }
                    Spacer(modifier = Modifier.width(6.dp))
                    BoolChip("关", current == 0) { boolState[field.path] = 0 }
                    Spacer(modifier = Modifier.width(6.dp))
                    BoolChip("继承", current == -1) { boolState[field.path] = -1 }
                }
            }
            "select" -> {
                val current = selectState[field.path] ?: ""
                if (field.options.isEmpty()) {
                    SimpleTextField(
                        value = current,
                        onValueChange = { selectState[field.path] = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Selector(
                        options = field.options,
                        current = current,
                        onSelect = { selectState[field.path] = it }
                    )
                }
            }
            "multiline_str", "raw_yaml" -> {
                SimpleTextField(
                    value = textState[field.path] ?: "",
                    onValueChange = { textState[field.path] = it },
                    singleLine = false,
                    minHeight = 120.dp,
                    monospace = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            "list_text" -> {
                Row(verticalAlignment = Alignment.Top) {
                    SimpleTextField(
                        value = textState[field.path] ?: "",
                        onValueChange = { textState[field.path] = it },
                        singleLine = false,
                        minHeight = 90.dp,
                        placeholder = "每行一个值",
                        monospace = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (field.actionBtn != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(onClick = { onActionBtn(field) }) {
                            Text(field.actionBtn, fontSize = 11.sp)
                        }
                    }
                }
            }
            in TEXT_TYPES -> {
                SimpleTextField(
                    value = textState[field.path] ?: "",
                    onValueChange = { textState[field.path] = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                Text(
                    "（类型 “${field.type}” 将在后续版本支持）",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
            }
        }
    }
}

private fun fieldMatchesQuery(field: PageField, query: String): Boolean {
    fun hit(value: String?): Boolean = value != null && value.contains(query, ignoreCase = true)
    if (hit(field.title) || hit(field.desc) || hit(field.path) || hit(field.type) || hit(field.comment)) {
        return true
    }
    if (field.options.any { it.contains(query, ignoreCase = true) }) return true
    // 动态控件用模板标题/键名做粗粒度匹配。
    val dynamic = field.dynamic
    if (dynamic != null) {
        val template = dynamic.optJSONObject("template")
        if (template != null) {
            template.keys().forEach { key ->
                if (key.contains(query, ignoreCase = true)) return true
                val node = template.optJSONObject(key)
                if (node != null && node.optString("title").contains(query, ignoreCase = true)) return true
            }
        }
        val preset = dynamic.optJSONObject("preset_keys")
        if (preset != null && preset.keys().asSequence().any { it.contains(query, ignoreCase = true) }) {
            return true
        }
    }
    return false
}

/**
 * 轻量输入框：高级设置页面有数百个输入控件，Material3 的 OutlinedTextField
 * 组合与布局开销过大，导致滚动掉帧。这里用 BasicTextField 自绘边框，显著降低开销。
 */
@Composable
fun SimpleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minHeight: Dp? = null,
    placeholder: String? = null,
    monospace: Boolean = false
) {
    val textStyle = TextStyle(
        fontSize = if (monospace) 12.sp else 13.sp,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        color = if (enabled) Color.DarkGray else Color.Gray
    )
    Box(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(6.dp))
            .border(1.dp, MorandiBorder, RoundedCornerShape(6.dp))
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = textStyle,
            cursorBrush = SolidColor(MorandiDarkGreen),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .then(if (minHeight != null) Modifier.heightIn(min = minHeight) else Modifier),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, fontSize = 11.sp, color = Color.LightGray)
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun BoolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) }
    )
}

@Composable
private fun Selector(options: List<String>, current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                current.ifBlank { "默认/不指定" },
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text("▼", fontSize = 10.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("默认/不指定", fontSize = 13.sp) },
                onClick = {
                    expanded = false
                    onSelect("默认/不指定")
                }
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}
