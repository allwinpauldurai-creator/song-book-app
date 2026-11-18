package com.imsbooks.songbook

import com.imsbooks.songbook.util.Mobile
import com.imsbooks.songbook.util.passwordProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as the web app's utils/mobile.test.ts and the backend's PhoneNumbersTest. */
class MobileTest {

    @Test
    fun normalisesIndianNumbersTypedLoosely() {
        listOf("9876543210", "98765 43210", "+91 98765-43210", "09876543210", "919876543210", "0091 9876543210")
            .forEach { assertEquals(it, "+919876543210", Mobile.normalize(it)) }
    }

    @Test
    fun keepsOtherCountriesWithPlus() {
        assertEquals("+447911123456", Mobile.normalize("+44 7911 123456"))
    }

    @Test
    fun rejectsInvalidNumbers() {
        listOf("", "12345", "5876543210", "+91 5876543210", "abc", "+0123456789").forEach {
            assertNull(it, Mobile.normalize(it))
        }
    }

    @Test
    fun formatsIndianNumbers() {
        assertEquals("+91 98765 43210", Mobile.format("+919876543210"))
        assertEquals("+447911123456", Mobile.format("+447911123456"))
        assertEquals("", Mobile.format(null))
    }

    @Test
    fun passwordPolicy() {
        assertNotNull(passwordProblem("short", null))
        assertNotNull(passwordProblem("+919876543210", "+919876543210"))
        assertNull(passwordProblem("a-long-enough-password", "+919876543210"))
    }
}
