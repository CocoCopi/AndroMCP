package com.cococopi.andromcp.privacy.detectors

import com.cococopi.andromcp.privacy.DetectionContext
import com.cococopi.andromcp.privacy.PiiDetection

/** A single deterministic (rule/checksum-based) PII detector over a piece of [text]. */
interface DeterministicDetector {
    fun detect(
        text: String,
        context: DetectionContext,
    ): List<PiiDetection>
}
