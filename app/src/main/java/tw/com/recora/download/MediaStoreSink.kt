package tw.com.recora.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import tw.com.recora.data.DashcamConfig
import tw.com.recora.data.FileNames
import tw.com.recora.data.LocalLibrary
import tw.com.recora.data.LocalMedia
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * 把下載寫進相簿（MediaStore），影片放 Movies/<存檔資料夾>、照片放 Pictures/<存檔資料夾>（預設 Recora，「App 設定」可改；
 * 可再分子資料夾）。改過存檔資料夾或舊版的 Dashcam、MufuDashcam 裡的檔案照樣讀得到。
 * 下載中的檔案標為 IS_PENDING，未完成的 pending 檔案就是續傳的依據，不需要另外的資料庫。
 * 找檔案時包含子資料夾，換過「下載資料夾分類」設定也找得到以前下載的。
 */
class MediaStoreSink(
    context: Context,
    /** 相簿除了 App 自己的資料夾還要讀的資料夾（「App 設定」裡勾選的）。 */
    private val libraryFolders: () -> Collection<String> = { LocalLibrary.DEFAULT_FOLDERS },
    /** 下載、匯出、截圖存到的資料夾名稱（「App 設定」的存檔資料夾）。 */
    private val albumDir: () -> String = { DashcamConfig.LOCAL_DIR },
) {
    private val resolver = context.contentResolver

    data class Entry(val uri: Uri, val name: String, val size: Long, val pending: Boolean)

    /** 手機上有影片或照片的資料夾（RELATIVE_PATH）與檔案數，給「相簿資料夾」挑選。 */
    data class MediaFolder(val path: String, val count: Int)

    /** [subdir] = App 資料夾底下的子資料夾（null = 直接放在裡面）。 */
    fun relativePath(isImage: Boolean, subdir: String? = null): String =
        (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/${albumDir()}/" +
            subdir?.let { "$it/" }.orEmpty()

    /** 相簿要讀的資料夾：App 的資料夾，加上使用者勾選的（例如 Astro App 的 Movies/astroFamily）。 */
    private fun libraryDirs(isImage: Boolean): List<String> = LocalLibrary.appDirs(isImage, albumDir()) + libraryFolders()

    private fun collection(isImage: Boolean): Uri =
        if (isImage) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

    /** 找同名檔案（含下載中的 pending 檔案）；pending 的大小以實際檔案長度為準。包含相簿讀的所有資料夾（相容舊版與其他資料夾）。 */
    fun find(name: String, isImage: Boolean): Entry? =
        query(isImage, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(name), MediaStore.MATCH_INCLUDE, libraryDirs(isImage))
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.let { if (it.pending) it.copy(size = currentSize(it.uri)) else it }

    /**
     * 手機上可以直接播的同名影片（已下載完成），沒有回 null。找相簿讀的所有資料夾：
     * 播放器切換前後鏡頭、剪輯時一起匯出另一個鏡頭，Astro 的另一個鏡頭在它自己的資料夾。
     * LIKE 的 `_` 是萬用字元（Astro 的檔名有底線），所以結果再比一次檔名。
     */
    fun findVideo(name: String): Uri? =
        query(false, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(name), MediaStore.MATCH_EXCLUDE, libraryDirs(false))
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.uri

    /** 提供給 [DownloadRepository] 使用的字串 URI 儲存介面。 */
    fun asDownloadSink(): DownloadSink = object : DownloadSink {
        override fun find(name: String, isImage: Boolean): DownloadSink.Entry? =
            this@MediaStoreSink.find(name, isImage)?.let {
                DownloadSink.Entry(it.uri.toString(), it.name, it.size, it.pending)
            }

        override fun create(name: String, isImage: Boolean, subdir: String?): String =
            this@MediaStoreSink.create(name, isImage, subdir).toString()

        override fun openOutput(uri: String, offset: Long): OutputStream =
            this@MediaStoreSink.openOutput(Uri.parse(uri), offset)

        override fun finish(uri: String) = this@MediaStoreSink.finish(Uri.parse(uri))

        override fun delete(uri: String) = this@MediaStoreSink.delete(Uri.parse(uri))
    }

    /** 已完成下載的檔案：大寫檔名 → 檔案。包含相簿讀的所有資料夾（相容舊版與其他資料夾）。 */
    fun downloadedEntries(): Map<String, Entry> =
        (query(false, null, null, MediaStore.MATCH_EXCLUDE, libraryDirs(false)) +
            query(true, null, null, MediaStore.MATCH_EXCLUDE, libraryDirs(true)))
            .associateBy { it.name.uppercase() }

    /** 已完成下載的檔案：大寫檔名 → 大小。 */
    fun downloaded(): Map<String, Long> = downloadedEntries().mapValues { it.value.size }

    /** 在相簿建立一個 pending 檔（寫完再 [finish]）；[mime] 預設依副檔名。 */
    fun create(name: String, isImage: Boolean, subdir: String? = null, mime: String = FileNames.mimeType(name)): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(isImage, subdir))
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

    /** [deleteOrRequest] 的結果：[deleted] = 直接刪掉的數量，[confirm] = 剩下要系統確認的（沒有則為 null）。 */
    data class DeleteResult(val deleted: Int, val confirm: IntentSender?)

    /**
     * 刪除相簿裡的檔案。本 App 建立的直接刪；不是本 App 建立的（例如重新安裝前下載的）會被拒絕（SecurityException），
     * 集中成一個系統確認視窗，不必一個一個按。
     */
    fun deleteOrRequest(uris: List<Uri>): DeleteResult {
        val needConfirm = ArrayList<Uri>()
        var deleted = 0
        for (uri in uris) {
            try {
                delete(uri)
                deleted++
            } catch (e: SecurityException) {
                needConfirm += uri
            }
        }
        val confirm = if (needConfirm.isEmpty()) null else MediaStore.createDeleteRequest(resolver, needConfirm).intentSender
        return DeleteResult(deleted, confirm)
    }

    /** 把本機檔（例如剪輯輸出的 MP4）複製進相簿，回傳新的 Uri。 */
    fun saveFile(source: File, displayName: String, mime: String, isImage: Boolean): Uri =
        writeNew(displayName, mime, isImage) { out -> source.inputStream().use { it.copyTo(out, 256 * 1024) } }

    /** 把截圖存成 JPEG 放進 Pictures/<存檔資料夾>。 */
    fun saveJpeg(bitmap: Bitmap, displayName: String): Uri =
        writeNew(displayName, "image/jpeg", isImage = true) { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("無法存成 JPEG")
        }

    /** 沒下載完的檔案（App 資料夾裡的 pending 檔），大小以實際檔案長度為準。 */
    fun listPending(): List<Entry> =
        (query(false, null, null, MediaStore.MATCH_ONLY) + query(true, null, null, MediaStore.MATCH_ONLY))
            .map { it.copy(size = runCatching { currentSize(it.uri) }.getOrDefault(it.size)) }

    /**
     * 相簿分頁：App 自己的資料夾（存檔資料夾、預設的 Recora 與改版前的 Dashcam、MufuDashcam，含子資料夾），以及使用者勾選的資料夾，排除下載中的 pending 檔案。
     * 沒有媒體讀取權限時只看得到本 App 建立的檔案。
     */
    fun listLibrary(): List<LocalMedia> {
        val out = ArrayList<LocalMedia>()
        val folders = libraryFolders()
        val dir = albumDir()
        for (isImage in listOf(false, true)) {
            val base = collection(isImage)
            val (selection, selectionArgs) = inDirs(LocalLibrary.appDirs(isImage, dir) + folders)
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
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
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            resolver.query(base, projection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    LocalLibrary.build(
                        uri = Uri.withAppendedPath(base, c.getLong(0).toString()).toString(),
                        name = c.getString(1).orEmpty(),
                        relativePath = c.getString(2).orEmpty(),
                        sizeBytes = c.getLong(3),
                        durationMs = c.getLong(4),
                        // 實測官方 App 存的檔案有些沒有 DATE_ADDED（會變成 1970 年），改用修改時間
                        dateAddedSec = c.getLong(5).takeIf { it > 0 } ?: c.getLong(7),
                        dateTakenMs = if (c.isNull(6)) null else c.getLong(6),
                        isImage = isImage,
                        folders = folders,
                        albumDir = dir,
                    )?.let(out::add)
                }
            }
        }
        return out
    }

    /** 手機上有影片或照片的資料夾與檔案數（沒有媒體讀取權限時只看得到本 App 建立的）。 */
    fun listMediaFolders(): List<MediaFolder> {
        val counts = HashMap<String, Int>()
        for (isImage in listOf(false, true)) {
            resolver.query(collection(isImage), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    counts[path] = (counts[path] ?: 0) + 1
                }
            }
        }
        return counts.map { (path, count) -> MediaFolder(path, count) }
    }

    /** 建立新檔、寫入、標成完成；寫入失敗時刪掉建了一半的檔案，不會留在相簿的「未完成的下載暫存檔」裡。 */
    private fun writeNew(displayName: String, mime: String, isImage: Boolean, write: (OutputStream) -> Unit): Uri {
        val uri = create(displayName, isImage, mime = mime)
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IOException("無法寫入相簿")
            finish(uri)
        } catch (e: Exception) {
            runCatching { delete(uri) }
            throw e
        }
        return uri
    }

    /** RELATIVE_PATH 在 [dirs] 任一個底下（含子資料夾）的條件與參數。 */
    private fun inDirs(dirs: List<String>): Pair<String, Array<String>> =
        dirs.joinToString(" OR ", "(", ")") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" } to
            dirs.map { "$it%" }.toTypedArray()

    /** [dirs]（預設是 App 資料夾，含子資料夾）裡的檔案；[matchPending] = MediaStore.MATCH_INCLUDE／EXCLUDE／ONLY。 */
    private fun query(
        isImage: Boolean,
        extraSelection: String?,
        extraArgs: Array<String>?,
        matchPending: Int,
        dirs: List<String> = LocalLibrary.appDirs(isImage, albumDir()),
    ): List<Entry> {
        val (dirSelection, dirArgs) = inDirs(dirs)
        val selection = if (extraSelection != null) "$dirSelection AND $extraSelection" else dirSelection
        val args = dirArgs + (extraArgs ?: emptyArray())
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, matchPending)
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
