package com.cococopi.andromcp.privacy

import com.cococopi.andromcp.services.accessibility.BoundsData
import com.cococopi.andromcp.services.accessibility.MultiWindowResult

/** A single text plus its detection context for the pipeline. */
data class TextItem(
    val text: String,
    val context: DetectionContext,
)

/** A redacted accessibility tree plus the bounds of every node that had at least one detection. */
data class ProcessedTree(
    val result: MultiWindowResult,
    val flaggedBounds: List<BoundsData>,
)
