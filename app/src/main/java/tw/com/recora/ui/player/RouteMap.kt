package tw.com.recora.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import com.waymap.ui.FitRequest
import com.waymap.ui.MapIcon
import com.waymap.ui.MapLine
import com.waymap.ui.MapMarker
import com.waymap.ui.MarkerIcons
import com.waymap.ui.WayMap
import com.waymap.ui.WayMapDefaults
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import tw.com.recora.data.gps.GpsPoint
import tw.com.recora.data.gps.GpsTrack
import tw.com.recora.ui.theme.Scrim
import java.net.InetAddress
import kotlin.math.max

/**
 * 播放畫面下半部的路線地圖（WayMap）：整段已定位的路線，加上播放位置那一秒的標記；
 * 點路線附近跳到那一秒（[onSeek] 傳毫秒）。換段（路線換了）時重新框住整段。
 * [dashcamBound] = 連著記錄器（App 綁在沒有網際網路的記錄器 Wi‑Fi），圖磚改走行動網路。
 */
@Composable
fun RouteMap(
    track: GpsTrack,
    current: GpsPoint?,
    dashcamBound: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val route = remember(track) { track.fixes.map { (_, p) -> LatLng(p.latitude, p.longitude) } }
    if (route.isEmpty()) return
    val lines = remember(route) {
        listOf(MapLine("route", route, color = ROUTE_COLOR, width = 5f, casingColor = "#FFFFFF", casingWidth = 8f))
    }
    val icons = remember(density) {
        val px = with(density) { 22.dp.roundToPx() }
        listOf(MapIcon(NOW_ICON) { MarkerIcons.dot(NOW_COLOR, px) })
    }
    val fit = remember(route) { FitRequest(route, paddingDp = 32, maxZoom = 17.0) }
    // 點地圖時離路線多遠還算點到：整段範圍對角線的 6%，至少 40 m（約一根手指寬）
    val tapRadiusM = remember(route) {
        val diagonal = GeoUtils.haversine(
            LatLng(route.minOf { it.lat }, route.minOf { it.lon }),
            LatLng(route.maxOf { it.lat }, route.maxOf { it.lon }),
        )
        max(40.0, diagonal * 0.06)
    }
    val markers = current?.takeIf { it.fix }
        ?.let { listOf(MapMarker("now", LatLng(it.latitude, it.longitude), icon = NOW_ICON, sortKey = 1f)) }
        ?: emptyList()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dashcamBound) { loadError = null }

    MapTilesOverCellular(dashcamBound)
    Box(modifier) {
        WayMap(
            modifier = Modifier.fillMaxSize(),
            icons = icons,
            markers = markers,
            lines = lines,
            styleUrl = if (dark) WayMapDefaults.STYLE_URL_DARK else WayMapDefaults.STYLE_URL,
            initialCenter = route.first(),
            initialZoom = 15.0,
            fitTo = fit,
            onTap = { p -> track.nearestSecond(p.lat, p.lon, tapRadiusM)?.let { onSeek(it * 1000L) } },
            onMapLoadError = { loadError = it },
        )
        loadError?.let {
            Text(
                if (dashcamBound) "地圖載入失敗：連著記錄器時地圖走行動網路，請確認行動數據已開啟" else "地圖載入失敗：離線時只看得到看過的區域",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                    .background(Scrim.strong, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 連著記錄器時整個 App 綁在記錄器的 Wi‑Fi（bindProcessToNetwork，那裡沒有網際網路），
 * 地圖的圖磚改用行動網路下載（連線與 DNS 都走行動網路）；離開地圖或中斷連線時恢復 MapLibre 預設的連線。
 */
@Composable
private fun MapTilesOverCellular(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                HttpRequestUtil.setOkHttpClient(
                    OkHttpClient.Builder()
                        .socketFactory(network.socketFactory)
                        .dns(object : Dns {
                            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
                        })
                        .build(),
                )
            }

            override fun onLost(network: Network) = HttpRequestUtil.setOkHttpClient(null)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val registered = runCatching { cm.requestNetwork(request, callback) }.isSuccess
        onDispose {
            if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
            HttpRequestUtil.setOkHttpClient(null) // null = MapLibre 預設的連線
        }
    }
}

private const val NOW_ICON = "now"
private const val ROUTE_COLOR = "#1976D2"
private val NOW_COLOR = android.graphics.Color.rgb(229, 57, 53)
