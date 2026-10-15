package com.cococopi.andromcp.privacy.detectors

import com.cococopi.andromcp.privacy.DetectionContext
import com.cococopi.andromcp.privacy.PiiCategory
import com.cococopi.andromcp.privacy.PiiDetection
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import javax.inject.Inject

/**
 * Phone-number detector backed by libphonenumber. Uses [PhoneNumberUtil.Leniency.VALID] against the
 * device's default region so both local-format and international numbers are validated.
 */
class PhoneDetector
    @Inject
    constructor() : DeterministicDetector {
        override fun detect(
            text: String,
            context: DetectionContext,
        ): List<PiiDetection> {
            val defaultRegion = Locale.getDefault().country.ifEmpty { DEFAULT_REGION }
            return PhoneNumberUtil
                .getInstance()
                .findNumbers(text, defaultRegion, PhoneNumberUtil.Leniency.VALID, Long.MAX_VALUE)
                .map { match ->
                    PiiDetection(
                        PiiCategory.PHONE_NUMBERS,
                        match.start(),
                        match.end(),
                        PiiDetection.Source.DETERMINISTIC,
                    )
                }
        }

        companion object {
            private const val DEFAULT_REGION = "US"
        }
    }
