package com.wanxiangupdater

import android.content.Context
import java.io.File

/**
 * 高级设置的本地工作区。
 *
 * Python 的 RimeYamlEngine 使用 pathlib + os.replace 操作本地文件，
 * 无法直接访问 SAF URI 或 root 路径。因此先把目标目录中的受管 YAML
 * 拉到 app 私有目录，交给 Python 编辑，再把变更文件写回目标。
 */
object WorkspaceSync {

    private const val WORKSPACE_DIR = "rime_advanced_workspace"

    fun workspaceDir(context: Context): File {
        val dir = File(context.filesDir, WORKSPACE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 清空工作区并按受管文件清单从目标拉取。 */
    fun syncFromTarget(
        context: Context,
        access: RimeFileAccess,
        managedFiles: List<String>
    ): SyncResult {
        val dir = workspaceDir(context)
        dir.listFiles()?.forEach { it.delete() }

        val pulled = mutableListOf<String>()
        val errors = mutableListOf<String>()
        for (name in managedFiles) {
            val bytes = try {
                if (access.exists(name)) access.read(name) else null
            } catch (error: Exception) {
                errors.add("读取失败 $name: ${error.message ?: error.javaClass.simpleName}")
                null
            }
            if (bytes == null) continue
            try {
                File(dir, name).writeBytes(bytes)
                pulled.add(name)
            } catch (error: Exception) {
                errors.add("写入工作区失败 $name: ${error.message ?: error.javaClass.simpleName}")
            }
        }
        return SyncResult(pulled, errors)
    }

    /** 把工作区中指定的变更文件写回目标。 */
    fun pushChanges(
        context: Context,
        access: RimeFileAccess,
        changedFiles: List<String>
    ): List<String> {
        val dir = workspaceDir(context)
        val errors = mutableListOf<String>()
        for (name in changedFiles) {
            val source = File(dir, name)
            if (!source.isFile) {
                errors.add("工作区缺少文件：$name")
                continue
            }
            val success = try {
                access.write(name, source.readBytes())
            } catch (error: Exception) {
                false
            }
            if (!success) errors.add("写回目标失败：$name")
        }
        return errors
    }

    data class SyncResult(val pulled: List<String>, val errors: List<String>)
}
