package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ChecklistItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 日程清单的本机存储：key 为“事件 ID + 实例开始时间”，
 * 值是该日程的清单项（含勾选状态）。卸载或清除数据后丢失。
 */
class ChecklistStore(context: Context) {
    private val prefs = context.getSharedPreferences("zhishi_checklists", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): Map<String, List<ChecklistItem>> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, List<ChecklistItem>>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun setItems(key: String, items: List<ChecklistItem>) {
        val current = load().toMutableMap()
        if (items.isEmpty()) current.remove(key) else current[key] = items
        saveItems(current)
    }

    fun toggleItem(key: String, index: Int) {
        val current = load().toMutableMap()
        val items = current[key]?.toMutableList() ?: return
        if (index !in items.indices) return
        items[index] = items[index].let { it.copy(done = !it.done) }
        current[key] = items
        saveItems(current)
    }

    /** 自我打分：1~5 星，按事件实例存储。 */
    fun loadRatings(): Map<String, Int> {
        val raw = prefs.getString(KEY_RATINGS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Int>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun setRating(key: String, stars: Int) {
        require(stars in 1..5) { "评分必须是 1 到 5 星" }
        saveRatings(loadRatings() + (key to stars))
    }

    fun remove(key: String) {
        val items = load().toMutableMap()
        val ratings = loadRatings().toMutableMap()
        val changed = items.remove(key) != null || ratings.remove(key) != null
        if (changed) {
            saveItems(items)
            saveRatings(ratings)
        }
    }

    /** 删除事件时清理它的所有实例清单与评分。 */
    fun removeByEventId(eventId: Long) {
        val prefix = "$eventId:"
        saveItems(load().filterKeys { !it.startsWith(prefix) })
        saveRatings(loadRatings().filterKeys { !it.startsWith(prefix) })
    }

    private fun saveItems(map: Map<String, List<ChecklistItem>>) {
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(map)).apply()
    }

    private fun saveRatings(map: Map<String, Int>) {
        prefs.edit().putString(KEY_RATINGS, json.encodeToString(map)).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_RATINGS = "ratings"
        fun keyOf(event: CalendarEvent): String = "${event.eventId}:${event.startMillis}"
        fun keyOf(eventId: Long, startMillis: Long): String = "$eventId:$startMillis"
    }
}
