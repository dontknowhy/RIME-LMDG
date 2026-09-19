package com.wanxiangupdater

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 高级设置 Chaquopy 桥接封装。
 * Python 侧为 rime_config_bridge.py，所有函数返回 JSON 字符串。
 */
object AdvancedSettingsEngine {

    private val startLock = Any()

    private fun getPython(context: Context): Python {
        if (!Python.isStarted()) {
            synchronized(startLock) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
            }
        }
        return Python.getInstance()
    }

    private fun call(context: Context, function: String, vararg args: Any?): JSONObject {
        val result = getPython(context)
            .getModule("rime_config_bridge")
            .callAttr(function, *args)
            .toString()
        return JSONObject(result)
    }

    private fun ensureOk(json: JSONObject): JSONObject {
        if (!json.optBoolean("ok", false)) {
            val message = json.optString("error").ifBlank { "Python 调用失败" }
            error(message)
        }
        return json
    }

    suspend fun managedFiles(context: Context): Result<List<String>> = withContext(Dispatchers.Default) {
        runCatching {
            val json = ensureOk(call(context, "managed_files"))
            val array = json.optJSONArray("files") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) add(array.optString(i))
            }
        }
    }

    suspend fun getNav(context: Context, workspace: String): Result<JSONArray> =
        withContext(Dispatchers.Default) {
            runCatching {
                ensureOk(call(context, "get_nav", workspace)).optJSONArray("categories")
                    ?: JSONArray()
            }
        }

    suspend fun loadPage(
        context: Context,
        workspace: String,
        file: String,
        mode: String
    ): Result<JSONObject> = withContext(Dispatchers.Default) {
        runCatching { ensureOk(call(context, "load_page", workspace, file, mode)) }
    }

    suspend fun savePage(
        context: Context,
        workspace: String,
        file: String,
        mode: String,
        editsJson: String
    ): Result<JSONObject> = withContext(Dispatchers.Default) {
        runCatching {
            ensureOk(call(context, "save_page", workspace, file, mode, editsJson))
        }
    }

    suspend fun scan(context: Context, workspace: String): Result<JSONObject> =
        withContext(Dispatchers.Default) {
            runCatching { ensureOk(call(context, "scan", workspace)) }
        }

    suspend fun detectConflicts(
        context: Context,
        workspace: String,
        file: String
    ): Result<List<Pair<Int, String>>> = withContext(Dispatchers.Default) {
        runCatching {
            val array = ensureOk(call(context, "detect_conflicts", workspace, file))
                .optJSONArray("conflicts")
            buildList {
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add(item.optInt("severity") to item.optString("line"))
                    }
                }
            }
        }
    }

    suspend fun importSwitches(context: Context, workspace: String): Result<List<String>> =
        withContext(Dispatchers.Default) {
            runCatching {
                val array = ensureOk(call(context, "import_switches", workspace)).optJSONArray("names")
                buildList {
                    if (array != null) {
                        for (i in 0 until array.length()) add(array.optString(i))
                    }
                }
            }
        }

    suspend fun readRaw(context: Context, workspace: String, file: String): Result<String> =
        withContext(Dispatchers.Default) {
            runCatching {
                ensureOk(call(context, "read_raw", workspace, file)).optString("text")
            }
        }

    suspend fun writeRaw(
        context: Context,
        workspace: String,
        file: String,
        text: String
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            ensureOk(call(context, "write_raw", workspace, file, text))
            Unit
        }
    }
}
