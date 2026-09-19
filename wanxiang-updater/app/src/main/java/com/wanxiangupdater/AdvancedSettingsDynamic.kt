@file:OptIn(ExperimentalMaterial3Api::class)

package com.wanxiangupdater

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.stringList(): List<String> = buildList {
    for (i in 0 until length()) add(optString(i))
}

private fun JSONArray.optObjectList(): List<JSONObject> = buildList {
    for (i in 0 until length()) optJSONObject(i)?.let { add(it) }
}

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------

sealed class DynamicState {
    abstract fun signature(): String
}

class DynamicListState(initial: List<String>) : DynamicState() {
    val rows = mutableStateListOf<String>()
    init {
        (initial.ifEmpty { listOf("") }).forEach { rows.add(it) }
    }
    override fun signature(): String = rows.joinToString("\u0001")
}

class DynamicMapState(initial: List<String>) : DynamicState() {
    val rows = mutableStateListOf<String>()
    init {
        (initial.ifEmpty { listOf("") }).forEach { rows.add(it) }
    }
    override fun signature(): String = rows.joinToString("\u0001")
}

class KvRow(var key: String, var value: String)
class DynamicKvState(initial: List<KvRow>) : DynamicState() {
    val rows = mutableStateListOf<KvRow>()
    init { initial.forEach { rows.add(it) } }
    override fun signature(): String = rows.joinToString("\u0001") { "${it.key}=${it.value}" }
}

class SchemaCheckboxState(options: List<Pair<String, String>>, active: List<String>) : DynamicState() {
    val options = options
    val checked = mutableStateMapOf<String, Boolean>()
    init {
        options.forEach { checked[it.first] = it.first in active }
    }
    override fun signature(): String =
        options.filter { checked[it.first] == true }.joinToString(",") { it.first }
}

class AlgebraPatchState(
    val variant: String,
    val schemeOptions: List<String>,
    val auxOptions: List<String>,
    val tiquanSchemes: List<String>,
    val fuzzyOptions: List<Pair<String, String>>,
    initialScheme: String,
    initialAux: String,
    initialTiquan: Boolean,
    initialFuzzy: List<String>,
    initialExtras: List<String>
) : DynamicState() {
    var scheme by mutableStateOf(initialScheme)
    var aux by mutableStateOf(initialAux)
    var tiquan by mutableStateOf(initialTiquan)
    val fuzzy = mutableStateMapOf<String, Boolean>()
    val extras = mutableStateListOf<String>()

    init {
        fuzzyOptions.forEach { fuzzy[it.second] = it.second in initialFuzzy }
        initialExtras.forEach { extras.add(it) }
    }

    override fun signature(): String =
        listOf(
            scheme, aux, tiquan.toString(),
            fuzzyOptions.filter { fuzzy[it.second] == true }.joinToString(",") { it.second },
            extras.joinToString("\u0001")
        ).joinToString("|")
}

sealed class BlockField {
    abstract fun signature(): String
}

class BlockBoolField(initialTri: Int) : BlockField() {
    var tri by mutableStateOf(initialTri)
    override fun signature(): String = "b$tri"
}

class BlockSelectField(val options: List<String>, initial: String) : BlockField() {
    var text by mutableStateOf(initial)
    override fun signature(): String = "s$text"
}

class BlockTextField(val type: String, initial: String) : BlockField() {
    var text by mutableStateOf(initial)
    override fun signature(): String = "t$text"
}

class BlockActionField(
    val presetKeys: Map<String, String>,
    initialKey: String,
    initialValue: String
) : BlockField() {
    var key by mutableStateOf(initialKey)
    var value by mutableStateOf(initialValue)
    override fun signature(): String = "a$key=$value"
}

data class BlockTemplateField(
    val title: String,
    val type: String,
    val desc: String,
    val options: List<String>,
    val presetKeys: Map<String, String>,
    val visibleIf: Map<String, List<String>>
)

class BlockRow(val original: JSONObject) {
    val fields = mutableStateMapOf<String, BlockField>()
}

