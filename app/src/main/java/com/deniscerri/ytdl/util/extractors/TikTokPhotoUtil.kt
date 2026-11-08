package com.deniscerri.ytdl.util.extractors

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TikTokPhotoPost(val author: String, val title: String, val images: List<String>, val audioUrl: String)

object TikTokPhotoUtil {
    fun fetch(url: String): TikTokPhotoPost? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        val html = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        val key = "__UNIVERSAL_DATA_FOR_REHYDRATION__"
        val m = Regex(key + "[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        val root = JSONObject(m.groupValues[1]).getJSONObject("__DEFAULT_SCOPE__")
        val item = root.getJSONObject("webapp.reflow.video.detail").getJSONObject("itemInfo").getJSONObject("itemStruct")
        val post = item.optJSONObject("imagePost") ?: return null
        val arr = post.getJSONArray("images")
        val images = (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("imageURL").getJSONArray("urlList").getString(0) }
        val audio = item.optJSONObject("music")?.optString("playUrl") ?: ""
        return TikTokPhotoPost(item.getJSONObject("author").optString("uniqueId"), item.optString("desc"), images, audio)
    }
}
