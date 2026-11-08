package app.androidrik.rikkahub.data.sync

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Validate the captured bytes, never the live database. Any failure aborts publication. */
internal fun validateBackupDatabase(snapshot: File) {
    SQLiteDatabase.openDatabase(snapshot.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                "Backup database integrity_check failed"
            }
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            check(!cursor.moveToFirst()) { "Backup database foreign_key_check failed" }
        }
    }
}
