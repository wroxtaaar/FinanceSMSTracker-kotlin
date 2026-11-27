package com.example.financesmstracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardMetaTest {
    private val ids = listOf("airtel", "ace", "hdfc")
    private lateinit var meta: CardMeta

    @Before
    fun setUp() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("card_meta_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        meta = CardMeta(prefs)
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