class DynamicBlockState(val template: LinkedHashMap<String, BlockTemplateField>) : DynamicState() {
    val rows = mutableStateListOf<BlockRow>()
    override fun signature(): String = rows.joinToString("\u0002") { row ->
        row.fields.toSortedMap().entries.joinToString("\u0001") { "${it.key}:${it.value.signature()}" }
    }
}

class AlgebraSchemeState(
    val options: List<String>,
    initial: String,
    val stroke: String? = null,
    val strokeOptions: List<Pair<String, String>> = emptyList(),
    initialStroke: String? = null
) : DynamicState() {
    var scheme by mutableStateOf(initial)
    var strokeValue by mutableStateOf(initialStroke ?: "")
    override fun signature(): String = "$scheme|$strokeValue"
}

// ---------------------------------------------------------------------------
// 渲染
// ---------------------------------------------------------------------------

private const val DYNAMIC_HINT = ""

@Composable
fun DynamicFieldEditor(
    field: PageField,
    state: DynamicState?,
    enabled: Boolean
) {
    if (state == null) {
        Text("（动态控件数据缺失）", fontSize = 11.sp, color = Color.LightGray)
    } else when (state) {
        is DynamicListState -> GenericRowEditor(state.rows, "填入配置", enabled)
        is DynamicMapState -> GenericRowEditor(state.rows, "key: value", enabled)
        is DynamicKvState -> KvEditor(field, state, enabled)
        is SchemaCheckboxState -> SchemaCheckboxEditor(state, enabled)
        is DynamicBlockState -> BlockEditor(state, enabled)
        is AlgebraPatchState -> AlgebraPatchEditor(state, enabled)
        is AlgebraSchemeState -> AlgebraSchemeEditor(field.type, state, enabled)
    }
}

private fun blockTitle(row: BlockRow): String {
    for (key in listOf("name", "accept", "option")) {
        when (val field = row.fields[key]) {
            is BlockTextField -> if (field.text.isNotBlank()) return field.text
            is BlockSelectField -> if (field.text.isNotBlank()) return field.text
            else -> {}
        }
    }
    val options = row.fields["options"]
    if (options is BlockTextField && options.text.isNotBlank()) {
        return options.text.removeSurrounding("[", "]").substringBefore(",").trim()
    }
    return "新规则块"
}

private fun parseStatesList(text: String): List<String> {
    val trimmed = text.trim().removePrefix("[").removeSuffix("]").trim()
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(',', '\n')
        .map { it.trim().trim('"', '\'').trim() }
        .filter { it.isNotEmpty() }
}

/** switches 的 reset 取值随 states/options 数量动态变化。 */
private fun resetStatesOf(row: BlockRow): List<String> {
    val source = (row.fields["states"] ?: row.fields["options"]) as? BlockTextField ?: return emptyList()
    return parseStatesList(source.text)
}

@Composable
private fun BlockResetSelector(
    states: List<String>,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    val labels = listOf("（默认/不设置）") + states.mapIndexed { index, name -> "$index · $name" }
    val index = value.trim().toIntOrNull()
    val current = if (index != null && index in states.indices) labels[index + 1] else labels[0]
    Selector(labels, current, enabled) { label ->
        val newValue = if (label.startsWith("（")) "" else label.substringBefore(" ·").trim()
        onChange(newValue)
    }
}

private fun blockFieldVisible(row: BlockRow, template: BlockTemplateField): Boolean {
    if (template.visibleIf.isEmpty()) return true
    for ((condKey, allowed) in template.visibleIf) {
        val field = row.fields[condKey]
        if (field is BlockSelectField && field.text !in allowed) return false
    }
    return true
}

