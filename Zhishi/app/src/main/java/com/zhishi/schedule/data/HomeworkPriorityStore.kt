package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** xszyid is the student homework ID, not the common assignment ID (zyid). No name or credentials stored. */
class HomeworkPriorityStore(context: Context) {
    private val preferences = context.getSharedPreferences("homework_priority_v1", Context.MODE_PRIVATE)

    fun decorate(snapshot: HomeworkSnapshot): HomeworkSnapshot = snapshot.copy(items = snapshot.items.map { item ->
        val mark = runCatching { preferences.getString(key(snapshot.semesterId, item), null)?.let(HomeworkPriority::valueOf) }.getOrNull()
        item.copy(priorityMark = mark)
    })

    /** Call on IO dispatcher; success means the manual choice has reached disk. Automatic urgency is derived. */
    fun save(semesterId: String, item: CourseHomework, mark: HomeworkPriority) {
        val editor = preferences.edit()
        val key = key(semesterId, item)
        if (mark == HomeworkPriority.NORMAL) editor.remove(key) else editor.putString(key, mark.name)
        check(editor.commit()) { "本机分类未能保存，请重试" }
    }

    private fun key(semesterId: String, item: CourseHomework): String {
        val identity = JsonArray(listOf(semesterId, item.courseId, item.id).map(::JsonPrimitive)).toString()
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)))
    }
}
