package com.example.financesmstracker.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsHandlingLogTest {
    @Test
    fun keepsOnlyNewestEntries() {
        var list = emptyList<SmsHandlingLog.Entry>()
        repeat(SmsHandlingLog.MAX_ENTRIES + 5) {
            list = SmsHandlingLog.appendCapped(list, SmsHandlingLog.Entry(it.toLong(), "S", "b", "o$it"))
        }
        assertEquals(SmsHandlingLog.MAX_ENTRIES, list.size)
        assertEquals("o5", list.first().outcome)
    }

    @Test
    fun snippetCollapsesWhitespaceAndTruncates() {
        val s = SmsHandlingLog.snippetOf("Payment of INR\n33136.06   " + "x".repeat(200))
        assertTrue(s.startsWith("Payment of INR 33136.06 x"))
        assertEquals(90, s.length)
    }

    @Test
    fun gmailScanFormatShowsSearchSummaryAndEachMessage() {
        val text = SmsHandlingLog.formatGmailScan(
            "folder=INBOX, candidates=2",
            listOf("hsbc@hsbc.bank.in | HSBC alert\n→ Read as DEBIT 4.00 USD", "x | y\n→ Skipped")
        )
        assertTrue(text.startsWith("Search: folder=INBOX, candidates=2"))
        assertTrue(text.contains("HSBC alert"))
        assertTrue(text.contains("→ Skipped"))
        assertEquals(
            "The scan returned no e-mails to look at.",
            SmsHandlingLog.formatGmailScan("", emptyList())
        )
    }

    @Test
    fun entriesAreSortedIntoAcceptedReviewAndOtherSections() {
        fun e(sender: String, outcome: String) = SmsHandlingLog.Entry(1L, sender, "b", outcome)
        val saved = e("HDFCBK", "Saved as DEBIT BANK HDFC 5304 (row 1, X)")
        val note = e("Gmail notification: Alert", "Saved as DEBIT CREDIT_CARD AXIS 1175 INR 1.00 (row 2, Y)")
        val review = e("AXISBK", "Sent to Review: low confidence")
        val failed = e("AXISBK", "ERROR: IOException: boom")
        val noise = e("PROMO", "Ignored: not a financial message")
        val dup = e("HDFCBK", "Skipped: duplicate (already stored)")
        val all = listOf(saved, note, review, failed, noise, dup)
        assertEquals(listOf(saved, note), SmsHandlingLog.inSection(all, SmsHandlingLog.Section.ACCEPTED))
        assertEquals(listOf(review, failed), SmsHandlingLog.inSection(all, SmsHandlingLog.Section.REVIEW))
        assertEquals(listOf(noise, dup), SmsHandlingLog.inSection(all, SmsHandlingLog.Section.OTHER))
        assertTrue(SmsHandlingLog.formatSection(all, SmsHandlingLog.Section.ACCEPTED).contains("[Notification] Alert"))
        assertTrue(SmsHandlingLog.formatSection(all, SmsHandlingLog.Section.ACCEPTED).contains("[SMS] HDFCBK"))
        assertEquals("Nothing here.", SmsHandlingLog.formatSection(emptyList(), SmsHandlingLog.Section.REVIEW))
    }

    @Test
    fun smsAndNotificationsKeepSeparateHistoriesAndFilter() {
        var list = emptyList<SmsHandlingLog.Entry>()
        list = SmsHandlingLog.appendCapped(list, SmsHandlingLog.Entry(0, "Gmail notification: A", "b", "Saved as X"))
        repeat(SmsHandlingLog.MAX_ENTRIES + 10) {
            list = SmsHandlingLog.appendCapped(list, SmsHandlingLog.Entry(it + 1L, "SMS", "b", "Ignored"))
        }
        assertEquals(1, SmsHandlingLog.fromSource(list, SmsHandlingLog.Source.NOTIFICATION).size)
        assertEquals(SmsHandlingLog.MAX_ENTRIES, SmsHandlingLog.fromSource(list, SmsHandlingLog.Source.SMS).size)
        assertEquals(SmsHandlingLog.MAX_ENTRIES + 1, SmsHandlingLog.fromSource(list, SmsHandlingLog.Source.ALL).size)
    }
}
