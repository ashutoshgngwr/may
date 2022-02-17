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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * [May] is a deliberately simple persistent key-value store for Android. It uses [SQLite
 * database][SQLiteDatabase] to store its data and [Kryo] binary serialization to (de)serialize
 * objects. Each entry has a [String] key and a value. Typically, values are simple data containers,
 * i.e., POJO or Kotlin data classes.
 *
 * A [May] datastore stores its data in a single table inside its corresponding SQLite database. It
 * uses [key's `hashCode`][String.hashCode] as the table primary key to optimise look-up
 * performance. It uses [ThreadLocal] [Kryo] instances to (de)serialize values and store them as
 * blobs in the SQLite database.
 *
 * ## Multi-threading
 *
 * Under the hood, [May] uses [reentrant read-write locks][ReentrantReadWriteLock] to synchronize
 * all of its operations, allowing multiple concurrent reads and at most one write at any given
 * instant.
 *
 * ## Usage
 *
 * Usually, clients should not create more than a single instance of [May] per datastore.
 *
 *    val may = May.openOrCreateDatastore("path/to/my.db")
 *
 *    // persist value
 *    may.put("key", "value")
 *
 *    // check if key exists in the store
 *    may.contains("key")
 *
 *    // retrieve value
 *    val value: String? = may.getAs<String>("key")
 *
 *    // remove value
 *    val wasRemoved = may.remove("key")
 *
 *    // list 10 keys by prefix in ascending order skipping the first 5 that match.
 *    val keys = may.keys("prefix/", offset = 5, limit = 10)
 *
 *    // close datastore
 *    may.close()
 */
class May private constructor(private val sqlite: SQLiteDatabase) : Closeable {

    private val rwLock = ReentrantReadWriteLock()

    init {
        rwLock.write {
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    `$ID_COL` integer NOT NULL PRIMARY KEY,
                    `$KEY_COL` text NOT NULL,
                    `$VALUE_COL` blob NOT NULL
                );
                """
            )
        }
    }

    /**
     * Closes the datastore.
     */
    override fun close(): Unit = rwLock.write {
        sqlite.close()
    }

    /**
     * Checks if the datastore contains a given [key].
     *
     * @return `true` if the [key] is found in the datastore, false otherwise.
     */
    fun contains(key: String): Boolean = rwLock.read {
        return sqlite.rawQuery(
            "SELECT 1 FROM $TABLE WHERE $ID_COL = ?;",
            arrayOf(key.hashCode().toString())
        ).use { it.count > 0 }
    }

    /**
     * Disables the features enabled by [enableWriteAheadLogging()][enableWriteAheadLogging].
     *
     * @see enableWriteAheadLogging
     * @see SQLiteDatabase.disableWriteAheadLogging
     * @see SQLiteDatabase.enableWriteAheadLogging
     */
    fun disableWriteAheadLogging(): Unit = rwLock.write {
        sqlite.disableWriteAheadLogging()
    }

    /**
     * Enables write-ahead logging on the underlying [SQLiteDatabase]. On enabling, the
     * [SQLiteDatabase] will create more files in the same directory to store WAL.
     *
     * @see SQLiteDatabase.enableWriteAheadLogging
     */
    fun enableWriteAheadLogging(): Unit = rwLock.write {
        sqlite.enableWriteAheadLogging()
    }

    /**
     * Retrieves the value associated with a given [key].
     *
     * @return value of type [Any] if the [key] is found in the datastore, `null` otherwise.
     */
    fun get(key: String): Any? = rwLock.read {
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

    /**
     * Retrieves the value associated with a given [key].
     *
     * @param V type of the associated value.
     * @return value of type [V] if the [key] is found in the datastore and its value is instance of
     * [V], `null` otherwise.
     */
    inline fun <reified V> getAs(key: String): V? {
        return get(key) as? V
    }

    private fun deserializeValue(cursor: Cursor): Any? {
        if (cursor.count < 1) {
            return null
        }

        cursor.moveToFirst()
        val input = cursor.getBlob(0) ?: return null
        return kryoThreadLocal.require().readClassAndObject(Input(input))
    }

    /**
     * Retrieves keys starting with a given [prefix] that are present the datastore.
     *
     * @param prefix key prefix to match (patterns are not allowed).
     * @param offset number of keys to skip in the result.
     * @param limit maximum number of key to return in the [Set].
     * @return a [Set] of keys matching the given [prefix].
     */
    @JvmOverloads
    fun keys(prefix: String = "", offset: Int = 0, limit: Int = -1): Set<String> = rwLock.read {
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

    /**
     * Stores the [value] associated with a [key] in the datastore. If the [key] already exists the
     * in the datastore, it overwrites the existing value.
     *
     * @param key must be unique. Values are overwritten in the datastore for duplicate keys.
     * @param value value associated with the given [key].
     */
    fun <V : Any> put(key: String, value: V) = rwLock.write {
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

    /**
     * Removes a key and its associated value from the datastore. Does nothing if the key doesn't
     * exist in the datastore.
     *
     * @return `true` if the key was removed, `false` otherwise.
     */
    fun remove(key: String): Boolean = rwLock.write {
        return sqlite.delete(TABLE, "$ID_COL = ?", arrayOf(key.hashCode().toString())) > 0
    }

    /**
     * Removes all keys with a given prefix, and their associated values from the datastore.
     *
     * @param keyPrefix empty prefix removes all keys. non-empty prefix removes matching keys only.
     * @return `true` if at least one key was removed, `false` otherwise.
     */
    @JvmOverloads
    fun removeAll(keyPrefix: String = ""): Boolean = rwLock.write {
        return sqlite.delete(TABLE, "$KEY_COL LIKE '$keyPrefix%'", null) > 0
    }

    companion object {
        private const val TABLE = "store"
        private const val ID_COL = "id"
        private const val KEY_COL = "key"
        private const val VALUE_COL = "value"

        // https://hazelcast.com/blog/kryo-serializer/
        @JvmStatic
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

        /**
         * Opens (or creates) a [May] datastore and returns its reference.
         *
         * @param path path of the datastore file.
         * @return a [May] datastore instance.
         */
        @JvmStatic
        fun openOrCreateDatastore(path: String): May {
            return May(SQLiteDatabase.openOrCreateDatabase(path, null))
        }

        /**
         * Opens (or creates) a [May] datastore and returns its reference.
         *
         * @param file reference to the datastore file (created if it doesn't already exist).
         * @return a [May] datastore instance.
         */
        @JvmStatic
        fun openOrCreateDatastore(file: File): May {
            return openOrCreateDatastore(file.path)
        }
    }

    private fun <T> ThreadLocal<T>.require(): T {
        return requireNotNull(get()) { "failed to get value from thread local for the current thread" }
    }
}
