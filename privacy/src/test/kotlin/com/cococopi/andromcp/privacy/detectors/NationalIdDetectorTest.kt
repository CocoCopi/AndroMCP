package com.cococopi.andromcp.privacy.detectors

import com.cococopi.andromcp.privacy.DetectionContext
import com.cococopi.andromcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NationalIdDetector")
class NationalIdDetectorTest {
    private val detector = NationalIdDetector()

    @Test
    fun `ssn shaped value with ssn context detected`() {
        val context = DetectionContext(fieldName = "ssn")
        val result = detector.detect("078-05-1120", context)

        assertEquals(1, result.size)
        assertEquals(PiiCategory.NATIONAL_IDS, result[0].category)
    }

    @Test
    fun `same value without context yields empty`() {
        val result = detector.detect("078-05-1120", DetectionContext.EMPTY)

        assertTrue(result.isEmpty())
    }
}
