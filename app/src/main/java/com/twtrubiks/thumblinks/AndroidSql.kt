package com.twtrubiks.thumblinks

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 手機上的 [Sql]：App 私有空間裡的 `thumblinks.db`。表的建立與升級由 [LinkStore] 負責（`CREATE TABLE IF NOT EXISTS`），
 * 這裡只管開檔。
 */
class AndroidSql(context: Context, name: String = NAME) : Sql, AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) = Unit

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val db: SQLiteDatabase get() = helper.writableDatabase

    override fun execute(sql: String, vararg args: Any?) = db.execSQL(sql, args)

    override fun query(sql: String, vararg args: Any): List<Row> =
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Row((0 until cursor.columnCount).associate { cursor.getColumnName(it) to cursor.value(it) }))
                }
            }
        }

    private fun Cursor.value(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i)
        else -> getString(i)
    }

    override fun <T> transaction(block: () -> T): T {
        val db = db
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = helper.close()

    companion object {
        const val NAME = "thumblinks.db"
    }
}
