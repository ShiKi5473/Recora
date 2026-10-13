package tw.com.dashcam.data.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** 沒有綁定記錄器網路，請求被 [RouteGuard] 擋下。 */
class DashcamOfflineException : IOException("未連線到記錄器")

/**
 * 只在 App 綁定記錄器網路（或 debug 測試模式）時才放行請求。
 *
 * 192.72.1.1 不是私有位址：沒綁定時請求會走手機的預設網路（行動網路或家裡的 Wi-Fi），
 * 送到網際網路上一台不相干的主機。記錄器斷線後的下載重試、離開即時畫面時的 streamerStop
 * 都會碰到，所以在共用的 OkHttpClient 統一擋下。
 */
class RouteGuard(private val canReachDashcam: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!canReachDashcam()) throw DashcamOfflineException()
        return chain.proceed(chain.request())
    }
}
