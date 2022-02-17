package io.github.ashutoshgngwr.may

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MayTest {

    data class Value(val value: String)

    private val testData = mapOf(
        "key-0" to Value("value-0"),
        "key-1" to Value("value-1"),
        "key-2" to Value("value-2"),
        "key-3" to Value("value-3"),
        "key-4" to Value("value-4"),
    )

    private lateinit var dbFile: File
    private lateinit var may: May

    @Before
    fun setUp() {
        val cacheDir: File = ApplicationProvider.getApplicationContext<Context>().cacheDir
        dbFile = File.createTempFile("may", ".db", cacheDir)
        may = May.openOrCreateDatastore(dbFile)
        testData.forEach { (k, v) -> may.put(k, v) }
    }

    @After
    fun tearDown() {
        may.close()
        dbFile.delete()
    }

    @Test
    fun contains() {
        may.remove("key-0")
        may.remove("key-1")
        assertFalse(may.contains("key-0"))
        assertFalse(may.contains("key-1"))
        assertTrue(may.contains("key-2"))
        assertTrue(may.contains("key-3"))
        assertTrue(may.contains("key-4"))
    }

    @Test
    fun get() {
        assertNull(may.getAs<String>("key-1"))
        assertEquals(testData["key-1"], may.getAs<Value>("key-1"))
    }

    @Test
    fun keys() {
        val oddKey = "may"
        may.put(oddKey, "may")
        assertEquals(setOf("key-1"), may.keys("key-", offset = 1, limit = 1))
        assertEquals(setOf(oddKey), may.keys(oddKey))
    }

    @Test
    fun put() {
        // duplicate key should replace old value.
        val newVal = "new-value"
        may.put("key-0", newVal)
        assertEquals(newVal, may.getAs<String>("key-0"))
    }

    @Test
    fun remove() {
        assertTrue(may.remove("key-0"))
        assertFalse(may.remove("non-existing-key"))
    }

    @Test
    fun removeAll() {
        // remove specific prefix
        assertTrue(may.removeAll("key-0"))
        assertFalse(may.contains("key-0"))
        assertTrue(may.contains("key-1"))

        // remove everything
        assertTrue(may.removeAll())
        assertFalse(may.contains("key-1"))

        // with nothing to remove
        assertFalse(may.removeAll())
    }
}
