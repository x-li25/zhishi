package com.zhishi.schedule.data

import android.Manifest
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalendarRepositoryTest {
    private lateinit var repository: CalendarRepository
    private lateinit var provider: TransactionalCalendarProvider
    private val event = ParsedEvent("复习", 1_000L, 61_000L, reminderMinutes = listOf(10))

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        provider = TransactionalCalendarProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        repository = CalendarRepository(context)
    }

    @After
    fun tearDown() {
        provider.db.close()
    }

    @Test
    fun `failed reminder insertion rolls back the event so retry creates only one`() = runBlocking {
        provider.failNextReminder = true
        assertTrue(runCatching { repository.insertEvent(event) }.isFailure)
        assertEquals(0, provider.count("events"))
        assertEquals(0, provider.count("reminders"))

        val eventId = repository.insertEvent(event)
        assertEquals(1, provider.count("events"))
        assertEquals(1, provider.count("reminders"))
        provider.db.rawQuery("SELECT event_id, minutes FROM reminders", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(eventId, it.getLong(0))
            assertEquals(10, it.getInt(1))
        }
    }

    @Test
    fun `failed update restores the original event and reminders`() = runBlocking {
        val eventId = repository.insertEvent(event)
        provider.failNextReminder = true
        val update = event.copy(title = "预习", startMillis = 2_000L, reminderMinutes = listOf(20),
            action = ParsedActionType.UPDATE, targetEventId = eventId)
        assertTrue(runCatching { repository.updateEvent(update) }.isFailure)
        provider.db.rawQuery("SELECT title, dtstart FROM events", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(event.title, it.getString(0))
            assertEquals(event.startMillis, it.getLong(1))
        }
        provider.db.rawQuery("SELECT minutes FROM reminders", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(10, it.getInt(0))
        }
        repository.updateEvent(update)
        assertEquals(1, provider.count("events"))
        assertEquals(1, provider.count("reminders"))
        provider.db.rawQuery("SELECT title, dtstart FROM events", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(update.title, it.getString(0))
            assertEquals(update.startMillis, it.getLong(1))
        }
        provider.db.rawQuery("SELECT minutes FROM reminders", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(20, it.getInt(0))
        }
    }

    @Test
    fun `reminders are deduplicated and negative values ignored`() = runBlocking {
        repository.insertEvent(event.copy(reminderMinutes = listOf(10, 10, -5, 0)))
        assertEquals(2, provider.count("reminders"))
    }

    @Test
    fun `updating a missing event fails without inserting reminders`() = runBlocking {
        val update = event.copy(action = ParsedActionType.UPDATE, targetEventId = 999)
        assertTrue(runCatching { repository.updateEvent(update) }.isFailure)
        assertEquals(0, provider.count("events"))
        assertEquals(0, provider.count("reminders"))
    }

    @Test
    fun `detail edit changes full title and location while preserving times reminders timezone and notes`() = runBlocking {
        val id = repository.insertEvent(event.copy(location = "旧地点"))
        val notes = "这是一段较长的备注\n第二行需要完整保留"
        provider.db.update("events", ContentValues().apply { put("description", notes) }, "_id = ?", arrayOf(id.toString()))
        val title = "概率论与数理统计：随机变量及其分布的完整课程名称"
        val location = "建华／经管新楼 LG1-16，东侧入口第二间教室"
        repository.updateEventDetails(id, title, location)
        provider.db.rawQuery("SELECT title, eventLocation, dtstart, dtend, eventTimezone, description, hasAlarm FROM events WHERE _id = ?", arrayOf(id.toString())).use {
            assertTrue(it.moveToFirst())
            assertEquals(title, it.getString(0))
            assertEquals(location, it.getString(1))
            assertEquals(event.startMillis, it.getLong(2))
            assertEquals(event.endMillis, it.getLong(3))
            assertEquals(java.time.ZoneId.systemDefault().id, it.getString(4))
            assertEquals(notes, it.getString(5))
            assertEquals(1, it.getInt(6))
        }
        assertEquals(1, provider.count("reminders"))
        provider.db.rawQuery("SELECT minutes FROM reminders WHERE event_id = ?", arrayOf(id.toString())).use {
            assertTrue(it.moveToFirst())
            assertEquals(10, it.getInt(0))
        }
    }

    @Test
    fun `clearing detail location removes it without changing original event time`() = runBlocking {
        val id = repository.insertEvent(event.copy(location = "旧地点"))
        repository.updateEventDetails(id, "新标题", "  ")
        provider.db.rawQuery("SELECT eventLocation, dtstart FROM events WHERE _id = ?", arrayOf(id.toString())).use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertEquals(event.startMillis, it.getLong(1))
        }
    }

    @Test
    fun `detail edit protects all day and recurring events from changing whole series`() = runBlocking {
        listOf(CalendarContract.Events.ALL_DAY, CalendarContract.Events.RRULE).forEach { column ->
            val id = repository.insertEvent(event)
            provider.db.update("events", ContentValues().apply {
                if (column == CalendarContract.Events.ALL_DAY) put(column, 1) else put(column, "FREQ=WEEKLY")
            }, "_id = ?", arrayOf(id.toString()))
            assertTrue(runCatching { repository.updateEventDetails(id, "不应写入", "新地点") }.isFailure)
            provider.db.rawQuery("SELECT title FROM events WHERE _id = ?", arrayOf(id.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals(event.title, it.getString(0))
            }
        }
    }

    @Test
    fun `detail edit requires calendar write permission`() = runBlocking {
        val id = repository.insertEvent(event)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_CALENDAR)
        val failure = runCatching { repository.updateEventDetails(id, "不应写入", null) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("写入权限"))
    }

    @Test
    fun `detail edit rejects read only calendar accounts`() = runBlocking {
        val id = repository.insertEvent(event)
        provider.accessLevel = CalendarContract.Calendars.CAL_ACCESS_READ
        val failure = runCatching { repository.updateEventDetails(id, "不应写入", null) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("不允许写入"))
    }

    @Test
    fun `detail edit rejects blank titles and missing events`() = runBlocking {
        val id = repository.insertEvent(event)
        assertTrue(runCatching { repository.updateEventDetails(id, "  ", null) }.isFailure)
        assertTrue(runCatching { repository.updateEventDetails(999L, "新标题", null) }.isFailure)
        assertEquals(1, provider.count("events"))
        assertEquals(1, provider.count("reminders"))
    }

    @Test
    fun `calendar range retains full title location and description for detail display`() = runBlocking {
        val title = "这是一段会在周视图小色块中显示不全的很长的完整课程名称"
        val location = "这是一段包含楼宇楼层房间号和路线说明的完整地点"
        val notes = "课程介绍\n请携带教材和作业"
        val id = repository.insertEvent(event.copy(title = title, location = location))
        provider.db.update("events", ContentValues().apply { put("description", notes) }, "_id = ?", arrayOf(id.toString()))
        val actual = repository.eventsForDate(LocalDate.of(1970, 1, 1), ZoneOffset.UTC).single()
        assertEquals(title, actual.title)
        assertEquals(location, actual.location)
        assertEquals(notes, actual.description)
        assertEquals(listOf(10), actual.reminderMinutes)
    }

    @Test
    fun `campus imports keep stable markers and retry preserves manual title edits`() = runBlocking {
        val school = CampusEvent("考试", "六教", "考试", 1_000L, 61_000L)
        assertEquals(1 to 0, repository.importCampusEvents(listOf(school, school)))
        provider.db.update("events", ContentValues().apply { put("title", "手动修改过") }, null, null)
        assertEquals(0 to 1, repository.importCampusEvents(listOf(school)))
        assertEquals(setOf(school.marker), repository.campusImportedMarkers())
        provider.db.rawQuery("SELECT title, eventTimezone, eventLocation FROM events", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("手动修改过", it.getString(0))
            assertEquals("Asia/Shanghai", it.getString(1))
            assertEquals("六教", it.getString(2))
        }
        assertEquals(0, provider.count("reminders"))
    }

    @Test
    fun `campus import can retry partially completed batches without duplicates`() = runBlocking {
        val rows = (1..41).map { CampusEvent("课程$it", "教室", "课程", it * 100_000L, it * 100_000L + 60_000L) }
        provider.failEventTitle = "课程41"
        assertTrue(runCatching { repository.importCampusEvents(rows) }.isFailure)
        assertEquals(40, provider.count("events"))
        provider.failEventTitle = null
        assertEquals(1 to 40, repository.importCampusEvents(rows))
        assertEquals(41, provider.count("events"))
    }

    @Test
    fun `campus import requires read permission to verify duplicates before any write`() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_CALENDAR)
        val school = CampusEvent("课程", "六教", "课程", 1_000L, 61_000L)
        assertTrue(runCatching { repository.importCampusEvents(listOf(school)) }.isFailure)
        assertEquals(0, provider.count("events"))
    }

    /** 使用 SQLite 事务模拟系统 CalendarProvider，并注入提醒写入失败。 */
    private class TransactionalCalendarProvider : ContentProvider() {
        val db: SQLiteDatabase = SQLiteDatabase.create(null)
        var failNextReminder = false
        var failEventTitle: String? = null
        var accessLevel = CalendarContract.Calendars.CAL_ACCESS_OWNER

        override fun onCreate(): Boolean {
            db.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY AUTOINCREMENT, calendar_id INTEGER, " +
                "title TEXT, dtstart INTEGER, dtend INTEGER, eventTimezone TEXT, eventLocation TEXT, " +
                "hasAlarm INTEGER, deleted INTEGER DEFAULT 0, rrule TEXT, allDay INTEGER DEFAULT 0, description TEXT, customAppPackage TEXT, customAppUri TEXT)")
            db.execSQL("CREATE TABLE reminders (_id INTEGER PRIMARY KEY AUTOINCREMENT, event_id INTEGER, minutes INTEGER, method INTEGER)")
            return true
        }

        fun count(table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            db.beginTransaction()
            try {
                val results = super.applyBatch(operations)
                db.setTransactionSuccessful()
                return results
            } finally {
                db.endTransaction()
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val table = uri.pathSegments.first()
            if (table == "events" && failEventTitle != null && values?.getAsString("title") == failEventTitle) {
                error("Injected campus event failure")
            }
            if (table == "reminders" && failNextReminder) {
                failNextReminder = false
                error("Injected reminder failure")
            }
            return ContentUris.withAppendedId(uri, db.insertOrThrow(table, null, values))
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            db.update(uri.pathSegments.first(), values, rowSelection(uri, selection), selectionArgs)

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            db.delete(uri.pathSegments.first(), rowSelection(uri, selection), selectionArgs)

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (uri.pathSegments.first() == "calendars") {
                val columns = projection ?: arrayOf(CalendarContract.Calendars._ID)
                return MatrixCursor(columns).apply {
                    addRow(columns.map { column ->
                        if (column == CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL) accessLevel else 1
                    })
                }
            }
            if (uri.pathSegments.first() == "instances") {
                val columns = requireNotNull(projection)
                return MatrixCursor(columns).apply {
                    db.query("events", null, null, null, null, null, "dtstart ASC").use { rows ->
                        while (rows.moveToNext()) {
                            addRow(columns.map { column ->
                                val source = when (column) {
                                    CalendarContract.Instances.EVENT_ID -> CalendarContract.Events._ID
                                    CalendarContract.Instances.BEGIN -> CalendarContract.Events.DTSTART
                                    CalendarContract.Instances.END -> CalendarContract.Events.DTEND
                                    else -> column
                                }
                                val index = rows.getColumnIndexOrThrow(source)
                                if (rows.isNull(index)) null else rows.getString(index)
                            })
                        }
                    }
                }
            }
            return db.query(uri.pathSegments.first(), projection, rowSelection(uri, selection), selectionArgs, null, null, sortOrder)
        }

        override fun getType(uri: Uri): String? = null

        private fun rowSelection(uri: Uri, selection: String?): String? =
            if (uri.pathSegments.size == 1) selection
            else "_id = ${ContentUris.parseId(uri)}" + (selection?.let { " AND ($it)" } ?: "")
    }
}
