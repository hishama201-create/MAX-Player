package com.deniscerri.ytdl.ui.watch

import android.content.Context
import com.deniscerri.ytdl.database.models.ResultItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object FavoritesStore {
    private const val PREFS = "watch_favorites"
    private const val KEY = "items"
    private val listType = object : TypeToken<List<ResultItem>>() {}.type

    fun getAll(context: Context): List<ResultItem> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try { Gson().fromJson<List<ResultItem>>(json, listType) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun isFavorite(context: Context, url: String): Boolean = getAll(context).any { it.url == url }

    // يعيد true إذا صار الفيديو مفضلاً، وfalse إذا أُزيل
    fun toggle(context: Context, item: ResultItem): Boolean {
        val list = getAll(context).toMutableList()
        val existing = list.indexOfFirst { it.url == item.url }
        val nowFav = existing < 0
        if (nowFav) list.add(0, item.copy(urls = "", chapters = null)) else list.removeAt(existing)
        save(context, list)
        return nowFav
    }

    private fun save(context: Context, list: List<ResultItem>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, Gson().toJson(list)).apply()
    }
}
