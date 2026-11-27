package com.example.financesmstracker

import com.example.financesmstracker.data.Transaction
import com.example.financesmstracker.parser.AccountType
import com.example.financesmstracker.parser.PaymentMethod
import com.example.financesmstracker.parser.TransactionType
import com.example.financesmstracker.ui.HomeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HomeFormatTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance(ist).apply { clear(); set(y, m, d, h, 0, 0) }.timeInMillis

    private fun tx(amount: Long, ts: Long, category: String?, type: TransactionType = TransactionType.DEBIT, merchant: String? = null) =
        Transaction(
            amountPaise = amount, transactionType = type, paymentMethod = PaymentMethod.UPI,
            accountType = AccountType.BANK_ACCOUNT, bank = "AXIS", merchantName = merchant, payeeId = null,
            accountLastFour = "1234", refNumber = null, timestamp = ts, smsHash = "h$ts$amount",
            category = category, parserConfidence = 1f
        )

    @Test
    fun dayLabelsAreTodayYesterdayThenDate() {
        val now = at(2026, Calendar.NOVEMBER, 27, 9)
        assertEquals("Today", HomeFormat.dayLabel(at(2026, Calendar.NOVEMBER, 27, 0), now, ist))
        assertEquals("Yesterday", HomeFormat.dayLabel(at(2026, Calendar.NOVEMBER, 26, 23), now, ist))
        assertEquals("24 Nov 2026", HomeFormat.dayLabel(at(2026, Calendar.NOVEMBER, 24), now, ist))
    }

    @Test
    fun spendingSkipsCreditsAndTransfersAndSortsByAmount() {
        val nov = HomeFormat.monthRange(at(2026, Calendar.NOVEMBER, 15), 0, ist)
        val rows = listOf(
            tx(10_000, at(2026, Calendar.NOVEMBER, 2), "GROCERIES"),
            tx(5_000, at(2026, Calendar.NOVEMBER, 3), "GROCERIES"),
            tx(90_000, at(2026, Calendar.NOVEMBER, 4), "FUEL"),
            tx(500_000, at(2026, Calendar.NOVEMBER, 5), "TRANSFER"),
            tx(700_000, at(2026, Calendar.NOVEMBER, 6), "SALARY", TransactionType.CREDIT),
            tx(1_000, at(2026, Calendar.OCTOBER, 31), "FUEL"),
            tx(300, at(2026, Calendar.NOVEMBER, 7), null)
        )
        val result = HomeFormat.spendingByCategory(rows, nov.first, nov.second)
        assertEquals(listOf("FUEL", "GROCERIES", "OTHER"), result.map { it.category })
        assertEquals(15_000L, result[1].minor)
    }

    @Test
    fun monthRangeCoversTheWholeMonthAndOffsetsWork() {
        val (start, end) = HomeFormat.monthRange(at(2026, Calendar.NOVEMBER, 27), 0, ist)
        assertEquals(at(2026, Calendar.NOVEMBER, 1, 0), start)
        assertEquals(at(2026, Calendar.DECEMBER, 1, 0), end)
        assertEquals("Oct 2026", HomeFormat.monthTitle(HomeFormat.monthRange(at(2026, Calendar.NOVEMBER, 27), -1, ist).first, ist))
    }

    @Test
    fun payeeIsNullWhenOnlyTheBankIsKnown() {
        assertNull(HomeFormat.payeeOrNull(tx(1, 1, "FOOD", merchant = "  ")))
        assertEquals("TANVEER HOTEL", HomeFormat.payeeOrNull(tx(1, 1, "FOOD", merchant = "TANVEER HOTEL")))
    }
}
