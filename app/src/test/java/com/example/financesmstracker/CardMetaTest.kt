package com.example.financesmstracker

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CardMetaTest {
    private val ids = listOf("airtel", "ace", "hdfc")
    private lateinit var meta: CardMeta

    @Before
    fun setUp() {
        meta = CardMeta(MemoryPrefs())
    }

    @Test
    fun unsharedCardKeepsItsOwnLimitAndDueDay() {
        meta.save("hdfc", 100_000_00, 5, emptySet(), ids)
        assertEquals(100_000_00L, meta.limitMinor("hdfc"))
        assertEquals(5, meta.dueDay("hdfc"))
        assertEquals(setOf("hdfc"), meta.sharedWith("hdfc", ids))
    }

    @Test
    fun sharedCardsSeeOneCombinedLimit() {
        meta.save("airtel", 245_000_00, 21, setOf("ace"), ids)
        assertEquals(245_000_00L, meta.limitMinor("airtel"))
        assertEquals(245_000_00L, meta.limitMinor("ace"))
        assertEquals(setOf("airtel", "ace"), meta.sharedWith("ace", ids))
        // Each card still has its own due day.
        assertEquals(21, meta.dueDay("airtel"))
        assertEquals(0, meta.dueDay("ace"))
    }

    @Test
    fun editingTheLimitFromEitherCardUpdatesBoth() {
        meta.save("airtel", 245_000_00, 21, setOf("ace"), ids)
        meta.save("ace", 300_000_00, 21, setOf("airtel"), ids)
        assertEquals(300_000_00L, meta.limitMinor("airtel"))
        assertEquals(300_000_00L, meta.limitMinor("ace"))
    }

    @Test
    fun unsharingTwoCardsDissolvesTheGroupAndKeepsTheLimit() {
        meta.save("airtel", 245_000_00, 21, setOf("ace"), ids)
        meta.save("airtel", 245_000_00, 21, emptySet(), ids)
        assertNull(meta.groupOf("airtel"))
        assertNull(meta.groupOf("ace"))
        assertEquals(245_000_00L, meta.limitMinor("airtel"))
        assertEquals(245_000_00L, meta.limitMinor("ace"))
    }

    @Test
    fun leavingAThreeCardGroupKeepsTheOtherTwoTogether() {
        meta.save("airtel", 200_000_00, 21, setOf("ace", "hdfc"), ids)
        meta.save("hdfc", 50_000_00, 3, emptySet(), ids)
        assertEquals(setOf("airtel", "ace"), meta.sharedWith("airtel", ids))
        assertEquals(200_000_00L, meta.limitMinor("ace"))
        assertEquals(50_000_00L, meta.limitMinor("hdfc"))
    }
}

/** In-memory SharedPreferences, so these tests run on the plain JVM (Robolectric cannot load on this build's SDK). */
private class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key; pending.remove(key) }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
    }
}
