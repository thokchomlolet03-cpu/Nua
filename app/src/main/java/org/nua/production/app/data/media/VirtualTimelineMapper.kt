package org.nua.production.app.data.media

import java.io.File

data class TimelineInterval(
    val originalStartMs: Long,
    val originalEndMs: Long,
    val vocalDurationMs: Long,
    val holdMs: Long,
    val cumulativeHoldBeforeMs: Long,
    val virtualStartMs: Long,
    val virtualEndMs: Long,
    val vocalAssetLocalPath: String,
    val originalText: String,
    val translatedText: String,
    val hotspots: List<HotspotInfo> = emptyList()
)

class VirtualTimelineMapper(composition: MediaComposition, sessionDir: File) {
    
    companion object {
        private const val TAG = "VirtualTimelineMapper"
    }

    val intervals: List<TimelineInterval>
    val totalVirtualDurationMs: Long
    private val physicalStartPositions: LongArray
    private val virtualStartPositions: LongArray
    private val virtualEndPositions: LongArray
    private val cumulativeHoldsAtEnd: LongArray

    init {
        val list = ArrayList<TimelineInterval>()
        var cumulativeHold = 0L
        
        for (seg in composition.segments) {
            val originalStart = seg.startMs
            val originalEnd = seg.endMs
            val originalDur = originalEnd - originalStart

            // Use the pre-calculated audio duration from segment metadata if available
            val file = File(sessionDir, seg.vocalAssetLocalPath)
            val vocalDur = if (seg.audioDurationMs != null && seg.audioDurationMs > 0) {
                seg.audioDurationMs
            } else if (file.exists()) {
                getWavDurationMs(file, originalDur)
            } else {
                originalDur
            }

            val hold = (vocalDur - originalDur).coerceAtLeast(0L)
            val vStart = originalStart + cumulativeHold
            val vEnd = vStart + maxOf(originalDur, vocalDur)

            list.add(
                TimelineInterval(
                    originalStartMs = originalStart,
                    originalEndMs = originalEnd,
                    vocalDurationMs = vocalDur,
                    holdMs = hold,
                    cumulativeHoldBeforeMs = cumulativeHold,
                    virtualStartMs = vStart,
                    virtualEndMs = vEnd,
                    vocalAssetLocalPath = seg.vocalAssetLocalPath,
                    originalText = seg.originalText,
                    translatedText = seg.translatedText,
                    hotspots = seg.hotspots
                )
            )

            cumulativeHold += hold
        }
        intervals = list
        physicalStartPositions = list.map { it.originalStartMs }.toLongArray()
        virtualStartPositions = list.map { it.virtualStartMs }.toLongArray()
        virtualEndPositions = list.map { it.virtualEndMs }.toLongArray()
        cumulativeHoldsAtEnd = list.map { it.cumulativeHoldBeforeMs + it.holdMs }.toLongArray()

        // Find total original duration. Assume last segment's end or extract from media
        val lastOriginalEnd = composition.segments.lastOrNull()?.endMs ?: 0L
        totalVirtualDurationMs = lastOriginalEnd + cumulativeHold
    }

    /**
     * Map physical video timeline playhead to virtual lecture timeline time.
     */
    fun getVirtualTimeMs(physicalTimeMs: Long): Long {
        if (intervals.isEmpty()) return physicalTimeMs

        // O(log n) binary search over pre-sorted start positions
        var lo = 0
        var hi = physicalStartPositions.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (physicalStartPositions[mid] <= physicalTimeMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        if (idx < 0) {
            // Before all intervals
            return physicalTimeMs
        }

        val interval = intervals[idx]
        return if (physicalTimeMs <= interval.originalEndMs) {
            val elapsed = physicalTimeMs - interval.originalStartMs
            interval.virtualStartMs + elapsed
        } else {
            // After this interval — add cumulative hold through this interval
            physicalTimeMs + interval.cumulativeHoldBeforeMs + interval.holdMs
        }
    }

    data class PhysicalState(
        val physicalTimeMs: Long,
        val shouldFreeze: Boolean,
        val activeInterval: TimelineInterval?,
        val vocalPlayheadMs: Long
    )

    /**
     * Map virtual timeline playhead to physical video playhead state.
     */
    fun getPhysicalState(virtualTimeMs: Long): PhysicalState {
        if (intervals.isEmpty()) {
            return PhysicalState(
                physicalTimeMs = virtualTimeMs,
                shouldFreeze = false,
                activeInterval = null,
                vocalPlayheadMs = 0L
            )
        }

        var lo = 0
        var hi = virtualStartPositions.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (virtualStartPositions[mid] <= virtualTimeMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        if (idx >= 0) {
            val active = intervals[idx]
            if (virtualTimeMs <= active.virtualEndMs) {
                val elapsedInInterval = virtualTimeMs - active.virtualStartMs
                val originalDur = active.originalEndMs - active.originalStartMs

                return if (elapsedInInterval >= originalDur) {
                    // If dubbed audio is longer than original segment and has run past originalEndMs: Freeze video!
                    PhysicalState(
                        physicalTimeMs = active.originalEndMs,
                        shouldFreeze = true,
                        activeInterval = active,
                        vocalPlayheadMs = elapsedInInterval
                    )
                } else {
                    // Video plays normally in sync with the beginning of the vocal chunk
                    PhysicalState(
                        physicalTimeMs = active.originalStartMs + elapsedInInterval,
                        shouldFreeze = false,
                        activeInterval = active,
                        vocalPlayheadMs = elapsedInInterval
                    )
                }
            } else {
                // Not inside any segment: we are in a silence/gap after the interval 'idx'
                val cumulativeHold = cumulativeHoldsAtEnd[idx]
                val physicalTime = (virtualTimeMs - cumulativeHold).coerceAtLeast(0L)
                return PhysicalState(
                    physicalTimeMs = physicalTime,
                    shouldFreeze = false,
                    activeInterval = null,
                    vocalPlayheadMs = 0L
                )
            }
        }

        // virtualTimeMs is before the first interval
        val physicalTime = virtualTimeMs.coerceAtLeast(0L)
        return PhysicalState(
            physicalTimeMs = physicalTime,
            shouldFreeze = false,
            activeInterval = null,
            vocalPlayheadMs = 0L
        )
    }

    private fun getWavDurationMs(file: File, fallbackMs: Long): Long {
        return WavUtils.getWavDurationMs(file, fallbackMs)
    }
}
