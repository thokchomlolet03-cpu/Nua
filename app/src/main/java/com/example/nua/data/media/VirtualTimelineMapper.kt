package com.example.nua.data.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        
        // Find total original duration. Assume last segment's end or extract from media
        val lastOriginalEnd = composition.segments.lastOrNull()?.endMs ?: 0L
        totalVirtualDurationMs = lastOriginalEnd + cumulativeHold
    }

    /**
     * Map physical video timeline playhead to virtual lecture timeline time.
     */
    fun getVirtualTimeMs(physicalTimeMs: Long): Long {
        var cumulativeHold = 0L
        for (interval in intervals) {
            if (physicalTimeMs < interval.originalStartMs) {
                return physicalTimeMs + cumulativeHold
            }
            if (physicalTimeMs <= interval.originalEndMs) {
                val elapsed = physicalTimeMs - interval.originalStartMs
                return interval.virtualStartMs + elapsed
            }
            cumulativeHold += interval.holdMs
        }
        return physicalTimeMs + cumulativeHold
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

        // 1. Check if virtualTimeMs falls into any vocal chunk interval
        val active = intervals.firstOrNull { virtualTimeMs >= it.virtualStartMs && virtualTimeMs <= it.virtualEndMs }
        if (active != null) {
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
        }

        // 2. Not inside any segment: we are in a silence/gap.
        // Physical = Virtual - cumulativeHold of all completed holds before this point.
        var cumulativeHold = 0L
        for (interval in intervals) {
            if (virtualTimeMs >= interval.virtualEndMs) {
                cumulativeHold += interval.holdMs
            } else {
                break
            }
        }

        val physicalTime = (virtualTimeMs - cumulativeHold).coerceAtLeast(0L)
        return PhysicalState(
            physicalTimeMs = physicalTime,
            shouldFreeze = false,
            activeInterval = null,
            vocalPlayheadMs = 0L
        )
    }

    private fun getWavDurationMs(file: File, fallbackMs: Long): Long {
        if (file.length() <= 44) return fallbackMs
        var fis: java.io.FileInputStream? = null
        return try {
            val buffer = ByteArray(44)
            fis = java.io.FileInputStream(file)
            val read = fis.read(buffer)
            if (read < 44) return fallbackMs

            val channels = ByteBuffer.wrap(buffer, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val sampleRate = ByteBuffer.wrap(buffer, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val bitsPerSample = ByteBuffer.wrap(buffer, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

            val pcmLength = file.length() - 44
            val bytesPerSample = bitsPerSample / 8
            val bytesPerSecond = sampleRate * channels * bytesPerSample

            if (bytesPerSecond <= 0) return fallbackMs
            (pcmLength * 1000L) / bytesPerSecond
        } catch (e: Exception) {
            System.err.println("Failed to read WAV duration for mapper: ${e.message}")
            fallbackMs
        } finally {
            fis?.close()
        }
    }
}
