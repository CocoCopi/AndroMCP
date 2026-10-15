package com.cococopi.andromcp.benchmark.corpus

import com.cococopi.andromcp.privacy.DetectionContext
import com.cococopi.andromcp.privacy.PiiCategory

/** A gold PII span; [category] null = out-of-scope label (excluded from scoring per the plan rules). */
data class GoldSpan(
    val start: Int,
    val end: Int,
    val category: PiiCategory?,
)

data class BenchmarkSample(
    val id: String,
    val text: String,
    val context: DetectionContext,
    val gold: List<GoldSpan>,
    val language: String,
)

/** A loaded corpus plus load transparency stats (nothing is dropped silently). */
data class LoadedCorpus(
    val name: String,
    val samples: List<BenchmarkSample>,
    val droppedRows: Int,
    val unknownLabels: Map<String, Int>,
)
