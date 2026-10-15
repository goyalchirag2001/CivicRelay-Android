package org.civicrelay.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsContactPolicyTest {
    @Test fun validNumbersAreNormalized() {
        assertEquals("+919876543210", SmsContactPolicy.normalize(" +91 98765-43210 "))
        assertEquals("9876543210", SmsContactPolicy.normalize("(987) 654-3210"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectShortNumber() { SmsContactPolicy.normalize("112") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectLetters() { SmsContactPolicy.normalize("+91HELLO12345") }
}
