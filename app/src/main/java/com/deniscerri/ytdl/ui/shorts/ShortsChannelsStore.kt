package com.deniscerri.ytdl.ui.shorts

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ShortsChannelsStore {
    private const val PREFS = "shorts_channels"
    private const val KEY = "channels"
    private val listType = object : TypeToken<List<String>>() {}.type

    fun getAll(context: Context): List<String> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try { Gson().fromJson<List<String>>(json, listType) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun add(context: Context, url: String) {
        val list = getAll(context).toMutableList()
        if (!list.contains(url)) list.add(url)
        save(context, list)
    }

    fun remove(context: Context, url: String) {
        save(context, getAll(context).filter { it != url })
    }

    private fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, Gson().toJson(list)).apply()
    }
}
