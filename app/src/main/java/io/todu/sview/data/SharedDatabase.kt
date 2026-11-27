package io.todu.sview.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 应用级键值库，按键写入 SQLite，避免每次修改都重写全部数据。 */
class SharedDatabase private constructor(private val context: Context) {
    private var openedPath: String? = null
    private var openedHelper: SQLiteOpenHelper? = null
    private val helper: SQLiteOpenHelper get() {
        val root = io.todu.sview.wear.WatchWorkspaceStore.root(context)
        val path = if (root == context.filesDir) "shared-values.db" else java.io.File(root, "shared-values.db").path
        if (openedPath != path) {
            openedHelper?.close()
            openedHelper = createHelper(context, path)
            openedPath = path
        }
        return checkNotNull(openedHelper)
    }

    /** 切换工作区前关闭旧连接，避免旧代次被删除后仍持有 SQLite 文件。 */
    @Synchronized fun closeForSync() { openedHelper?.close(); openedHelper = null; openedPath = null }

    private fun createHelper(context: Context, path: String) = object : SQLiteOpenHelper(context.applicationContext, path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE entries (key TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    /** 磁盘读取由调用方放在 IO 线程，数据库连接首次使用时才打开。 */
    @Synchronized fun list(): List<ScriptVariable> = helper.readableDatabase.query(
        "entries", arrayOf("key", "type", "value"), null, null, null, null, "key COLLATE NOCASE"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(ScriptVariable(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
    }

    @Synchronized fun get(key: String): Any {
        DatabaseValue.validateKey(key)
        return DatabaseValue.json(find(helper.readableDatabase, key))
    }

    @Synchronized fun getAll(): JSONObject = JSONObject().apply { list().forEach { put(it.name, DatabaseValue.json(it)) } }

    /** 脚本写入允许覆盖同名键；返回前事务已提交，重启后仍可读取。 */
    @Synchronized fun set(key: String, value: Any) = write(DatabaseValue.fromValue(key, value), null, false)

    /** 表单新增不覆盖同名键，编辑时核对原值，防止覆盖脚本刚写入的数据。 */
    @Synchronized fun edit(value: ScriptVariable, previous: ScriptVariable?) = write(DatabaseValue.normalized(value), previous, true)

    private fun write(entry: ScriptVariable, previous: ScriptVariable?, checkPrevious: Boolean) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val oldKey = previous?.name ?: entry.name
            val current = find(db, oldKey)
            if (checkPrevious) require(current == previous) { if (previous == null) "键已存在" else "数据已变化，请重新打开编辑" }
            if (oldKey != entry.name) require(find(db, entry.name) == null) { "键已存在" }
            val bytes = entry.value.toByteArray().size
            db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(CAST(value AS BLOB))), 0) FROM entries", null).use { cursor ->
                cursor.moveToFirst()
                require(cursor.getInt(0) + (if (current == null) 1 else 0) <= 1000) { "最多保存 1000 个键" }
                require(cursor.getLong(1) - (current?.value?.toByteArray()?.size ?: 0) + bytes <= 2 * 1024 * 1024) { "数据库内容不能超过 2 MB" }
            }
            val values = ContentValues().apply { put("key", entry.name); put("type", entry.type); put("value", entry.value) }
            check(db.insertWithOnConflict("entries", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存失败" }
            if (oldKey != entry.name) db.delete("entries", "key = ?", arrayOf(oldKey))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value++
    }

    /** 删除不存在的键返回 false；界面删除还会核对用户看到的原值。 */
    @Synchronized fun remove(key: String, expected: ScriptVariable? = null): Boolean {
        DatabaseValue.validateKey(key)
        val db = helper.writableDatabase
        if (expected != null) require(find(db, key) == expected) { "数据已变化，请重新打开操作" }
        val removed = db.delete("entries", "key = ?", arrayOf(key)) > 0
        if (removed) revision.value++
        return removed
    }

    private fun find(db: SQLiteDatabase, key: String): ScriptVariable? = db.query(
        "entries", arrayOf("key", "type", "value"), "key = ?", arrayOf(key), null, null, null
    ).use { if (it.moveToFirst()) ScriptVariable(it.getString(0), it.getString(1), it.getString(2)) else null }

    companion object {
        @Volatile private var instance: SharedDatabase? = null
        fun get(context: Context): SharedDatabase = instance ?: synchronized(this) {
            instance ?: SharedDatabase(context.applicationContext).also { instance = it }
        }
    }
}
