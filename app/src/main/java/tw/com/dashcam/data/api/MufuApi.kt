package tw.com.dashcam.data.api

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import tw.com.dashcam.data.Brand
import tw.com.dashcam.data.Camera
import tw.com.dashcam.data.CgiParsers
import tw.com.dashcam.data.DashcamConfig
import tw.com.dashcam.data.DashcamFile
import tw.com.dashcam.data.DiagLog
import tw.com.dashcam.data.FileNames
import tw.com.dashcam.data.Folder
import tw.com.dashcam.data.PathStyle
import tw.com.dashcam.data.SettingDef
import tw.com.dashcam.data.SettingDefs
import tw.com.dashcam.data.SettingsStore
import tw.com.dashcam.data.net.await
import java.io.IOException
import java.time.ZonedDateTime

/** MUFU V60P 的 HTTP 介面（MUFU_V60P_API.md §2、§3）。 */
class MufuApi(
    private val client: OkHttpClient,
    private val settings: SettingsStore,
) : DashcamApi {
    override val brand: Brand get() = Brand.Mufu

    override val host: String get() = settings.state.value.hostFor(Brand.Mufu)
    private val hostName: String get() = hostNameOf(host)
    private val port: Int get() = portOf(host)

    /** 本次執行已確認可用的下載路徑樣式。 */
    @Volatile
    private var pathStyle: PathStyle? = null

    fun cgiUrl(params: List<Pair<String, String>>): String =
        "http://$host/cgi-bin/Config.cgi?" +
            params.joinToString("&") { (k, v) -> "$k=${FileNames.encodeQueryValue(v)}" }

    suspend fun cgi(params: List<Pair<String, String>>, httpClient: OkHttpClient = client): String =
        withContext(Dispatchers.IO) {
            val url = cgiUrl(params)
            val query = url.substringAfter('?')
            val request = Request.Builder().url(url).build()
            try {
                httpClient.newCall(request).await().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    // dir / reardir 的 XML 很長，由 listFolder 記摘要
                    if (params.none { it.first == "action" && it.second.endsWith("dir") }) {
                        DiagLog.log("→ $query ⇒ HTTP ${resp.code}: ${DiagLog.compact(body)}")
                    }
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    body
                }
            } catch (e: IOException) {
                DiagLog.log("→ $query ⇒ ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }

    /**
     * 透過指定的 [network]（null = 目前預設網路）確認對方是不是記錄器。
     * 實測 devinfo.fwver 在此韌體回 "no this key"，所以只要 Config.cgi 有任何 HTTP 回應就算成功。
     * 記錄器是單核心且邊錄影邊跑 CGI 腳本，逾時給寬一點。
     */
    override suspend fun probe(network: Network?): DashcamApi.ProbeResult = withContext(Dispatchers.IO) {
        val probeClient = probeClient(client, network)
        val request = Request.Builder()
            .url(cgiUrl(listOf("action" to "get", "property" to "devinfo.fwver")))
            .build()
        val started = System.nanoTime()
        try {
            probeClient.newCall(request).await().use { resp ->
                DashcamApi.ProbeResult(true, "HTTP ${resp.code}，${(System.nanoTime() - started) / 1_000_000} ms")
            }
        } catch (e: IOException) {
            DashcamApi.ProbeResult(false, "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: SecurityException) {
            DashcamApi.ProbeResult(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun getProperties(property: String): Map<String, String> =
        CgiParsers.parseKeyValues(cgi(listOf("action" to "get", "property" to property)))

    suspend fun set(property: String, value: String? = null): DashcamApi.SetResult {
        val params = mutableListOf("action" to "set", "property" to property)
        if (value != null) params += "value" to value
        val body = cgi(params)
        return DashcamApi.SetResult(isOk(body), body.trim())
    }

    /** 回應裡一定有 "0 OK"（CGI 腳本本身的輸出），要另外排除錯誤字樣。 */
    private fun isOk(body: String): Boolean =
        "OK" in body && ERROR_MARKERS.none { body.contains(it, ignoreCase = true) }

    /**
     * 讀取錄影狀態（此系列韌體的預覽狀態：Camera.Preview.MJPEG.status.record = Recording / Standby…）。
     * 韌體不支援時回 null。
     */
    override suspend fun recordingStatus(): String? {
        val props = getProperties("Camera.Preview.MJPEG.status.*")
        return props.entries.firstOrNull { it.key.endsWith(".record", ignoreCase = true) }?.value
    }
    suspend fun streamerStart() = set("streamer", "start")
    suspend fun streamerStop() = set("streamer", "stop")
    suspend fun forceIFrame() = set("forceiframe")
    /** 切換預覽鏡頭（官方 App 對 OULI 記錄器用 setcamid，不是 set）。切換後要重新建立 RTSP 連線。 */
    suspend fun setPreviewCamera(camera: Camera): DashcamApi.SetResult {
        val body = cgi(
            listOf(
                "action" to "setcamid", "property" to "Camera.Preview.Source.1.Camid",
                "value" to if (camera == Camera.Front) "front" else "rear",
            ),
        )
        return DashcamApi.SetResult(isOk(body), body.trim())
    }

    /** 記錄器用當地時間（TimeSettings 沒有時區）。 */
    override suspend fun syncTime(now: ZonedDateTime) =
        set("TimeSettings", FileNames.timeSettingsValue(now.toLocalDateTime()))

    /** 同官方 App：離開時通知記錄器 UserDisconnect=ON（記錄器才會恢復一般狀態）。 */
    override suspend fun onDisconnecting() {
        set("UserDisconnect", "ON")
    }

    override suspend fun delete(sdPath: String): DashcamApi.SetResult {
        val body = cgi(listOf("action" to "del", "property" to FileNames.deleteProperty(sdPath)))
        return DashcamApi.SetResult(isOk(body), body.trim())
    }

    /**
     * 逐頁讀取資料夾（每頁 16 筆），每讀到一頁就送出目前累積的完整清單。
     * 與 dashcam_download.py 的 list_files 相同：直到回空頁或沒有新檔案為止。
     * 不看 `<amount>`：實測 V60P 固定回 0，拿來判斷會在第一頁就停。
     */
    override fun listFolder(folder: Folder): Flow<List<DashcamFile>> = flow {
        val seen = HashSet<String>()
        val all = ArrayList<DashcamFile>()
        // 實測：dir 只列前鏡頭，後鏡頭要用 reardir（官方 App 也是這樣）
        for (action in listOf("dir", "reardir")) {
            var from = 0
            for (pageNo in 0 until MAX_PAGES) {
                val xml = cgi(
                    listOf(
                        "action" to action, "property" to folder.cgiName, "format" to "all",
                        "count" to DashcamConfig.PAGE_SIZE.toString(), "from" to from.toString(),
                    ),
                )
                val page = CgiParsers.parseDir(xml)
                if (pageNo == 0 || page.entries.isEmpty()) {
                    DiagLog.log("$action ${folder.cgiName} from=$from ⇒ ${page.entries.size} 筆")
                }
                if (page.entries.isEmpty()) break
                var added = 0
                for (e in page.entries) {
                    val path = FileNames.sdPath(e.name, folder)
                    if (seen.add(path)) {
                        all += DashcamFile(FileNames.basename(path), path, e.size, e.time, folder)
                        added++
                    }
                }
                from += page.entries.size
                // 先確認下載路徑樣式，照片縮圖網址才會正確
                if (pathStyle == null && all.isNotEmpty()) {
                    try {
                        resolveUrl(all.first().sdPath)
                    } catch (e: IOException) {
                        // 之後下載時會再試
                    }
                }
                emit(all.toList())
                if (added == 0) break
            }
        }
        DiagLog.log("列表 ${folder.cgiName} 完成：共 ${all.size} 個檔案")
        if (all.isEmpty()) emit(emptyList())
    }.flowOn(Dispatchers.IO)

    /** 記憶卡容量（Camera.Menu.CardInfo.LifeTimeTotal / RemainLifeTime，官方 App 也讀這兩個）。 */
    override suspend fun storageInfo(): DashcamApi.StorageInfo {
        val info = getProperties("Camera.Menu.CardInfo.*")
        val total = info["Camera.Menu.CardInfo.LifeTimeTotal"] ?: info["Camera.Menu.CardInfo.total"]
        val remain = info["Camera.Menu.CardInfo.RemainLifeTime"] ?: info["Camera.Menu.CardInfo.remain"]
        return DashcamApi.StorageInfo(total, remain, parseSize(total), parseSize(remain))
    }

    /** "117.0G" / "2.6G" / "512M" → bytes */
    private fun parseSize(s: String?): Long? {
        val m = Regex("""([\d.]+)\s*([KMGT]?)""", RegexOption.IGNORE_CASE).find(s ?: return null) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mul = when (m.groupValues[2].uppercase()) {
            "K" -> 1L shl 10
            "M" -> 1L shl 20
            "G" -> 1L shl 30
            "T" -> 1L shl 40
            else -> 1L
        }
        return (n * mul).toLong()
    }

    override fun httpUrl(sdPath: String): String = urlFor(sdPath, pathStyle ?: PathStyle.Mnt)

    private fun urlFor(sdPath: String, style: PathStyle): String =
        HttpUrl.Builder()
            .scheme("http")
            .host(hostName)
            .port(port)
            .addPathSegments(FileNames.httpPath(sdPath, style).trimStart('/'))
            .build()
            .toString()

    /**
     * 找出實際可下載的網址：先試已確認的樣式，否則依序試 Mnt、Root。
     * 兩種都 404 回 null；網路錯誤則丟出 IOException。
     */
    override suspend fun resolveUrl(sdPath: String): String? {
        val known = pathStyle
        val order = if (known != null) listOf(known) + PathStyle.entries.filter { it != known } else PathStyle.entries
        for (style in order) {
            val url = urlFor(sdPath, style)
            val code = rangeProbe(url)
            if (code == 200 || code == 206) {
                if (pathStyle != style) DiagLog.log("下載路徑樣式：${FileNames.httpPath(sdPath, style)}（HTTP $code）")
                pathStyle = style
                return url
            }
            DiagLog.log("下載路徑 ${FileNames.httpPath(sdPath, style)} ⇒ HTTP $code")
        }
        return null
    }

    /** 只要 1 byte 的 GET（GoAhead 不一定支援 HEAD）。 */
    private suspend fun rangeProbe(url: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(request).await().use { it.code }
    }

    fun rtspUrl(path: String): String = "rtsp://$hostName:${DashcamConfig.RTSP_PORT}/liveRTSP/$path"

    /**
     * 即時串流路徑。實測 V60P 只有 liveRTSP/av4 與 av5（API 文件的位元表不適用）。
     * 從官方 App 的紀錄確認：前鏡頭播 av4（影像 + 聲音）；切到後鏡頭時先送 setcamid=rear，再播 av5（只有影像）。
     */
    fun livePath(camera: Camera): String = if (camera == Camera.Front) "av4" else "av5"

    /** 官方 App 開始預覽前會送這個。 */
    suspend fun streamStatusOn() = set("StreamStatus", "ON")

    /** SD 卡狀態（READY / FORMAT_ING / NO_CARD…）。 */
    override suspend fun cardStatus(): String? = getProperties("Camera.Menu.SD0")["Camera.Menu.SD0"]

    /**
     * 格式化記憶卡：由記錄器自己格式化，檔案系統與資料夾結構就是記錄器要的格式。
     * 寫入格式同其他設定（不含 Camera.Menu. 前綴，實測前綴版不生效），與官方 App 共用的預設指令相同。
     */
    private suspend fun sendFormat(): DashcamApi.SetResult = set("SD0", "format")

    /**
     * 修改記錄器設定並讀回確認：寫入用不含前綴的名稱（set&property=SoundRecord），
     * 實測加 Camera.Menu. 前綴不會生效。回傳記錄器目前的值（null = 讀不到）。
     */
    private suspend fun changeSetting(key: String, value: String, timeoutMs: Long): String? {
        // 同官方 App：進入設定模式 → 修改 → 離開設定模式（記錄器可能在 exit 時才寫入 flash）
        set("Setting", "enter")
        try {
            set(key, value)
        } finally {
            set("Setting", "exit")
        }
        // 實測單項讀取（Camera.Menu.X）不可靠，改讀完整清單；改解析度時記錄器約 1 分鐘沒有回應
        var last: String? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            last = try {
                getProperties("Camera.Menu.*")["Camera.Menu.$key"]
            } catch (e: IOException) {
                null
            }
            if (last.equals(value, ignoreCase = true)) break
        }
        return last
    }

    /** 一般與緊急資料夾都沒有檔案（用來確認格式化完成）。 */
    private suspend fun isCardEmpty(): Boolean = listOf(Folder.Normal, Folder.Event).all { folder ->
        val xml = cgi(
            listOf("action" to "dir", "property" to folder.cgiName, "format" to "all", "count" to "1", "from" to "0"),
        )
        CgiParsers.parseDir(xml).entries.isEmpty()
    }

    override val liveViews: List<DashcamApi.LiveView> = listOf(
        DashcamApi.LiveView(0, "前鏡頭", Camera.Front),
        DashcamApi.LiveView(1, "後鏡頭", Camera.Rear),
    )

    /**
     * 同官方 App：StreamStatus=ON → streamer=start → setcamid → 播 av4（前）/ av5（後）。
     * 切換鏡頭後要重新建立 RTSP 連線，先等記錄器切換來源。控制指令失敗不影響播放。
     */
    override suspend fun startLive(view: DashcamApi.LiveView): DashcamApi.LiveSource {
        val camera = view.camera ?: Camera.Front
        ignoreIo { streamStatusOn() }
        ignoreIo { streamerStart() }
        ignoreIo { setPreviewCamera(camera) }
        delay(800)
        return DashcamApi.LiveSource(rtspUrl(livePath(camera)), forceTcp = true)
    }

    /** 逼一張 I-frame，加快起播。 */
    override suspend fun afterLiveStarted() = ignoreIo { forceIFrame() }

    override suspend fun stopLive() = ignoreIo { streamerStop() }

    /**
     * 讀取設定：devinfo.fwver 在此韌體不存在，所以兩組都讀、有什麼就顯示什麼。
     * 可改的項目是 [SettingDefs.all] 中記錄器有回報的；其他唯讀顯示。
     */
    override suspend fun readSettings(): DashcamApi.SettingsSnapshot {
        val devInfo = try {
            getProperties("devinfo.*")
        } catch (e: IOException) {
            emptyMap()
        }
        val menu = getProperties("Camera.Menu.*")
        val defs = SettingDefs.all.filter { "$MENU${it.key}" in menu }
        val editable = defs.map { "$MENU${it.key}" }.toSet()
        return DashcamApi.SettingsSnapshot(
            defs = defs,
            values = defs.associate { it.key to menu.getValue("$MENU${it.key}") },
            readOnly = (devInfo + menu.filterKeys { it !in editable }).mapKeys { it.key.removePrefix(MENU) }.toSortedMap(),
        )
    }

    override suspend fun changeSetting(def: SettingDef, value: String): String? =
        changeSetting(def.key, value, if (def.slowMs > 0) def.slowMs else 20_000)

    /**
     * 格式化記憶卡：送出指令後輪詢 Camera.Menu.SD0，直到記錄器回報 READY。
     * 由記錄器自己格式化，所以檔案系統與資料夾結構就是記錄器要的格式。
     */
    override suspend fun formatCard(onProgress: (String) -> Unit): String {
        val result = sendFormat()
        DiagLog.log("格式化記憶卡：${DiagLog.compact(result.body)}")
        delay(2000)
        var last: String? = null
        var sawBusy = false
        val deadline = System.currentTimeMillis() + FORMAT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            last = try {
                cardStatus()
            } catch (e: IOException) {
                null // 格式化期間記錄器可能暫時不回應
            }
            onProgress(last ?: "等待記錄器回應…")
            if (last != null && !last.equals("READY", ignoreCase = true)) sawBusy = true
            if (last.equals("READY", ignoreCase = true) && (sawBusy || isCardEmpty())) break
            delay(1000)
        }
        return if (last.equals("READY", ignoreCase = true)) {
            "格式化完成，記憶卡已清空"
        } else {
            "已送出格式化，但記錄器目前狀態為 ${last ?: "無回應"}，請稍後確認"
        }
    }

    private companion object {
        const val MENU = "Camera.Menu."
        const val FORMAT_TIMEOUT_MS = 90_000L
        const val MAX_PAGES = 1000
        val ERROR_MARKERS = listOf("no key found", "no this key", "no this parameter", "fail")
    }
}
