package io.todu.sview.wear

import android.database.sqlite.SQLiteDatabase
import io.todu.sview.data.ScriptVariable
import java.io.File

/** 数据库在未启用的目录内完成事务，不与正在运行的脚本争用连接。 */
object WatchDatabaseSnapshot {
    fun write(file: File, entries: List<ScriptVariable>) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE entries (key TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, value TEXT NOT NULL)")
                db.compileStatement("INSERT INTO entries(key,type,value) VALUES(?,?,?)").use { statement ->
                    entries.forEach {
                        statement.bindString(1, it.name)
                        statement.bindString(2, it.type)
                        statement.bindString(3, it.value)
                        statement.executeInsert()
                    }
                }
                db.version = 1
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
}
