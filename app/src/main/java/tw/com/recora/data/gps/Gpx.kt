package tw.com.recora.data.gps

import java.util.Locale

/** 把 [GpsTrack] 寫成 GPX 1.1，分享出去（存到雲端硬碟再用 gpx.studio、Google 我的地圖等開啟）。 */
object Gpx {
    /**
     * 只放已定位的點。時間用「第一筆已定位的 GPS 時間 + 第幾秒」，跟影片時間軸一致
     * （GPS 時間本身偶爾重複或跳秒，見 [GpsTrack]）；完全沒有 GPS 時間時不寫 `<time>`。
     * 沒有任何已定位的點回 null。
     */
    fun write(name: String, track: GpsTrack): String? {
        val fixes = track.points.withIndex().filter { it.value?.fix == true }
        if (fixes.isEmpty()) return null
        val timed = fixes.firstOrNull { it.value?.utc != null }
        val start = timed?.let { it.value!!.utc!!.minusSeconds(it.index.toLong()) }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Recora\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("<trk><name>").append(escape(name)).append("</name><trkseg>\n")
            for ((second, p) in fixes) {
                append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", p!!.latitude, p.longitude))
                start?.let { append("<time>").append(it.plusSeconds(second.toLong())).append("</time>") }
                append("</trkpt>\n")
            }
            append("</trkseg></trk>\n</gpx>\n")
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
