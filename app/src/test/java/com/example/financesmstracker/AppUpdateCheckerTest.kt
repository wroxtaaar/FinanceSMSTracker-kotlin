package com.example.financesmstracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun onlyAHigherBuildCountsAsAnUpdate() {
        assertTrue(isNewerBuild(installed = 12, latest = 13))
        assertFalse(isNewerBuild(installed = 13, latest = 13))
        assertFalse(isNewerBuild(installed = 14, latest = 13))
    }
}
