package tw.com.recora.data.api

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tw.com.recora.data.Brand
import tw.com.recora.data.DashcamFile
import tw.com.recora.data.DiagLog
import tw.com.recora.data.FileNames
import tw.com.recora.data.Folder
import tw.com.recora.data.SettingDef
import tw.com.recora.data.SettingsStore
import tw.com.recora.data.net.await
import tw.com.recora.ui.Formats
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Astro LEO3（Novatek NT96560）的 HTTP 介面（ASTRO_LEO3_API.md）。
 * 指令都是 `GET /?custom=1&cmd=N[&par=][&str=]`，回應 `<Function><Cmd/><Status/>…</Function>`，Status 0 = 成功。
 *
 * 與 MUFU 最大的不同：手機連著時記錄器會繼續錄影，用檔案前要停錄並切到回放模式（[AstroSession]）。
 */
class AstroApi(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val formatTimeoutMs: Long = FORMAT_TIMEOUT_MS,
    private val hostProvider: () -> String,
) : DashcamApi {
    constructor(client: OkHttpClient, settings: SettingsStore, scope: CoroutineScope) :
        this(client, scope, hostProvider = { settings.state.value.hostFor(Brand.Astro) })

    override val brand: Brand get() = Brand.Astro

    override val host: String get() = hostProvider()
    private val hostName: String get() = hostNameOf(host)
    private val port: Int get() = portOf(host)

    /** 記錄器拒絕了指令（Status 不是 0）。 */
    class RejectedException(val cmd: Int, val status: Int?) : IOException("記錄器拒絕指令 $cmd（Status $status）")

    val session = AstroSession(scope, object : AstroSession.Commands {
        override suspend fun isRecording(): Boolean = isRecordingNow()
        override suspend fun stopRecording() = require(command(CMD_RECORD, par = "0"))
        override suspend fun startRecording() {
            startRecordingWithRetry()
            onRecordingResumed()
        }
        override suspend fun enterPlayback() = require(command(CMD_MODE, par = MODE_PLAYBACK))
        override suspend fun enterMovie() = require(command(CMD_MODE, par = MODE_MOVIE))
    })

    /** 重新開始錄影之後呼叫：記錄器上會多出新檔案、循環錄影也會刪掉最舊的，已讀過的列表要作廢。 */
    @Volatile
    var onRecordingResumed: () -> Unit = {}

    override val listingStopsRecording: Boolean get() = true

    override val disconnectTimeoutMs: Long get() = 8_000L

    override val disconnectFailureWarning: String
        get() = "中斷連線時連不上記錄器，它可能沒有恢復錄影。請再連線一次（連上時會自動恢復），或重新開機記錄器。"

    @Volatile
    private var keepAlive: Job? = null

    /** 這次連線讀過的 2019／3031／3030（連線期間不會變）。 */
    @Volatile
    private var liveLink: String? = null

    @Volatile
    private var menu: List<NovatekParsers.MenuItem>? = null

    @Volatile
    private var sizes: List<Pair<String, String>>? = null

    /**
     * 查詢參數原樣送出（`str=A:\DCIM\…`、`str=12:38:59`），同官方 App；OkHttp 解析字串網址時查詢裡的 `\`、`:` 會保留。
     */
    fun commandUrl(cmd: Int, par: String? = null, str: String? = null): String =
        "http://$host/?custom=1&cmd=$cmd" + (par?.let { "&par=$it" } ?: "") + (str?.let { "&str=$it" } ?: "")

    /** 送出指令、回傳原始回應。[quiet] = 不記進「裝置」頁的紀錄（每 5 秒一次的保持連線、很長的列表）。 */
    suspend fun raw(cmd: Int, par: String? = null, str: String? = null, quiet: Boolean = false, readTimeoutMs: Long? = null): String =
        withContext(Dispatchers.IO) {
            val url = commandUrl(cmd, par, str)
            val query = url.substringAfter('?')
            val callClient = readTimeoutMs?.let { client.newBuilder().readTimeout(it, TimeUnit.MILLISECONDS).build() } ?: client
            try {
                callClient.newCall(Request.Builder().url(url).build()).await().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!quiet || !resp.isSuccessful) DiagLog.log("→ $query ⇒ HTTP ${resp.code}: ${DiagLog.compact(body)}")
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    body
                }
            } catch (e: IOException) {
                DiagLog.log("→ $query ⇒ ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }

    suspend fun command(cmd: Int, par: String? = null, str: String? = null, quiet: Boolean = false): NovatekParsers.Reply =
        NovatekParsers.parseReply(raw(cmd, par, str, quiet))

    private fun require(reply: NovatekParsers.Reply) {
        if (!reply.ok) throw RejectedException(reply.cmd ?: -1, reply.status)
    }

    /** 3014 的 2001（1 = 錄影中）；讀不到再看 2016 的已錄秒數。 */
    private suspend fun isRecordingNow(): Boolean {
        val status = NovatekParsers.parseStatusPairs(raw(CMD_STATUS, quiet = true))
        status[CMD_RECORD.toString()]?.let { return it == "1" }
        return (command(CMD_RECORD_TIME).value?.toLongOrNull() ?: 0) > 0
    }

    /**
     * 開始錄影。官方 App 紀錄：即時串流被 2015&par=0 停掉後，2001&par=1 會一直回 -13，
     * 送 2015&par=1 之後才成功；所以遇到拒絕就先開即時串流再試。
     */
    private suspend fun startRecordingWithRetry() {
        var status: Int? = null
        repeat(START_ATTEMPTS) { attempt ->
            val reply = command(CMD_RECORD, par = "1")
            if (reply.ok) return
            status = reply.status
            DiagLog.log("開始錄影被拒絕（Status ${reply.status}，第 ${attempt + 1} 次）")
            runCatching { command(CMD_LIVE, par = "1") }
            delay(START_RETRY_MS)
        }
        throw RejectedException(CMD_RECORD, status)
    }

    /** 3012 回應要是 Novatek 的 XML 才算：192.168.1.254 也常是家用路由器的位址。 */
    override suspend fun probe(network: Network?): DashcamApi.ProbeResult = withContext(Dispatchers.IO) {
        val probeClient = probeClient(client, network)
        val started = System.nanoTime()
        try {
            probeClient.newCall(Request.Builder().url(commandUrl(CMD_VERSION)).build()).await().use { resp ->
                val body = resp.body?.string().orEmpty()
                val ms = (System.nanoTime() - started) / 1_000_000
                val reply = NovatekParsers.parseReply(body)
                if (resp.isSuccessful && reply.cmd == CMD_VERSION) {
                    DashcamApi.ProbeResult(true, "HTTP ${resp.code}，$ms ms，韌體 ${reply.string ?: "?"}")
                } else {
                    DashcamApi.ProbeResult(false, "HTTP ${resp.code}，不是 Astro 記錄器的回應：${DiagLog.compact(body, 80)}")
                }
            }
        } catch (e: IOException) {
            DashcamApi.ProbeResult(false, "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: SecurityException) {
            DashcamApi.ProbeResult(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 連上後：記下裝置資訊（車上實測後對照用）、開始每 5 秒送 8107 保持連線（同官方 App）、
     * 確認記錄器有在錄影（[AstroSession.onConnected]）。
     */
    override suspend fun onConnected() {
        liveLink = null
        menu = null
        sizes = null
        startKeepAlive()
        // 先確認錄影狀態：「檔案」頁可能已經停錄，晚一步就多一段競態的時間窗
        try {
            session.onConnected()
        } catch (e: IOException) {
            DiagLog.log("確認錄影狀態失敗：${e.message}")
        }
        for (cmd in listOf(CMD_DEVICE_INFO, CMD_STATUS)) {
            try {
                DiagLog.detail("cmd=$cmd 全文：${raw(cmd, quiet = true)}")
            } catch (e: IOException) {
                // 只是紀錄
            }
        }
    }

    /** 中斷連線前一定恢復錄影模式與錄影（記錄器不能因為手機連過就停著不錄）。 */
    override suspend fun onDisconnecting() {
        stopKeepAlive()
        session.restoreNow(force = true)
    }

    override fun onDisconnected() {
        stopKeepAlive()
        session.reset()
    }

    /** 退到背景後 App 可能被滑掉關閉：沒有人在用檔案就不等 10 秒，馬上恢復錄影模式與錄影（只恢復這次連線我們改過的）。 */
    override suspend fun onAppBackground() = session.restoreIfIdle()

    private fun startKeepAlive() {
        val job = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_MS)
                try {
                    command(CMD_KEEP_ALIVE, quiet = true)
                } catch (e: IOException) {
                    // 斷線由 DashcamConnection 處理
                }
            }
        }
        synchronized(this) {
            keepAlive?.cancel()
            keepAlive = job
        }
    }

    private fun stopKeepAlive() {
        synchronized(this) {
            keepAlive?.cancel()
            keepAlive = null
        }
    }

    override suspend fun syncTime(now: ZonedDateTime): DashcamApi.SetResult {
        val date = command(CMD_SET_DATE, str = NovatekParsers.utcDate(now))
        val time = command(CMD_SET_TIME, str = NovatekParsers.utcTime(now))
        return DashcamApi.SetResult(date.ok && time.ok, "日期 Status ${date.status}，時間 Status ${time.status}")
    }

    /** 3015 一次回傳全部檔案，依資料夾分類。 */
    override suspend fun listAll(): Map<Folder, List<DashcamFile>> = withFiles {
        val entries = NovatekParsers.parseFileList(raw(CMD_FILE_LIST, quiet = true))
        val files = entries.map { e ->
            val path = NovatekParsers.sdPathOf(e.fpath)
            DashcamFile(e.name, path, e.size, e.time, NovatekParsers.folderOf(path), brand = Brand.Astro, readOnly = ((e.attr ?: 0) and 1) != 0)
        }
        val byFolder = Folder.entries.associateWith { f -> files.filter { it.folder == f } }
        DiagLog.log("列表（3015）：共 ${files.size} 個檔案（" + byFolder.entries.joinToString("、") { "${it.key.label} ${it.value.size}" } + "）")
        val dirs = entries.map { it.fpath.substringBeforeLast('\\') }.distinct()
        DiagLog.detail("3015 資料夾：$dirs；ATTR：${entries.groupingBy { it.attr }.eachCount()}")
        byFolder
    }

    override fun listFolder(folder: Folder): Flow<List<DashcamFile>> = flow { emit(listAll()[folder].orEmpty()) }

    override fun httpUrl(sdPath: String): String =
        HttpUrl.Builder().scheme("http").host(hostName).port(port)
            .addPathSegments(sdPath.trimStart('/'))
            .build()
            .toString()

    /** 只要 1 byte 的 GET：確認檔案在，也記下有沒有支援 Range（206）。 */
    override suspend fun resolveUrl(sdPath: String): String? = withFiles {
        val url = httpUrl(sdPath)
        val code = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).await().use { it.code }
        }
        DiagLog.log("下載網址 $sdPath ⇒ HTTP $code${if (code == 200) "（沒有回 206，可能不支援續傳）" else ""}")
        if (code == 200 || code == 206) url else null
    }

    override suspend fun delete(sdPath: String): DashcamApi.SetResult = withFiles {
        val reply = command(CMD_DELETE, str = NovatekParsers.fpathOf(sdPath))
        DashcamApi.SetResult(reply.ok, "Status ${reply.status}")
    }

    override fun holdFiles(reason: String): Lease = session.hold(reason)

    override suspend fun awaitFiles(reason: String): Lease = session.awaitHold(reason)

    override suspend fun <T> withFiles(block: suspend () -> T): T = session.withFiles(block)

    /** 3028 的 0–3：官方 App 的切換鍵依序循環，各是哪個畫面待實測（ASTRO_LEO3_API.md §2.7）。 */
    override val liveViews: List<DashcamApi.LiveView> = (0..3).map { DashcamApi.LiveView(it, "畫面 ${it + 1}") }

    /**
     * 即時串流要錄影模式：沒有人在用檔案就馬上恢復（不等 10 秒）→ 2015&par=1 → 3028&par=n → 播 2019 回的網址。
     */
    override suspend fun startLive(view: DashcamApi.LiveView): DashcamApi.LiveSource {
        try {
            session.restoreNow()
        } catch (e: IOException) {
            DiagLog.log("切回錄影模式失敗：${e.message}")
        }
        command(CMD_LIVE, par = "1")
        command(CMD_LIVE_VIEW, par = view.id.toString())
        val link = liveLink ?: try {
            NovatekParsers.parseLiveLink(raw(CMD_LIVE_LINK))
        } catch (e: IOException) {
            null
        }
        liveLink = link
        // 測試模式的假記錄器在別的位址：網址裡的主機換成目前的記錄器位址
        val url = (link ?: "rtsp://$hostName/xxx.mov").replace(Regex("""^rtsp://[^/]+"""), "rtsp://$hostName")
        return DashcamApi.LiveSource(url, forceTcp = true)
    }

    /**
     * 不送 2015&par=0：官方 App 紀錄裡停掉即時串流後，記錄器沒在錄影時就開始不了錄影（-13），
     * 留著串流比較保險（官方 App 連線期間也一直開著）。
     */
    override suspend fun stopLive() {}

    override suspend fun recordingStatus(): DashcamApi.RecordingStatus {
        val recording = isRecordingNow()
        val seconds = command(CMD_RECORD_TIME, quiet = true).value?.toLongOrNull() ?: 0
        val mode = when (session.mode) {
            AstroSession.Mode.Files -> "（檔案模式，離開檔案頁約 10 秒後恢復錄影）"
            else -> ""
        }
        val text = when {
            recording && seconds > 0 -> "錄影中（這段已錄 $seconds 秒）"
            recording -> "錄影中"
            else -> "未錄影$mode" + (session.restoreFailure?.let { "（恢復錄影失敗：$it）" } ?: "")
        }
        return DashcamApi.RecordingStatus(recording, text)
    }

    override suspend fun cardStatus(): String {
        val reply = command(CMD_CARD_STATUS)
        return when (reply.value) {
            "1" -> "正常"
            null -> "記錄器沒有回報（Status ${reply.status}）"
            else -> "狀態碼 ${reply.value}"
        }
    }

    /** 3017 的格式待實測：數字就當作剩餘位元組；總容量讀不到。 */
    override suspend fun storageInfo(): DashcamApi.StorageInfo {
        val reply = command(CMD_FREE_SPACE)
        DiagLog.detail("3017 剩餘空間回應：$reply")
        val bytes = reply.value?.toLongOrNull()
        return DashcamApi.StorageInfo(null, bytes?.let(Formats::size) ?: reply.value, null, bytes)
    }

    override suspend fun readSettings(): DashcamApi.SettingsSnapshot {
        val statusXml = raw(CMD_STATUS)
        DiagLog.detail("3014 全文：$statusXml")
        val menuItems = menu ?: try {
            NovatekParsers.parseMenu(raw(CMD_MENU, str = "all", quiet = true))
        } catch (e: IOException) {
            emptyList()
        }.also { menu = it }
        val sizeList = sizes ?: try {
            NovatekParsers.parseMovieSizes(raw(CMD_MOVIE_SIZES, quiet = true))
        } catch (e: IOException) {
            emptyList()
        }.also { sizes = it }
        val extra = LinkedHashMap<String, String>()
        try {
            command(CMD_VERSION).string?.let { extra["韌體"] = it }
            command(CMD_DEVICE_INFO).string?.split(';')?.let { parts ->
                if (parts.size > 1) extra["晶片"] = parts[1]
                parts.lastOrNull()?.takeIf { ':' in it && it.length == 17 }?.let { extra["MAC"] = it }
            }
        } catch (e: IOException) {
            // 只是額外資訊
        }
        return AstroSettings.snapshot(NovatekParsers.parseStatusPairs(statusXml), menuItems, sizeList, extra)
    }

    /** 同官方 App：先停錄，再送 cmd&par；改完讀 3014 確認。 */
    override suspend fun changeSetting(def: SettingDef, value: String): String? {
        val cmd = def.key.toInt()
        session.withRecordingStopped { require(command(cmd, par = value)) }
        repeat(3) {
            val now = try {
                NovatekParsers.parseStatusPairs(raw(CMD_STATUS, quiet = true))[def.key]
            } catch (e: IOException) {
                null
            }
            if (now == value || it == 2) return now
            delay(1000)
        }
        return null
    }

    /**
     * 3010&par=1（官方 App 也送這個，但還沒看過記錄器的回應）：回應可能要等格式化做完，所以讀取逾時放寬；
     * 沒回應不代表沒在格式化，一樣等記憶卡狀態回到正常、列表變空。結束後馬上恢復錄影，恢復不了要讓使用者知道。
     */
    override suspend fun formatCard(onProgress: (String) -> Unit): String {
        val result = runFormat(onProgress)
        return try {
            session.restoreNow()
            result
        } catch (e: IOException) {
            "$result。記錄器無法開始錄影：${e.message}"
        }
    }

    private suspend fun runFormat(onProgress: (String) -> Unit): String =
        // 整段等待都在「停錄」裡：閒置計時不會在格式化進行中恢復錄影
        session.withRecordingStopped {
            val reply = try {
                NovatekParsers.parseReply(raw(CMD_FORMAT, par = "1", readTimeoutMs = formatTimeoutMs))
            } catch (e: IOException) {
                null
            }
            DiagLog.log("格式化記憶卡：${reply?.let { "Status ${it.status}" } ?: "記錄器沒有回應"}")
            if (reply != null && !reply.ok) return@withRecordingStopped "記錄器拒絕格式化（Status ${reply.status}）"
            val deadline = System.currentTimeMillis() + formatTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                delay(2000)
                val card = try {
                    cardStatus()
                } catch (e: IOException) {
                    null // 格式化期間記錄器可能暫時不回應
                }
                onProgress(card ?: "等待記錄器回應…")
                if (card == "正常") {
                    val left = try {
                        listAll().values.sumOf { it.size }
                    } catch (e: IOException) {
                        -1
                    }
                    if (left == 0) return@withRecordingStopped "格式化完成，記憶卡已清空"
                    if (left > 0) onProgress("記憶卡上還有 $left 個檔案…")
                }
            }
            if (reply == null) {
                "記錄器沒有回應格式化，記憶卡也沒有清空。請再試一次；仍然不行就取出記憶卡，用電腦格式化"
            } else {
                "已送出格式化，但還沒確認完成，請稍後重新整理檔案列表確認"
            }
        }

    companion object {
        const val CMD_RECORD = 2001
        const val CMD_LIVE = 2015
        const val CMD_RECORD_TIME = 2016
        const val CMD_LIVE_LINK = 2019
        const val CMD_MODE = 3001
        const val CMD_SET_DATE = 3005
        const val CMD_SET_TIME = 3006
        const val CMD_FORMAT = 3010
        const val CMD_VERSION = 3012
        const val CMD_STATUS = 3014
        const val CMD_FILE_LIST = 3015
        const val CMD_FREE_SPACE = 3017
        const val CMD_CARD_STATUS = 3024
        const val CMD_LIVE_VIEW = 3028
        const val CMD_MOVIE_SIZES = 3030
        const val CMD_MENU = 3031
        const val CMD_DELETE = 4003
        const val CMD_KEEP_ALIVE = 8107
        const val CMD_DEVICE_INFO = 8567

        /** 3001 的 par：檔案頁送 2（APK），錄影模式 1（Novatek 慣例，待實測）。 */
        const val MODE_PLAYBACK = "2"
        const val MODE_MOVIE = "1"

        const val KEEP_ALIVE_MS = 5_000L
        const val START_ATTEMPTS = 3
        const val START_RETRY_MS = 1_500L
        /** 等 3010 回應、之後再等列表變空，各最多這麼久。 */
        const val FORMAT_TIMEOUT_MS = 60_000L
    }
}
