package com.example.financesmstracker.integration

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceSyncRejectionTest {
    private fun err(text: String) = IOException(text)

    @Test
    fun aRefusedPayloadIsDroppedSoItCannotBlockTheQueue() {
        assertTrue(FinanceSyncService.isPermanentRejection(err("Oracle request HTTP 422: {\"detail\":[]}")))
        assertTrue(FinanceSyncService.isPermanentRejection(err("Oracle request HTTP 400: bad")))
    }

    @Test
    fun networkSignInAndServerFaultsAreRetried() {
        assertFalse(FinanceSyncService.isPermanentRejection(err("Oracle request HTTP 401: unauthorized")))
        assertFalse(FinanceSyncService.isPermanentRejection(err("Oracle request HTTP 500: boom")))
        assertFalse(FinanceSyncService.isPermanentRejection(err("Oracle request HTTP 429: slow down")))
        assertFalse(FinanceSyncService.isPermanentRejection(err("Unable to resolve host")))
        assertFalse(FinanceSyncService.isPermanentRejection(err("Oracle request HTTP 4220: x")))
    }
}
