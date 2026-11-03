package com.auto.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 1 (Section 13): Streaming waveform peak accumulator and bounded audio windowing.
 * Generates 20 peaks/sec in a single streaming pass without holding the entire decoded FloatArray in memory,
 * and preserves AudioPauses rules exactly.
 */
object AudioStream {
    const val DEFAULT_PEAKS_PER_SEC = 20
    /** Bounded cap for full-array pause analysis (60 min at 16 kHz mono = 57.6M floats). */
    const val MAX_PAUSE_SAMPLES = 16_000 * 60 * 60

    /**
     * Streams arbitrary PCM float chunks at `sampleRate` (default 16 kHz) and accumulates
     * one peak bucket per `1 / peaksPerSec` seconds (800 samples at 16 kHz for 20 peaks/sec).
     */
    class PeakStreamer(
        val sampleRate: Int = AudioPauses.SR,
        val peaksPerSec: Int = DEFAULT_PEAKS_PER_SEC
    ) {
        private val samplesPerBucket = max(1, sampleRate / max(1, peaksPerSec))
        private var buckets = FloatArray(4096)
        private var bucketCount = 0
        private var currentMax = 0f
        private var inBucket = 0
        var totalSamples: Long = 0L
            private set

        fun accept(sample: Float) {
            val v = abs(sample)
            if (v > currentMax) currentMax = v
            inBucket++
            totalSamples++
            if (inBucket >= samplesPerBucket) {
                pushBucket(currentMax)
                currentMax = 0f
                inBucket = 0
            }
        }

        fun acceptChunk(chunk: FloatArray, offset: Int = 0, length: Int = chunk.size - offset) {
            val end = min(chunk.size, offset + length)
            for (i in offset until end) accept(chunk[i])
        }

        private fun pushBucket(value: Float) {
            if (bucketCount == buckets.size) {
                buckets = buckets.copyOf(buckets.size * 2)
            }
            buckets[bucketCount++] = value
        }

        /** Finalizes and normalizes peaks to 0..1 (matching Waveform.peaks scaling). */
        fun finish(expectedBuckets: Int? = null): FloatArray {
            if (inBucket > 0) {
                pushBucket(currentMax)
                currentMax = 0f
                inBucket = 0
            }
            val raw = buckets.copyOf(bucketCount)
            val top = raw.maxOrNull() ?: 0f
            if (top > 0f) {
                for (i in raw.indices) raw[i] /= top
            }
            if (expectedBuckets != null && expectedBuckets > 0 && raw.size != expectedBuckets) {
                return Waveform.resample(raw, expectedBuckets)
            }
            return raw
        }
    }

    /**
     * Bounded sample collector for 16 kHz mono samples that avoids unbounded doubling copies
     * by using fixed-size 64K-sample pages and assembling a single exact-sized array on finish.
     */
    class PagedSampleBuffer(private val maxSamples: Int = MAX_PAUSE_SAMPLES) {
        private val pageSize = 65_536
        private val pages = ArrayList<FloatArray>()
        private var currentPage = FloatArray(pageSize)
        private var pageOffset = 0
        var size: Int = 0
            private set

        fun add(v: Float) {
            if (size >= maxSamples) return
            currentPage[pageOffset++] = v
            size++
            if (pageOffset == pageSize) {
                pages.add(currentPage)
                currentPage = FloatArray(pageSize)
                pageOffset = 0
            }
        }

        fun toFloatArray(): FloatArray {
            val out = FloatArray(size)
            var pos = 0
            for (p in pages) {
                System.arraycopy(p, 0, out, pos, pageSize)
                pos += pageSize
            }
            if (pageOffset > 0) {
                System.arraycopy(currentPage, 0, out, pos, pageOffset)
            }
            return out
        }

        fun clear() {
            pages.clear()
            pageOffset = 0
            size = 0
        }
    }
}
