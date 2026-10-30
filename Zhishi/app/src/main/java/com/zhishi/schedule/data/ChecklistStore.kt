package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ChecklistItem
import com.zhishi.schedule.domain.ChecklistKeys
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 日程清单的本机存储：key 为“事件 ID + 实例开始时间”，
 * 值是该日程的清单项（含勾选状态）。卸载或清除数据后丢失。
 */
class ChecklistStore(context: Context) {
    private val prefs = context.getSharedPreferences("zhishi_checklists", Context.MODE_PRIVATE)
    init {
        // 清理旧版评分数据，清单及勾选状态继续沿用。
        if (prefs.contains("ratings")) prefs.edit().remove("ratings").apply()
    }
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

    fun addItem(key: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = load().toMutableMap()
        current[key] = current[key].orEmpty() + ChecklistItem(trimmed)
        saveItems(current)
    }

    fun removeItem(key: String, index: Int) {
        val current = load().toMutableMap()
        val items = current[key]?.toMutableList() ?: return
        if (index !in items.indices) return
        items.removeAt(index)
        if (items.isEmpty()) current.remove(key) else current[key] = items
        saveItems(current)
    }

    fun remove(key: String) {
        val items = load().toMutableMap()
        items.remove(key)
        saveItems(items)
    }

    /** 迁移清单，重复迁移不会覆盖已迁移的数据。 */
    fun move(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val items = load().toMutableMap()
        items.remove(oldKey)?.let { items[newKey] = it }
        saveItems(items)
    }

    /** 删除事件时清理它的所有实例清单。 */
    fun removeByEventId(eventId: Long) {
        val prefix = "$eventId:"
        saveItems(load().filterKeys { !it.startsWith(prefix) })
    }

    private fun saveItems(map: Map<String, List<ChecklistItem>>) {
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(map)).apply()
    }

    companion object {
        private const val KEY_ITEMS = "items"
        fun keyOf(event: CalendarEvent): String = ChecklistKeys.of(event)
        fun keyOf(eventId: Long, startMillis: Long): String = ChecklistKeys.of(eventId, startMillis)
    }
}
