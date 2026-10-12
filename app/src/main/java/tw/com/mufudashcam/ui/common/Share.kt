package tw.com.mufudashcam.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 用系統分享選單分享相簿裡的檔案（content://），[mimeType] 用萬用字元的類型（影片、照片或兩者混合）。 */
fun shareMedia(context: Context, uris: List<Uri>, mimeType: String) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.setType(mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, if (uris.size == 1) "分享" else "分享 ${uris.size} 個檔案"))
}
