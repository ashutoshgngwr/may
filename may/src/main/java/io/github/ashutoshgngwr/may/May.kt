package io.github.ashutoshgngwr.may

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import org.objenesis.strategy.StdInstantiatorStrategy
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File

class May private constructor(private val sqlite: SQLiteDatabase) : Closeable {

    init {
        sqlite.enableWriteAheadLogging()
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $ID_COL integer NOT NULL PRIMARY KEY,
                $KEY_COL text NOT NULL,
                $VALUE_COL blob NOT NULL
            );
        """
        )
    }

    override fun close() {
        sqlite.close()
    }

    fun contains(key: String): Boolean {
        return sqlite.rawQuery(
            "SELECT 1 FROM $TABLE WHERE $ID_COL = ?;",
            arrayOf(key.hashCode().toString())
        ).use { it.count > 0 }
    }

    fun get(key: String): Any? {
        return sqlite.query(
            TABLE,
            arrayOf(VALUE_COL),
            "$ID_COL = ?",
            arrayOf(key.hashCode().toString()),
            null,
            null,
            null,
            "1"
        ).use(this::deserializeValue)
    }

    inline fun <reified T> getAs(key: String): T? {
        return get(key) as? T
    }

    private fun deserializeValue(cursor: Cursor): Any? {
        if (cursor.count < 1) {
            return null
        }

        cursor.moveToFirst()
        val input = cursor.getBlob(0) ?: return null
        return kryoThreadLocal.require().readClassAndObject(Input(input))
    }

    @JvmOverloads
    fun keys(prefix: String = "", offset: Int = 0, limit: Int = -1): Set<String> {
        // `SQLiteDatabase#query` fails when limit is -1, although it is a valid SQLite query.
        return sqlite.rawQuery(
            """
            SELECT $KEY_COL FROM $TABLE
                WHERE $KEY_COL LIKE '$prefix%'
                ORDER BY $KEY_COL ASC
                LIMIT $limit OFFSET $offset
            """,
            null
        ).use(this::readKeys)
    }

    private fun readKeys(cursor: Cursor): Set<String> {
        if (cursor.count == 0) {
            return emptySet()
        }

        val keys = mutableSetOf<String>()
        cursor.moveToFirst()
        do {
            keys.add(cursor.getString(0))
        } while (cursor.moveToNext())

        return keys
    }

    fun <V : Any> put(key: String, value: V) {
        val valueOutputStream = ByteArrayOutputStream()
        val valueOutput = Output(valueOutputStream)
        kryoThreadLocal.require().writeClassAndObject(valueOutput, value)
        valueOutput.flush()

        val row = ContentValues()
        row.put(ID_COL, key.hashCode())
        row.put(KEY_COL, key)
        row.put(VALUE_COL, valueOutputStream.toByteArray())
        sqlite.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(key: String): Boolean {
        return sqlite.delete(TABLE, "$ID_COL = ?", arrayOf(key.hashCode().toString())) > 0
    }

    companion object {
        private const val TABLE = "store"
        private const val ID_COL = "id"
        private const val KEY_COL = "key"
        private const val VALUE_COL = "value"

        // https://hazelcast.com/blog/kryo-serializer/
        private val kryoThreadLocal = object : ThreadLocal<Kryo>() {
            override fun initialValue(): Kryo {
                return Kryo().apply {
                    // make type registration optional
                    isRegistrationRequired = false

                    // allow non-zero arg constructors when deserializing
                    instantiatorStrategy = StdInstantiatorStrategy()
                }
            }
        }

        fun with(path: String): May {
            return May(SQLiteDatabase.openOrCreateDatabase(path, null))
        }

        fun with(file: File): May {
            return May(SQLiteDatabase.openOrCreateDatabase(file, null))
        }
    }

    private fun <T> ThreadLocal<T>.require(): T {
        return requireNotNull(get()) { "failed to get value from thread local for the current thread" }
    }
}
