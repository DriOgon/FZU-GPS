package com.driogon.fzucheckin.automation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** No credentials, authentication responses or raw school data belong in this database. */
class AutomationStore(context: Context, databaseName: String = "automation.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
    private val mutable = MutableStateFlow(AutomationSettings())
    val state = mutable.asStateFlow()

    init { read().also { mutable.value = it } }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL DEFAULT 0, minute INTEGER NOT NULL DEFAULT 1265, version INTEGER NOT NULL DEFAULT 0, generation INTEGER NOT NULL DEFAULT 0, account TEXT NOT NULL DEFAULT '', first_due INTEGER NOT NULL DEFAULT 0, paused TEXT NOT NULL DEFAULT '', message TEXT NOT NULL DEFAULT '尚未执行')")
        db.execSQL("INSERT INTO settings(id) VALUES(1)")
        db.execSQL("CREATE TABLE submissions (key TEXT PRIMARY KEY, state TEXT NOT NULL)")
        db.execSQL("CREATE TABLE runs (id TEXT PRIMARY KEY, tries INTEGER NOT NULL DEFAULT 0, confirms INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun read(): AutomationSettings = readableDatabase.rawQuery("SELECT * FROM settings WHERE id=1", null).use {
        check(it.moveToFirst())
        fun long(name: String) = it.getLong(it.getColumnIndexOrThrow(name))
        fun string(name: String) = it.getString(it.getColumnIndexOrThrow(name))
        AutomationSettings(long("enabled") == 1L, long("minute").toInt(), long("version"), long("generation"),
            string("account"), long("first_due"), string("paused"), string("message"))
    }

    private fun publish() { mutable.value = read() }
    private fun write(values: ContentValues) { check(writableDatabase.update("settings", values, "id=1", null) == 1); publish() }

    @Synchronized fun attachAccount(id: String) {
        val old = read()
        if (old.accountId == id) return
        write(ContentValues().apply {
            put("account", id); put("generation", old.generation + 1); put("version", old.version + 1)
            put("enabled", 0); put("paused", ""); put("first_due", 0); put("message", "尚未执行")
        })
    }

    @Synchronized fun configure(enabled: Boolean, minute: Int, firstDue: Long, expectedAccount: String? = null) {
        require(minute in 0..1439)
        val old = read()
        require(!enabled || old.accountId.isNotEmpty())
        require(!enabled || expectedAccount == null || expectedAccount == old.accountId) { "账号已改变，请重新登录后设置" }
        write(ContentValues().apply {
            put("enabled", if (enabled) 1 else 0); put("minute", minute); put("version", old.version + 1)
            put("first_due", firstDue); put("paused", "")
        })
    }

    @Synchronized fun invalidateAccount() {
        val old = read()
        write(ContentValues().apply {
            put("enabled", 0); put("version", old.version + 1); put("generation", old.generation + 1)
            put("account", ""); put("paused", ""); put("first_due", 0); put("message", "尚未执行")
        })
    }

    @Synchronized fun clearRecords() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("submissions", null, null); writableDatabase.delete("runs", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    @Synchronized fun message(task: AutoTask, text: String, pause: Boolean = false, finished: Boolean = false): Boolean {
        if (!AutoTimes.current(read(), task)) return false
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("settings", ContentValues().apply {
                put("message", text); if (pause) put("paused", text)
            }, "id=1", null)
            ensureRun(task)
            if (finished) writableDatabase.execSQL("UPDATE runs SET finished=1 WHERE id=?", arrayOf(task.id))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        publish(); return true
    }

    @Synchronized fun submission(key: String): String? = readableDatabase.rawQuery(
        "SELECT state FROM submissions WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    /** The settings check and reservation share the same lock/transaction as invalidation. */
    @Synchronized fun reserve(key: String, task: AutoTask?) {
        writableDatabase.beginTransaction()
        try {
            check(task == null || AutoTimes.current(read(), task)) { "任务设置已改变，本次未提交" }
            check(submission(key) == null) { "本次签到已有提交记录，请先查询确认" }
            writableDatabase.insertOrThrow("submissions", null, ContentValues().apply { put("key", key); put("state", "DISPATCHING") })
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    @Synchronized fun updateSubmission(key: String, state: String) {
        writableDatabase.update("submissions", ContentValues().apply { put("state", state) }, "key=?", arrayOf(key))
    }

    private fun ensureRun(task: AutoTask) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO runs(id) VALUES(?)", arrayOf(task.id))
    }
    @Synchronized fun finished(task: AutoTask): Boolean = readableDatabase.rawQuery(
        "SELECT finished FROM runs WHERE id=?", arrayOf(task.id)).use { it.moveToFirst() && it.getInt(0) == 1 }

    @Synchronized fun count(task: AutoTask, confirmation: Boolean = false): Int {
        val column = if (confirmation) "confirms" else "tries"
        return readableDatabase.rawQuery("SELECT $column FROM runs WHERE id=?", arrayOf(task.id)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }
    @Synchronized fun increment(task: AutoTask, confirmation: Boolean = false): Int {
        val column = if (confirmation) "confirms" else "tries"
        ensureRun(task); writableDatabase.execSQL("UPDATE runs SET $column=$column+1 WHERE id=?", arrayOf(task.id))
        return count(task, confirmation)
    }
}
