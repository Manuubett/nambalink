package com.manuubett.nambalink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** The numbers the user chose to keep. They stay on the phone. */
class Db(context: Context) : SQLiteOpenHelper(context, "namba.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE people(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL DEFAULT ''," +
                "org TEXT NOT NULL DEFAULT ''," +
                "phone TEXT NOT NULL," +
                "till TEXT NOT NULL DEFAULT ''," +
                "paybill TEXT NOT NULL DEFAULT ''," +
                "acc TEXT NOT NULL DEFAULT ''," +
                "saved_ms INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only
    }

    private fun values(p: Person): ContentValues {
        val v = ContentValues()
        v.put("name", p.name)
        v.put("org", p.org)
        v.put("phone", p.phone)
        v.put("till", p.till)
        v.put("paybill", p.paybill)
        v.put("acc", p.acc)
        v.put("saved_ms", System.currentTimeMillis())
        return v
    }

    // Keeps one entry per phone number: saving again updates it
    fun save(p: Person): Long {
        val db = writableDatabase
        val existing = idForPhone(p.phone)

        return if (existing > 0L) {
            db.update("people", values(p), "id=?", arrayOf(existing.toString()))
            existing
        } else {
            db.insert("people", null, values(p))
        }
    }

    fun idForPhone(phone: String): Long {
        val c = readableDatabase.rawQuery(
            "SELECT id FROM people WHERE phone=? LIMIT 1", arrayOf(phone)
        )

        try {
            return if (c.moveToFirst()) c.getLong(0) else 0L
        } finally {
            c.close()
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete("people", "id=?", arrayOf(id.toString()))
    }

    fun all(): List<Person> {
        val out = ArrayList<Person>()

        readableDatabase.rawQuery(
            "SELECT id, name, org, phone, till, paybill, acc FROM people " +
                "ORDER BY saved_ms DESC LIMIT 500",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Person(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getString(5),
                        c.getString(6)
                    )
                )
            }
        }

        return out
    }
}
