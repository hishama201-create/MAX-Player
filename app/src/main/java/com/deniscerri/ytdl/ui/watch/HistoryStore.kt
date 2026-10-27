package com.deniscerri.ytdl.ui.watch

import android.content.Context
import com.deniscerri.ytdl.database.models.ResultItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object HistoryStore {
    private const val PREFS = "watch_history"
    private const val KEY = "items"
    private const val MAX = 50
    private val listType = object : TypeToken<List<ResultItem>>() {}.type

    fun getAll(context: Context): List<ResultItem> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try { Gson().fromJson<List<ResultItem>>(json, listType) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun add(context: Context, item: ResultItem) {
        val list = getAll(context).toMutableList()
        list.removeAll { it.url == item.url }
        list.add(0, item.copy(urls = "", chapters = null))
        save(context, list.take(MAX))
    }

    private fun save(context: Context, list: List<ResultItem>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, Gson().toJson(list)).apply()
    }
}
