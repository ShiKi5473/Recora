package tw.com.recora.ui

import kotlinx.serialization.Serializable

// 導覽的目的地（type-safe routes）：參數的編碼、解碼由 Navigation 處理，不必自己組 "player?url=…" 字串。

@Serializable
data object FilesRoute

@Serializable
data object LiveRoute

@Serializable
data object DownloadsRoute

@Serializable
data object LibraryRoute

@Serializable
data object DeviceRoute

@Serializable
data object SettingsRoute

/**
 * 播放畫面：[url] 是手機上的 content:// 或記錄器的 http 網址，[title] 是檔名。
 * [queue] = 來源頁面登記的播放清單編號（[tw.com.recora.ui.player.PlayQueues]），0 = 只播這一段。
 */
@Serializable
data class PlayerRoute(val url: String, val title: String = "", val queue: Long = 0L)

/** 剪輯畫面：手機上的影片（content://）與檔名。 */
@Serializable
data class EditorRoute(val uri: String, val title: String = "")
