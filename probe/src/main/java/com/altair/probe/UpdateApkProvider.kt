package com.altair.probe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** 只共享一个已校验安装包；不暴露目录、任意路径或写权限。 */
class UpdateApkProvider : ContentProvider() {
    private fun checked(uri: Uri): File {
        val ctx = checkNotNull(context)
        if (uri != Uri.parse("content://${ctx.packageName}.updates/pending.apk"))
            throw FileNotFoundException("无效更新文件")
        return File(ctx.filesDir, "pending-update.apk")
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String { checked(uri); return "application/vnd.android.package-archive" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("更新文件只读")
        return ParcelFileDescriptor.open(checked(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = checked(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> "probe-release.apk"
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }.toTypedArray())
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
