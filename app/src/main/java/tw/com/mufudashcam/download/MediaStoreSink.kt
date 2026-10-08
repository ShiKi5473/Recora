package tw.com.mufudashcam.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import tw.com.mufudashcam.data.DashcamConfig
import tw.com.mufudashcam.data.FileNames
import tw.com.mufudashcam.data.LocalLibrary
import tw.com.mufudashcam.data.LocalMedia
import java.io.IOException
import java.io.OutputStream

/**
 * 把下載寫進相簿（MediaStore），影片放 Movies/MufuDashcam、照片放 Pictures/MufuDashcam。
 * 下載中的檔案標為 IS_PENDING，未完成的 pending 檔案就是續傳的依據，不需要另外的資料庫。
 */
class MediaStoreSink(context: Context) {
    private val resolver = context.contentResolver

    data class Entry(val uri: Uri, val name: String, val size: Long, val pending: Boolean)

    fun relativePath(isImage: Boolean): String =
        (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/${DashcamConfig.LOCAL_DIR}/"

    private fun collection(isImage: Boolean): Uri =
        if (isImage) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

    /** 找同名檔案（含下載中的 pending 檔案）；pending 的大小以實際檔案長度為準。 */
    fun find(name: String, isImage: Boolean): Entry? =
        query(isImage, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(name), includePending = true)
            .firstOrNull()
            ?.let { if (it.pending) it.copy(size = currentSize(it.uri)) else it }

    /** 已完成下載的檔案：大寫檔名 → 大小。 */
    fun downloaded(): Map<String, Long> =
        (query(false, null, null, includePending = false) + query(true, null, null, includePending = false))
            .associate { it.name.uppercase() to it.size }

    fun create(name: String, isImage: Boolean): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, FileNames.mimeType(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(isImage))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection(isImage), values) ?: throw IOException("無法在相簿建立檔案")
    }

    fun currentSize(uri: Uri): Long =
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L

    /** 開啟輸出串流並定位到 [offset]，offset 之後的舊資料會被截斷。 */
    fun openOutput(uri: Uri, offset: Long): OutputStream {
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("無法開啟檔案")
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        out.channel.truncate(offset)
        out.channel.position(offset)
        return out
    }

    fun finish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    fun delete(uri: Uri) {
        resolver.delete(uri, null, null)
    }

    /** 把本機檔（例如剪輯輸出的 MP4）複製進相簿，回傳新的 Uri。 */
    fun saveFile(source: java.io.File, displayName: String, mime: String, isImage: Boolean): Uri {
        val uri = createNamed(displayName, mime, isImage)
        resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out, 256 * 1024) } }
            ?: throw IOException("無法寫入相簿")
        finish(uri)
        return uri
    }

    /** 把截圖存成 JPEG 放進 Pictures/MufuDashcam。 */
    fun saveJpeg(bitmap: android.graphics.Bitmap, displayName: String): Uri {
        val uri = createNamed(displayName, "image/jpeg", isImage = true)
        resolver.openOutputStream(uri)?.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
            ?: throw IOException("無法寫入相簿")
        finish(uri)
        return uri
    }

    /**
     * 相簿分頁：只列 Movies/MufuDashcam 與 Pictures/MufuDashcam（含子資料夾），排除下載中的 pending 檔案。
     * 沒有媒體讀取權限時只看得到本 App 建立的檔案。
     */
    fun listLibrary(): List<LocalMedia> {
        val out = ArrayList<LocalMedia>()
        for (isImage in listOf(false, true)) {
            val base = collection(isImage)
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath(isImage) + "%"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
            }
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_TAKEN,
            )
            resolver.query(base, projection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    LocalLibrary.build(
                        uri = Uri.withAppendedPath(base, c.getLong(0).toString()).toString(),
                        name = c.getString(1).orEmpty(),
                        relativePath = c.getString(2).orEmpty(),
                        sizeBytes = c.getLong(3),
                        durationMs = c.getLong(4),
                        dateAddedSec = c.getLong(5),
                        dateTakenMs = if (c.isNull(6)) null else c.getLong(6),
                    )?.let(out::add)
                }
            }
        }
        return out
    }

    private fun createNamed(displayName: String, mime: String, isImage: Boolean): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(isImage))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection(isImage), values) ?: throw IOException("無法在相簿建立檔案")
    }

    private fun query(
        isImage: Boolean,
        extraSelection: String?,
        extraArgs: Array<String>?,
        includePending: Boolean,
    ): List<Entry> {
        val selection = buildString {
            append("${MediaStore.MediaColumns.RELATIVE_PATH}=?")
            if (extraSelection != null) append(" AND $extraSelection")
        }
        val args = arrayOf(relativePath(isImage)) + (extraArgs ?: emptyArray())
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putInt(
                MediaStore.QUERY_ARG_MATCH_PENDING,
                if (includePending) MediaStore.MATCH_INCLUDE else MediaStore.MATCH_EXCLUDE,
            )
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val base = collection(isImage)
        return resolver.query(base, projection, queryArgs, null)?.use { c ->
            val out = ArrayList<Entry>(c.count)
            while (c.moveToNext()) {
                out += Entry(
                    uri = Uri.withAppendedPath(base, c.getLong(0).toString()),
                    name = c.getString(1).orEmpty(),
                    size = c.getLong(2),
                    pending = c.getInt(3) != 0,
                )
            }
            out
        }.orEmpty()
    }
}
