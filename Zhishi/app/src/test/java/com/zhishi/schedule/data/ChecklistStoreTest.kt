package com.zhishi.schedule.data

import android.content.Context
import com.zhishi.schedule.domain.ChecklistItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChecklistStoreTest {
    private lateinit var store: ChecklistStore
    private val oldKey = "1:1000"
    private val newKey = "1:2000"
    private val items = listOf(ChecklistItem("复习", done = true))

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("zhishi_checklists", Context.MODE_PRIVATE).edit().clear().commit()
        store = ChecklistStore(context)
        store.setItems(oldKey, items)
    }

    @Test
    fun `removing a checklist removes its items`() {
        store.remove(oldKey)
        assertFalse(store.load().containsKey(oldKey))
    }

    @Test
    fun `moving an event preserves completion across reload and retry`() {
        store.move(oldKey, newKey)
        store.move(oldKey, newKey)
        val reloaded = ChecklistStore(RuntimeEnvironment.getApplication())
        assertEquals(items, reloaded.load()[newKey])
        assertFalse(reloaded.load().containsKey(oldKey))
    }

    @Test
    fun `removing an event clears all its instances without affecting other events`() {
        store.setItems(newKey, items)
        store.setItems("10:1000", items)
        store.removeByEventId(1)
        assertEquals(setOf("10:1000"), store.load().keys)
    }

    @Test
    fun `upgrade removes legacy ratings without clearing completed checklist items`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("zhishi_checklists", Context.MODE_PRIVATE)
        prefs.edit().putString("ratings", """{"1:1000":5}""").commit()
        val reloaded = ChecklistStore(context)
        assertFalse(prefs.contains("ratings"))
        assertEquals(items, reloaded.load()[oldKey])
        reloaded.move(oldKey, newKey)
        assertEquals(items, ChecklistStore(context).load()[newKey])
        assertFalse(prefs.contains("ratings"))
    }
}
