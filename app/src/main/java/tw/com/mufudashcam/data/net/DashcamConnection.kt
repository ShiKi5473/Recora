package tw.com.mufudashcam.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import tw.com.mufudashcam.data.Brand
import tw.com.mufudashcam.data.DiagLog
import tw.com.mufudashcam.data.SettingsStore
import tw.com.mufudashcam.data.api.DashcamApi
import java.net.InetAddress

sealed interface ConnState {
    data object Idle : ConnState
    /** 等待系統的 Wi-Fi 連線對話框。 */
    data object Requesting : ConnState
    data class Connected(val viaOverride: Boolean, val brand: Brand) : ConnState
    data class Failed(val message: String) : ConnState
}

/**
 * 連上記錄器的 Wi-Fi 並把整個 App 綁到該網路（API 文件 §1：此 AP 沒有網際網路，
 * 不綁定的話系統會把請求送到行動網路）。
 *
 * - [connect]：用 WifiNetworkSpecifier 請求連線卡片選的記錄器（SSID 前綴相符）的網路，系統會跳出選擇對話框。
 * - [start]：監聽所有 Wi-Fi 網路；使用者若已在系統設定手動連上，探測到任一種記錄器就直接綁定。
 *
 * 放在 Application 層級，前景服務下載時網路請求不會因為 Activity 關閉而被釋放。
 * 連線過程記在 [log]，裝置頁會顯示，方便在沒有接電腦時查原因。
 */