@Composable
private fun BlockEditor(state: DynamicBlockState, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        state.rows.forEachIndexed { index, row ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAF8)),
                border = CardDefaults.outlinedCardBorder(true),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "📦 规则块: ${blockTitle(row)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MorandiDarkGreen,
                            modifier = Modifier.weight(1f)
                        )
                        RowActionButtons(
                            enabled = enabled,
                            canUp = index > 0,
                            canDown = index < state.rows.size - 1,
                            onUp = { val tmp = state.rows[index]; state.rows[index] = state.rows[index - 1]; state.rows[index - 1] = tmp },
                            onDown = { val tmp = state.rows[index]; state.rows[index] = state.rows[index + 1]; state.rows[index + 1] = tmp },
                            onDelete = { state.rows.removeAt(index) },
                            onAdd = { state.rows.add(index + 1, BlockRow(JSONObject())) }
                        )
                    }
                    state.template.forEach { (key, template) ->
                        if (!blockFieldVisible(row, template)) return@forEach
                        val field = row.fields[key] ?: return@forEach
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(template.title, fontSize = 12.sp, color = Color.DarkGray, fontWeight = FontWeight.Medium)
                        if (template.desc.isNotBlank()) {
                            Text(template.desc, fontSize = 10.sp, color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        when (field) {
                            is BlockBoolField -> Row {
                                FilterChip(selected = field.tri == 1, onClick = { if (enabled) field.tri = 1 }, label = { Text("开", fontSize = 11.sp) })
                                Spacer(modifier = Modifier.width(6.dp))
                                FilterChip(selected = field.tri == 0, onClick = { if (enabled) field.tri = 0 }, label = { Text("关", fontSize = 11.sp) })
                                Spacer(modifier = Modifier.width(6.dp))
                                FilterChip(selected = field.tri == -1, onClick = { if (enabled) field.tri = -1 }, label = { Text("继承", fontSize = 11.sp) })
                            }
                            is BlockSelectField -> Selector(template.options, field.text, enabled) { field.text = it }
                            is BlockActionField -> {
                                val keys = field.presetKeys.keys.toList()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        KeySelector(keys, field.key, enabled) { newKey ->
                                            if (newKey != field.key) {
                                                field.key = newKey
                                                field.value = ""
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                SimpleTextField(
                                    value = field.value,
                                    onValueChange = { if (enabled) field.value = it },
                                    enabled = enabled,
                                    placeholder = field.presetKeys[field.key] ?: "填入值",
                                    monospace = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            is BlockTextField -> {
                                val states = if (key == "reset") resetStatesOf(row) else emptyList()
                                if (states.isNotEmpty()) {
                                    BlockResetSelector(states, field.text, enabled) { field.text = it }
                                } else {
                                    val multiline = field.type == "list_text" || field.type == "raw_yaml"
                                    SimpleTextField(
                                        value = field.text,
                                        onValueChange = { if (enabled) field.text = it },
                                        enabled = enabled,
                                        singleLine = !multiline,
                                        minHeight = if (multiline) 70.dp else null,
                                        monospace = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { if (enabled) state.rows.add(BlockRow(JSONObject())) }, enabled = enabled) {
            Text("➕ 添加规则块", fontSize = 12.sp)
        }
    }
}

@Composable
private fun GenericRowEditor(rows: MutableList<String>, placeholder: String, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SimpleTextField(
                    value = row,
                    onValueChange = { if (enabled) rows[index] = it },
                    enabled = enabled,
                    placeholder = placeholder,
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                RowActionButtons(
                    enabled = enabled,
                    canUp = index > 0,
                    canDown = index < rows.size - 1,
                    onUp = { val tmp = rows[index]; rows[index] = rows[index - 1]; rows[index - 1] = tmp },
                    onDown = { val tmp = rows[index]; rows[index] = rows[index + 1]; rows[index + 1] = tmp },
                    onDelete = { rows.removeAt(index) },
                    onAdd = { rows.add(index + 1, "") }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        TextButton(onClick = { if (enabled) rows.add("") }, enabled = enabled) {
            Text("➕ 添加一行", fontSize = 12.sp)
        }
    }
}

@Composable
private fun KvEditor(field: PageField, state: DynamicKvState, enabled: Boolean) {
    val presetKeys = field.dynamic?.optJSONObject("preset_keys") ?: JSONObject()
    val keys = mutableListOf<String>()
    presetKeys.keys().forEach { keys.add(it) }

    Column(modifier = Modifier.fillMaxWidth()) {
        state.rows.forEachIndexed { index, row ->
            val desc = presetKeys.optString(row.key, "")
            val (kind, selectOptions) = kvKind(desc)
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        KeySelector(keys, row.key, enabled) { newKey ->
                            if (newKey != row.key) {
                                row.key = newKey
                                row.value = ""
                            }
                        }
                    }
                    RowActionButtons(
                        enabled = enabled,
                        canUp = index > 0,
                        canDown = index < state.rows.size - 1,
                        onUp = { val tmp = state.rows[index]; state.rows[index] = state.rows[index - 1]; state.rows[index - 1] = tmp },
                        onDown = { val tmp = state.rows[index]; state.rows[index] = state.rows[index + 1]; state.rows[index + 1] = tmp },
                        onDelete = { state.rows.removeAt(index) },
                        onAdd = { state.rows.add(index + 1, KvRow("", "")) }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                KvValueEditor(kind, selectOptions, row.value, enabled) { row.value = it }
                if (desc.isNotBlank()) {
                    Text(desc, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                }
            }
        }
        TextButton(onClick = { if (enabled) state.rows.add(KvRow("", "")) }, enabled = enabled) {
            Text("➕ 添加参数", fontSize = 12.sp)
        }
    }
}

private fun kvKind(desc: String): Pair<String, List<String>> {
    val lowered = desc.lowercase()
    if ("(true/false)" in lowered) return "bool" to emptyList()
    if ("填列表" in desc || "数组" in desc) return "text" to emptyList()
    val match = Regex("\\(([a-zA-Z0-9_]+(?:/[a-zA-Z0-9_]+)+)\\)").find(desc)
    if (match != null) return "select" to match.groupValues[1].split("/")
    if ("数字" in desc) return "number" to emptyList()
    return "line" to emptyList()
}

@Composable
private fun KvValueEditor(
    kind: String,
    options: List<String>,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    when (kind) {
        "bool" -> Row {
            FilterChip(selected = value == "true", onClick = { if (enabled) onChange("true") }, label = { Text("true", fontSize = 11.sp) })
            Spacer(modifier = Modifier.width(6.dp))
            FilterChip(selected = value == "false", onClick = { if (enabled) onChange("false") }, label = { Text("false", fontSize = 11.sp) })
        }
        "select" -> Selector(options, value, enabled, onChange)
        "text" -> SimpleTextField(
            value = value,
            onValueChange = { if (enabled) onChange(it) },
            enabled = enabled,
            singleLine = false,
            minHeight = 70.dp,
            monospace = true,
            modifier = Modifier.fillMaxWidth()
        )
        else -> SimpleTextField(
            value = value,
            onValueChange = { if (enabled) onChange(it) },
            enabled = enabled,
            monospace = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SchemaCheckboxEditor(state: SchemaCheckboxState, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        state.options.forEach { (id, name) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = state.checked[id] == true,
                    onCheckedChange = { checked ->
                        if (!enabled) return@Checkbox
                        if (!checked && state.checked.count { it.value } <= 1) return@Checkbox
                        state.checked[id] = checked
                    }
                )
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    id,
                    fontSize = 10.sp,
                    color = MorandiGreen,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun AlgebraPatchEditor(state: AlgebraPatchState, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!enabled) {
            Text(
                "⚠️ 保护机制：核心规则仅允许在【补丁模式】下编辑。",
                fontSize = 11.sp,
                color = Color(0xFFC46A6A),
                fontWeight = FontWeight.Bold
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔤 拼写方案:", fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
                Selector(state.schemeOptions, state.scheme, enabled) {
                    state.scheme = it
                    if (it !in state.tiquanSchemes) state.tiquan = false
                }
            }
        }
        if (state.variant == "pro") {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⌨️ 辅助模式:", fontSize = 12.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f)) {
                    Selector(state.auxOptions, state.aux, enabled) { state.aux = it }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        FilterChip(
            selected = state.tiquan,
            enabled = enabled && state.scheme in state.tiquanSchemes,
            onClick = { state.tiquan = !state.tiquan },
            label = { Text("🚀 四码唯一字提权 (限自然/小鹤)", fontSize = 11.sp) }
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text("☁️ 模糊音", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
        state.fuzzyOptions.chunked(5).forEach { chunk ->
            Row {
                chunk.forEach { (label, path) ->
                    FilterChip(
                        selected = state.fuzzy[path] == true,
                        enabled = enabled,
                        onClick = { state.fuzzy[path] = !(state.fuzzy[path] ?: false) },
                        label = { Text(label, fontSize = 10.sp) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text("🧩 附加补丁（每行一条 wanxiang_algebra:/...）", fontSize = 11.sp, color = Color.Gray)
        state.extras.forEachIndexed { index, line ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                SimpleTextField(
                    value = line,
                    onValueChange = { if (enabled) state.extras[index] = it },
                    enabled = enabled,
                    monospace = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { if (enabled) state.extras.removeAt(index) }, enabled = enabled) {
                    Text("✕", fontSize = 12.sp, color = Color.Red)
                }
            }
        }
        TextButton(onClick = { if (enabled) state.extras.add("") }, enabled = enabled) {
            Text("➕ 添加补丁", fontSize = 12.sp)
        }
    }
}

@Composable
private fun AlgebraSchemeEditor(type: String, state: AlgebraSchemeState, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!enabled) {
            Text(
                "⚠️ 保护机制：核心规则仅允许在【补丁模式】下编辑。",
                fontSize = 11.sp,
                color = Color(0xFFC46A6A),
                fontWeight = FontWeight.Bold
            )
        }
        if (type == "reverse_algebra") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔤 拼音解析方案:", fontSize = 12.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f)) {
                    Selector(state.options, state.scheme, enabled) { state.scheme = it }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🖌️ 笔画挂接方案:", fontSize = 12.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f)) {
                    Selector(
                        state.strokeOptions.map { it.first },
                        state.strokeOptions.firstOrNull { it.second == state.strokeValue }?.first ?: state.strokeOptions.firstOrNull()?.first ?: "",
                        enabled
                    ) { label ->
                        state.strokeValue = state.strokeOptions.firstOrNull { it.first == label }?.second ?: state.strokeValue
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔤 按键映射:", fontSize = 12.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f)) {
                    Selector(state.options, state.scheme, enabled) { state.scheme = it }
                }
            }
        }
    }
}

@Composable
private fun KeySelector(keys: List<String>, current: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(current.ifBlank { "--- 选择参数 ---" }, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("▼", fontSize = 10.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            keys.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key, fontSize = 12.sp) },
                    onClick = { expanded = false; onSelect(key) }
                )
            }
        }
    }
}

@Composable
private fun RowActionButtons(
    enabled: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
    onAdd: () -> Unit
) {
    TextButton(onClick = onUp, enabled = enabled && canUp, contentPadding = PaddingValues(2.dp)) {
        Text("↑", fontSize = 13.sp)
    }
    TextButton(onClick = onDown, enabled = enabled && canDown, contentPadding = PaddingValues(2.dp)) {
        Text("↓", fontSize = 13.sp)
    }
    TextButton(onClick = onAdd, enabled = enabled, contentPadding = PaddingValues(2.dp)) {
        Text("＋", fontSize = 13.sp, color = MorandiDarkGreen)
    }
    TextButton(onClick = onDelete, enabled = enabled, contentPadding = PaddingValues(2.dp)) {
        Text("✕", fontSize = 13.sp, color = Color.Red)
    }
}

@Composable
private fun Selector(options: List<String>, current: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(current.ifBlank { "默认/不指定" }, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("▼", fontSize = 10.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 12.sp) },
                    onClick = { expanded = false; onSelect(option) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 构造状态与保存编辑
// ---------------------------------------------------------------------------

fun buildDynamicState(field: PageField): DynamicState? {
    val dynamic = field.dynamic ?: return null
    return when (dynamic.optString("kind")) {
        "list" -> DynamicListState(dynamic.optJSONArray("rows")?.optObjectList()?.map { it.optString("text") } ?: emptyList())
        "map" -> DynamicMapState(dynamic.optJSONArray("rows")?.optObjectList()?.map { it.optString("text") } ?: emptyList())
        "kv_list" -> {
            val rows = dynamic.optJSONArray("rows")?.optObjectList()?.map {
                KvRow(it.optString("key"), it.optString("value"))
            } ?: emptyList()
            DynamicKvState(rows)
        }
        "schema_checkboxes" -> {
            val options = dynamic.optJSONArray("options")?.optObjectList()?.map {
                it.optString("id") to it.optString("name")
            } ?: emptyList()
            val active = dynamic.optJSONArray("active")?.stringList() ?: emptyList()
            SchemaCheckboxState(options, active)
        }
        "block" -> {
            val templateObj = dynamic.optJSONObject("template") ?: JSONObject()
            val template = LinkedHashMap<String, BlockTemplateField>()
            templateObj.keys().forEach { key ->
                val node = templateObj.optJSONObject(key) ?: return@forEach
                val presetObj = node.optJSONObject("preset_keys") ?: JSONObject()
                val preset = LinkedHashMap<String, String>()
                presetObj.keys().forEach { preset[it] = presetObj.optString(it) }
                val visibleObj = node.optJSONObject("visible_if") ?: JSONObject()
                val visible = LinkedHashMap<String, List<String>>()
                visibleObj.keys().forEach { key2 -> visible[key2] = visibleObj.optJSONArray(key2)?.stringList() ?: emptyList() }
                template[key] = BlockTemplateField(
                    title = node.optString("title", key),
                    type = node.optString("type", "str"),
                    desc = node.optString("desc", ""),
                    options = node.optJSONArray("options")?.stringList() ?: emptyList(),
                    presetKeys = preset,
                    visibleIf = visible
                )
            }
            val state = DynamicBlockState(template)
            dynamic.optJSONArray("rows")?.optObjectList()?.forEach { rowObj ->
                val original = rowObj.optJSONObject("original") ?: JSONObject()
                val row = BlockRow(original)
                val fieldsObj = rowObj.optJSONObject("fields") ?: JSONObject()
                template.forEach { (key, templateField) ->
                    val node = fieldsObj.optJSONObject(key) ?: return@forEach
                    row.fields[key] = when (templateField.type) {
                        "bool" -> BlockBoolField(node.optInt("tri", -1))
                        "select" -> BlockSelectField(templateField.options, node.optString("text"))
                        "action_kv" -> {
                            val preset = LinkedHashMap<String, String>()
                            val presetObj = node.optJSONObject("preset_keys") ?: JSONObject()
                            presetObj.keys().forEach { preset[it] = presetObj.optString(it) }
                            BlockActionField(preset, node.optString("action_key"), node.optString("action_value"))
                        }
                        else -> BlockTextField(templateField.type, node.optString("text"))
                    }
                }
                state.rows.add(row)
            }
            state
        }
        "algebra_patch" -> AlgebraPatchState(
            variant = dynamic.optString("variant", "base"),
            schemeOptions = dynamic.optJSONArray("scheme_options")?.stringList() ?: emptyList(),
            auxOptions = dynamic.optJSONArray("aux_options")?.stringList() ?: emptyList(),
            tiquanSchemes = dynamic.optJSONArray("tiquan_schemes")?.stringList() ?: emptyList(),
            fuzzyOptions = dynamic.optJSONArray("fuzzy_options")?.optObjectList()?.map {
                it.optString("label") to it.optString("path")
            } ?: emptyList(),
            initialScheme = dynamic.optString("scheme", "全拼"),
            initialAux = dynamic.optString("aux", "直接辅助"),
            initialTiquan = dynamic.optBoolean("tiquan"),
            initialFuzzy = dynamic.optJSONArray("fuzzy")?.stringList() ?: emptyList(),
            initialExtras = dynamic.optJSONArray("extras")?.stringList() ?: emptyList()
        )
        "reverse_algebra" -> AlgebraSchemeState(
            options = dynamic.optJSONArray("scheme_options")?.stringList() ?: emptyList(),
            initial = dynamic.optString("scheme", "自然码"),
            stroke = dynamic.optString("stroke", "hspzn"),
            strokeOptions = dynamic.optJSONArray("stroke_options")?.optObjectList()?.map {
                it.optString("label") to it.optString("value")
            } ?: emptyList(),
            initialStroke = dynamic.optString("stroke", "hspzn")
        )
        "english_algebra", "mixed_algebra" -> AlgebraSchemeState(
            options = dynamic.optJSONArray("scheme_options")?.stringList() ?: emptyList(),
            initial = dynamic.optString("scheme", if (field.type == "english_algebra") "自然码" else "全拼")
        )
        else -> null
    }
}

fun buildDynamicEdit(field: PageField, state: DynamicState): JSONObject? {
    return when (state) {
        is DynamicListState -> JSONObject().apply {
            put("path", field.path)
            put("type", "dynamic_list")
            put("rows", JSONArray().apply { state.rows.forEach { put(JSONObject().put("text", it)) } })
        }
        is DynamicMapState -> JSONObject().apply {
            put("path", field.path)
            put("type", "dynamic_map")
            put("rows", JSONArray().apply { state.rows.forEach { put(JSONObject().put("text", it)) } })
        }
        is DynamicKvState -> {
            val preset = field.dynamic?.optJSONObject("preset_keys") ?: JSONObject()
            JSONObject().apply {
                put("path", field.path)
                put("type", "dynamic_kv_list")
                put("rows", JSONArray().apply {
                    state.rows.forEach { row ->
                        put(JSONObject().apply {
                            put("key", row.key)
                            put("value", row.value)
                            put("desc", preset.optString(row.key, ""))
                        })
                    }
                })
            }
        }
        is SchemaCheckboxState -> JSONObject().apply {
            put("path", field.path)
            put("type", "schema_checkboxes")
            put("schemas", JSONArray().apply {
                state.options.forEach { (id, _) -> if (state.checked[id] == true) put(id) }
            })
        }
        is DynamicBlockState -> JSONObject().apply {
            put("path", field.path)
            put("type", "dynamic_block_list")
            put("rows", JSONArray().apply {
                state.rows.forEach { row ->
                    val fieldsObj = JSONObject()
                    state.template.forEach { (key, templateField) ->
                        if (!blockFieldVisible(row, templateField)) return@forEach
                        val fieldState = row.fields[key] ?: return@forEach
                        fieldsObj.put(
                            key,
                            when (fieldState) {
                                is BlockBoolField -> JSONObject().put("tri", fieldState.tri)
                                is BlockSelectField -> JSONObject().put("text", fieldState.text)
                                is BlockActionField -> JSONObject().apply {
                                    put("action_key", fieldState.key)
                                    put("action_value", fieldState.value)
                                }
                                is BlockTextField -> JSONObject().put("text", fieldState.text)
                            }
                        )
                    }
                    put(JSONObject().apply {
                        put("original", row.original)
                        put("fields", fieldsObj)
                    })
                }
            })
        }
        is AlgebraPatchState -> JSONObject().apply {
            put("path", field.path)
            put("type", "algebra_patch")
            put("algebra", JSONObject().apply {
                put("scheme", state.scheme)
                put("aux", state.aux)
                put("tiquan", state.tiquan)
                put("fuzzy", JSONArray().apply {
                    state.fuzzyOptions.forEach { (_, path) -> if (state.fuzzy[path] == true) put(path) }
                })
                put("extras", JSONArray().apply { state.extras.forEach { put(it) } })
            })
        }
        is AlgebraSchemeState -> JSONObject().apply {
            put("path", field.path)
            put("type", field.type)
            put("algebra", JSONObject().apply {
                put("scheme", state.scheme)
                if (field.type == "reverse_algebra") put("stroke", state.strokeValue)
            })
        }
    }
}
