package com.wanxiangupdater

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 高级设置的目标目录读写抽象。
 *
 * 更新器支持三种目标：DEFAULT（/rime）、ROOT:（绝对路径）、SAF tree URI。
 * 高级设置只读写受管的 YAML 文件，统一通过本接口以“文件名”为键访问。
 */
interface RimeFileAccess {
    val displayName: String
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray): Boolean
    fun exists(name: String): Boolean
}

/** 手机根目录 /rime（需要 MANAGE_EXTERNAL_STORAGE）。 */
class DefaultRimeAccess(private val context: Context) : RimeFileAccess {
    private val base: File
        get() = File(Environment.getExternalStorageDirectory(), "rime")

    override val displayName: String = "/rime"

    override fun read(name: String): ByteArray? {
        val file = File(base, name)
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    override fun write(name: String, bytes: ByteArray): Boolean {
        val root = base
        if (!root.exists() && !root.mkdirs()) return false
        return runCatching {
            val target = File(root, name)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    override fun exists(name: String): Boolean = File(base, name).isFile
}

/** root 绝对路径目标，通过 su 读写。 */
class RootRimeAccess(private val rootPath: String) : RimeFileAccess {
    override val displayName: String
        get() = rootPath.substringAfterLast("/").ifBlank { rootPath }

    private fun absolute(name: String) = "$rootPath/$name"

    override fun read(name: String): ByteArray? = RootShell.readBytes(absolute(name))

    override fun write(name: String, bytes: ByteArray): Boolean =
        RootShell.writeBytes(absolute(name), bytes)

    override fun exists(name: String): Boolean = RootShell.exists(absolute(name))
}

/** SAF 授权目录。受管文件都位于授权目录根部。 */
class SafRimeAccess(private val context: Context, private val treeUri: Uri) : RimeFileAccess {
    override val displayName: String = "SAF授权目录"

    private fun rootDoc(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    override fun read(name: String): ByteArray? {
        val doc = rootDoc()?.findFile(name) ?: return null
        if (!doc.isFile) return null
        return runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    override fun write(name: String, bytes: ByteArray): Boolean {
        val root = rootDoc() ?: return false
        return runCatching {
            val existing = root.findFile(name)
            val doc = when {
                existing != null && existing.isFile -> existing
                else -> root.createFile("application/x-yaml", name)
            } ?: return false
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: return false
            true
        }.onFailure {
            Log.e("AdvancedSettings", "SAF 写入失败: $name", it)
        }.getOrDefault(false)
    }

    override fun exists(name: String): Boolean = rootDoc()?.findFile(name)?.isFile == true
}

/** 根据 deploy_paths 中保存的目标字符串创建访问器。 */
fun createRimeAccess(context: Context, rawTarget: String): RimeFileAccess? {
    return when {
        rawTarget == "DEFAULT" -> DefaultRimeAccess(context)
        isRootTarget(rawTarget) -> RootRimeAccess(rootTargetPath(rawTarget))
        rawTarget.startsWith("content://") -> runCatching {
            SafRimeAccess(context, Uri.parse(rawTarget))
        }.getOrNull()
        else -> null
    }
}
