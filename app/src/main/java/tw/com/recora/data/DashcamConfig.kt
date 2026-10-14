package tw.com.recora.data

object DashcamConfig {
    const val DEFAULT_HOST = "192.72.1.1"
    const val DEFAULT_PASSWORD = "12345678"
    const val DEFAULT_SSID_PREFIX = "MUFU_"
    const val SD_ROOT = "/mnt/mmc"
    const val RTSP_PORT = 554

    /** 每頁筆數；官方 App 用 16，一次要太多記錄器容易卡住。 */
    const val PAGE_SIZE = 16

    /** 本機下載資料夾（Movies/ 或 Pictures/ 底下）。 */
    const val LOCAL_DIR = "Recora"

    /** 改名前的本機資料夾（舊版的下載還在這裡，相簿與已下載判斷仍要讀）。 */
    val LEGACY_LOCAL_DIRS: List<String> = listOf("Dashcam", "MufuDashcam")

    /** Astro LEO3（Novatek，ASTRO_LEO3_API.md）。 */
    const val ASTRO_HOST = "192.168.1.254"
    const val ASTRO_SSID_PREFIX = "Astrofamily_"

    fun defaultHost(brand: Brand): String = if (brand == Brand.Astro) ASTRO_HOST else DEFAULT_HOST
}

/** dir 的資料夾名稱 ↔ SD 卡上的目錄（對應 dashcam_download.py 的 FOLDER_DIR）。 */
enum class Folder(val cgiName: String, val dirName: String, val label: String) {
    Normal("Normal", "Video", "一般"),
    Event("Event", "Emergency", "緊急"),
    Parking("Parking", "Parking", "停車"),
    Photo("Photo", "Picture", "照片"),
}
