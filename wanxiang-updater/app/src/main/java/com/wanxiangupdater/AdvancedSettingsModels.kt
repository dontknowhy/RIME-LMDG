package com.wanxiangupdater

import org.json.JSONArray
import org.json.JSONObject

data class NavFile(val file: String, val name: String, val exists: Boolean)

data class NavCategory(val title: String, val files: List<NavFile>)

data class PageField(
    val path: String,
    val type: String,
    val title: String,
    val desc: String,
    val text: String,
    val present: Boolean,
    val schemaPresent: Boolean,
    val value: Any?,
    val options: List<String>,
    val dynamic: JSONObject?,
    val actionBtn: String?,
    val comment: String?
)

data class PageSection(
    val key: String,
    val title: String,
    val desc: String,
    val fields: List<PageField>
)

data class PagePlan(
    val file: String,
    val custom: String,
    val hasCustom: Boolean,
    val sections: List<PageSection>
)

object AdvancedSettingsParser {

    fun parseNav(array: JSONArray): List<NavCategory> {
        val categories = mutableListOf<NavCategory>()
        for (i in 0 until array.length()) {
            val category = array.optJSONObject(i) ?: continue
            val filesArray = category.optJSONArray("files") ?: JSONArray()
            val files = mutableListOf<NavFile>()
            for (j in 0 until filesArray.length()) {
                val file = filesArray.optJSONObject(j) ?: continue
                files.add(
                    NavFile(
                        file = file.optString("file"),
                        name = file.optString("name"),
                        exists = file.optBoolean("exists")
                    )
                )
            }
            categories.add(NavCategory(category.optString("title"), files))
        }
        return categories
    }

    fun parsePage(json: JSONObject): PagePlan {
        val sectionsArray = json.optJSONArray("sections") ?: JSONArray()
        val sections = mutableListOf<PageSection>()
        for (i in 0 until sectionsArray.length()) {
            val section = sectionsArray.optJSONObject(i) ?: continue
            val fieldsArray = section.optJSONArray("fields") ?: JSONArray()
            val fields = mutableListOf<PageField>()
            for (j in 0 until fieldsArray.length()) {
                val field = fieldsArray.optJSONObject(j) ?: continue
                val optionsArray = field.optJSONArray("options")
                val options = buildList {
                    if (optionsArray != null) {
                        for (k in 0 until optionsArray.length()) add(optionsArray.opt(k).toString())
                    }
                }
                fields.add(
                    PageField(
                        path = field.optString("path"),
                        type = field.optString("type").ifBlank { "str" },
                        title = field.optString("title"),
                        desc = field.optString("desc"),
                        text = field.optString("text"),
                        present = field.optBoolean("present"),
                        schemaPresent = field.optBoolean("schema_present"),
                        value = if (field.has("value") && !field.isNull("value")) field.opt("value") else null,
                        options = options,
                        dynamic = if (field.has("dynamic") && !field.isNull("dynamic")) field.optJSONObject("dynamic") else null,
                        actionBtn = field.optString("action_btn").ifBlank { null },
                        comment = field.optString("comment").ifBlank { null }
                    )
                )
            }
            sections.add(
                PageSection(
                    key = section.optString("key"),
                    title = section.optString("title"),
                    desc = section.optString("desc"),
                    fields = fields
                )
            )
        }
        return PagePlan(
            file = json.optString("file"),
            custom = json.optString("custom"),
            hasCustom = json.optBoolean("has_custom"),
            sections = sections
        )
    }
}
