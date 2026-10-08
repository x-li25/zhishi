package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.CalendarEvent

/**
 * 日程完成状态的本机存储。系统日历没有“完成”概念，
 * 这里按“事件 ID + 实例开始时间”标记，重复日程的每一次实例互不影响。
 */
class CompletionStore(context: Context) {
    private val prefs = context.getSharedPreferences("zhishi_completion", Context.MODE_PRIVATE)

    fun loadCompleted(): Set<String> =
        prefs.getStringSet(KEY_COMPLETED, emptySet()).orEmpty().toSet()

    fun isCompleted(event: CalendarEvent): Boolean = keyOf(event) in loadCompleted()

    fun toggle(event: CalendarEvent): Boolean {
        val key = keyOf(event)
        val current = loadCompleted().toMutableSet()
        val nowCompleted = current.add(key).not()
        if (!nowCompleted) current.remove(key)
        prefs.edit().putStringSet(KEY_COMPLETED, current).apply()
        return nowCompleted
    }

    companion object {
        private const val KEY_COMPLETED = "completed_keys"
        fun keyOf(event: CalendarEvent): String = "${event.eventId}:${event.startMillis}"
    }
}