class DashcamConnection(
    context: Context,
    private val apis: Map<Brand, DashcamApi>,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()
    val isConnected: Boolean get() = _state.value is ConnState.Connected

    /** 已綁定（或測試模式連上）的記錄器種類；中斷連線、送出最後的指令之前都還在。 */
    @Volatile
    var boundBrand: Brand? = null
        private set

    /** 目前要用哪一種記錄器的介面：連著的那一種，否則連線卡片選的。 */
    val currentBrand: Brand get() = boundBrand ?: settings.state.value.dashcamBrand

    private fun apiFor(brand: Brand): DashcamApi = apis.getValue(brand)

    /**
     * 現在送出的請求會不會真的到記錄器：已綁定記錄器網路，或 debug 測試模式。
     * 給 [RouteGuard] 用；比 [isConnected] 寬一點，中斷連線時送 UserDisconnect 還來得及。
     */
    val canReachDashcam: Boolean get() = boundNetwork != null || settings.state.value.overrideActive

    /** 診斷紀錄（與 API 請求共用）。 */
    val log: StateFlow<List<String>> = DiagLog.lines

    /** 每次連上後呼叫（對時、自動備份緊急錄影、接著下載沒下載完的檔案），傳入連上的記錄器。 */
    var onConnected: (suspend (DashcamApi) -> Unit)? = null

    private val lock = Mutex()

    @Volatile
    private var boundNetwork: Network? = null

    /** 最近一次綁定失敗是因為 VPN 不允許繞過。 */
    @Volatile
    private var blockedByVpn = false

    @Volatile
    private var requestCallback: ConnectivityManager.NetworkCallback? = null
    private var monitorCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (monitorCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 正在等系統對話框時由 connect() 的 callback 處理，避免同一個網路探測兩次
                if (requestCallback != null) return
                scope.launch { autoDetect(network) }
            }

            override fun onLost(network: Network) = handleLost(network)
        }
        cm.registerNetworkCallback(wifiRequest(null), cb)
        monitorCallback = cb
        scope.launch { if (settings.current().overrideActive) connectOverride() }
    }

    fun connect() {
        scope.launch {
            disconnectJob?.join()
            val s = settings.current()
            if (s.overrideActive) {
                connectOverride()
                return@launch
            }
            if (isConnected) return@launch
            releaseRequest()
            val brand = s.dashcamBrand
            val prefix = s.ssidPrefixFor(brand)
            val spec = WifiNetworkSpecifier.Builder()
                .setSsidPattern(PatternMatcher(prefix, PatternMatcher.PATTERN_PREFIX))
                .setWpa2Passphrase(s.passwordFor(brand))
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    log("已連上 Wi-Fi（$network）")
                    scope.launch {
                        if (!tryBind(network, brand, userSelected = true)) {
                            _state.value = ConnState.Failed(
                                if (blockedByVpn) {
                                    VPN_BLOCKED_MESSAGE
                                } else {
                                    "已連上 Wi-Fi，但無法使用此網路（詳見「裝置」頁的診斷紀錄）"
                                },
                            )
                        }
                    }
                }

                override fun onUnavailable() {
                    log("系統回報：找不到或使用者取消")
                    requestCallback = null
                    if (_state.value == ConnState.Requesting) {
                        _state.value = ConnState.Failed("未連線：找不到 $prefix… 的 Wi-Fi，或已取消")
                    }
                }

                override fun onLost(network: Network) = handleLost(network)
            }
            log("要求連線到 ${brand.label} 記錄器（$prefix… 的 Wi-Fi）")
            _state.value = ConnState.Requesting
            requestCallback = cb
            // 使用者在系統對話框按取消時，Android 16 不一定會回呼 onUnavailable，所以加上逾時
            cm.requestNetwork(wifiRequest(spec), cb, REQUEST_TIMEOUT_MS)
        }
    }

    /** 放棄等待系統連線對話框。 */
    fun cancelRequest() {
        if (_state.value != ConnState.Requesting) return
        log("取消等待連線")
        releaseRequest()
        _state.value = ConnState.Idle
    }

    @Volatile
    private var disconnectJob: Job? = null

    /**
     * 中斷連線：先讓記錄器回到平常的狀態（MUFU 同官方 App 通知 UserDisconnect=ON；Astro 恢復錄影），再解除綁定。
     */
    fun disconnect() {
        val wasConnected = isConnected
        val brand = boundBrand
        if (boundNetwork != null || requestCallback != null) log("中斷連線")
        _state.value = ConnState.Idle
        disconnectJob = scope.launch {
            if (wasConnected && brand != null) {
                try {
                    withTimeout(DISCONNECT_TIMEOUT_MS) { apiFor(brand).onDisconnecting() }
                } catch (e: Exception) {
                    log("通知記錄器失敗：${e.message}") // 記錄器已經離線也沒關係
                }
            }
            releaseRequest()
            unbind()
        }
    }

    /** debug：直接用預設網路連 host 覆寫位址（假記錄器，模擬連線卡片選的那一種）。 */
    private suspend fun connectOverride() {
        unbind()
        releaseRequest()
        val brand = settings.current().dashcamBrand
        val api = apiFor(brand)
        val result = api.probe(null)
        log("測試模式探測 ${brand.label} ${api.host}：${result.detail}")
        if (result.ok) {
            boundBrand = brand
            _state.value = ConnState.Connected(viaOverride = true, brand = brand)
            scope.launch { onConnected?.invoke(api) }
        } else {
            _state.value = ConnState.Failed("連不到 ${api.host}：${result.detail}")
        }
    }

    /**
     * 使用者在系統設定手動連上某個 Wi-Fi：依序試每種記錄器（連線卡片選的先試），
     * 記錄器位址在這個網路的直連網段內才探測。
     */
    private suspend fun autoDetect(network: Network) {
        val selected = settings.current().dashcamBrand
        for (brand in listOf(selected) + Brand.dashcams.filter { it != selected }) {
            if (boundNetwork != null) return
            if (tryBind(network, brand, userSelected = false)) return
        }
    }

    /**
     * 探測記錄器並把 App 綁到 [network]。
     * [userSelected] = 使用者在系統對話框選了記錄器的 Wi-Fi：探測失敗仍然綁定，
     * 讓後續的請求顯示實際錯誤，而不是停在「沒有回應」。
     */
    private suspend fun tryBind(network: Network, brand: Brand, userSelected: Boolean): Boolean {
        if (boundNetwork == network) return true
        if (boundNetwork != null || settings.current().overrideActive) return false
        val api = apiFor(brand)
        // 自動偵測時，這個網路可能是家裡的 Wi-Fi：記錄器位址不在同一網段就不探測，
        // 否則探測會經由路由器送到網際網路上的 192.72.1.1。Astro 的 192.168.1.254 常常就在家用網段內，
        // 所以它的探測還會確認回應是記錄器的格式。
        if (!userSelected && !hostOnLink(network, api.host)) return false
        blockedByVpn = false
        if (userSelected) describe(network)

        val attempts = if (userSelected) PROBE_ATTEMPTS else 1
        var ok = false
        for (attempt in 1..attempts) {
            val result = api.probe(network)
            if (result.ok) {
                log("${brand.label} 記錄器 ${api.host} 有回應（${result.detail}）")
                ok = true
                break
            }
            if (userSelected) log("探測 ${api.host} 失敗（第 $attempt/$attempts 次）：${result.detail}")
            // VPN 不允許 App 繞過它時，綁定網路會回 EPERM，重試也沒用
            if ("EPERM" in result.detail && isVpnActive()) {
                blockedByVpn = true
                if (userSelected) log("被 VPN 擋住：請在 VPN App 把本 App 排除，或暫停 VPN")
                return false
            }
            if (attempt < attempts) delay(PROBE_RETRY_DELAY_MS)
        }
        if (!ok && !userSelected) return false
        if (!ok) log("仍然綁定此網路，之後的請求會顯示錯誤原因")

        val bound = lock.withLock {
            if (boundNetwork == network) return true
            if (boundNetwork != null) return false
            if (!cm.bindProcessToNetwork(network)) {
                blockedByVpn = isVpnActive()
                log("bindProcessToNetwork 失敗")
                return false
            }
            boundNetwork = network
            boundBrand = brand
            _state.value = ConnState.Connected(viaOverride = false, brand = brand)
            true
        }
        if (bound) {
            log("已綁定到 ${brand.label} 記錄器的網路")
            onConnected?.invoke(api)
        }
        return bound
    }

    /** 把網路細節記進連線紀錄（手機拿到的位址、閘道，以及是否有 VPN）。 */
    private fun describe(network: Network) {
        val lp = cm.getLinkProperties(network)
        val addresses = lp?.linkAddresses?.joinToString { it.toString() } ?: "?"
        val gateways = lp?.routes?.mapNotNull { it.gateway?.hostAddress }?.filter { it != "0.0.0.0" && it != "::" }?.distinct()
        log("介面 ${lp?.interfaceName ?: "?"}，手機位址 $addresses，閘道 ${gateways?.joinToString().orEmpty().ifEmpty { "無" }}")
        if (isVpnActive()) log("注意：手機正在使用 VPN，可能擋住連到記錄器的連線")
    }

    /** 記錄器位址在 [network] 的直連網段內（不經閘道），也就是手機拿到的是記錄器發的位址。 */
    private fun hostOnLink(network: Network, host: String): Boolean {
        val target = runCatching { InetAddress.getByName(host.substringBefore(':')) }.getOrNull() ?: return false
        val routes = cm.getLinkProperties(network)?.routes ?: return false
        return routes.any { !it.hasGateway() && it.destination.prefixLength > 0 && it.matches(target) }
    }

    /** 本 App 目前是否在 VPN 底下（被 VPN App 排除的話這裡會是 false）。 */
    private fun isVpnActive(): Boolean =
        cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun handleLost(network: Network) {
        if (network != boundNetwork) return
        log("記錄器網路中斷")
        unbind()
        _state.value = ConnState.Failed("與記錄器的連線中斷")
    }

    private fun unbind() {
        if (boundNetwork != null) {
            cm.bindProcessToNetwork(null)
            boundNetwork = null
        }
        boundBrand?.let { apiFor(it).onDisconnected() }
        boundBrand = null
    }

    private fun releaseRequest() {
        requestCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        requestCallback = null
    }

    private fun wifiRequest(spec: WifiNetworkSpecifier?): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply { if (spec != null) setNetworkSpecifier(spec) }
            .build()

    private fun log(message: String) = DiagLog.log(message)

    private companion object {
        const val REQUEST_TIMEOUT_MS = 60_000
        const val PROBE_ATTEMPTS = 3
        const val PROBE_RETRY_DELAY_MS = 1500L

        /** Astro 要切回錄影模式再開始錄影（各約 1 秒，被拒絕時還要重試），比 MUFU 的一個指令久。 */
        const val DISCONNECT_TIMEOUT_MS = 8_000L
        const val VPN_BLOCKED_MESSAGE =
            "手機的 VPN（例如 AdGuard）擋住了記錄器連線。\n" +
                "請在 VPN App 中把本 App 設為不經過 VPN，或暫停 VPN 後再連線。"
    }
}
