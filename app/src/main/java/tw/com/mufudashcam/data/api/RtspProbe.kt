package tw.com.mufudashcam.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.com.mufudashcam.data.DiagLog
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * debug 用：以 RTSP DESCRIBE 確認某條即時串流路徑是否存在、包含哪些軌，結果寫進診斷紀錄。
 * 實測 V60P 只有 av4（前鏡頭 + 聲音）與 av5（後鏡頭），其他路徑都回 404。
 * 走 App 行程已綁定的記錄器網路。
 */
object RtspProbe {
    data class Result(val path: String, val ok: Boolean, val videoTracks: Int, val audioTracks: Int, val detail: String)

    suspend fun describe(host: String, port: Int, path: String): Result = withContext(Dispatchers.IO) {
        val url = "rtsp://$host:$port/liveRTSP/$path"
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 4000)
                socket.soTimeout = 4000
                val request = "DESCRIBE $url RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\nUser-Agent: MufuDashcam\r\n\r\n"
                socket.getOutputStream().apply { write(request.toByteArray()); flush() }
                val text = readResponse(socket)
                val status = text.lineSequence().firstOrNull().orEmpty()
                val video = Regex("^m=video", RegexOption.MULTILINE).findAll(text).count()
                val audio = Regex("^m=audio", RegexOption.MULTILINE).findAll(text).count()
                val ok = " 200 " in "$status " && video > 0
                Result(path, ok, video, audio, status.trim())
            }
        } catch (e: IOException) {
            Result(path, false, 0, 0, "${e.javaClass.simpleName}: ${e.message}")
        }.also {
            DiagLog.log("RTSP ${it.path}：${it.detail}，視訊 ${it.videoTracks} 軌、音訊 ${it.audioTracks} 軌")
        }
    }

    /** 讀到標頭 + Content-Length 指定的 SDP 內容為止。 */
    private fun readResponse(socket: Socket): String {
        val input = socket.getInputStream()
        val buf = ByteArray(8192)
        val sb = StringBuilder()
        var contentLength = -1
        var headerEnd = -1
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: java.net.SocketTimeoutException) {
                -1
            }
            if (n <= 0) break
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            if (headerEnd < 0) {
                headerEnd = sb.indexOf("\r\n\r\n")
                if (headerEnd >= 0) {
                    contentLength = Regex("(?i)Content-Length:\\s*(\\d+)").find(sb.substring(0, headerEnd))
                        ?.groupValues?.get(1)?.toInt() ?: 0
                }
            }
            if (headerEnd >= 0 && sb.length >= headerEnd + 4 + contentLength) break
            if (sb.length > 64 * 1024) break
        }
        return sb.toString()
    }
}
