package org.nua.production.app.data.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.media.audiofx.Equalizer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Dual-player synchronization engine with pitch-invariant time warping.
 *
 * Manages two ExoPlayer instances:
 * - **videoPlayer**: Plays the original lecture video
 * - **audioPlayer**: Plays dubbed Hindi vocal chunks
 *
 * Synchronization strategy (TRIZ-inspired):
 * 1. **Clock Skewing Zone (drift 1–800ms)**: Adjusts video playback speed via
 *    ExoPlayer's native Sonic algorithm. Pitch remains invariant because Sonic
 *    uses sinusoidal overlap-add (SOLA) time stretching, not naive resampling.
 *    Video slows to `originalDur / vocalDur` (minimum 0.80x) while audio plays at 1.0x.
 *
 * 2. **Hard Freeze Zone (drift > 800ms)**: Video pauses on last frame. Audio continues.
 *    When audio segment completes, video resumes at 1.0x.
 *
 * 3. **No Drift Zone**: Both players run at 1.0x normal speed.
 *
 * This engine must be created and used on the Main thread (ExoPlayer requirement).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SyncPlayerEngine(context: Context) {

    companion object {
        private const val TAG = "SyncPlayerEngine"
        /** Maximum video slowdown before switching to hard freeze */
        private const val MIN_VIDEO_SPEED = 0.80f
        /** Drift threshold above which we freeze instead of slowing */
        private const val FREEZE_THRESHOLD_MS = 800L
        /** Sync loop tick interval */
        private const val SYNC_TICK_MS = 30L
        /** Drift threshold for seek correction */
        private const val SEEK_CORRECTION_THRESHOLD_MS = 300L
    }

    val videoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        volume = 0.05f  // Soft ducking to preserve room tone
        repeatMode = Player.REPEAT_MODE_OFF
    }

    val audioPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    setupVocalEqualizer(audioSessionId)
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    isSeeking = false
                }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    isSeeking = false
                }
            }
        })
    }

    @Volatile private var isSeeking = false
    private var vocalEqualizer: Equalizer? = null


    private var isFreezing = false
    private var currentInterval: TimelineInterval? = null
    private var mapper: VirtualTimelineMapper? = null
    private var sessionDir: File? = null
    private var activeVocalPath: String? = null
    private val vocalWindowIndices = mutableMapOf<String, Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var adaptivePacingSlowdown = false

    // Callback for UI state updates
    var onStateUpdate: ((SyncState) -> Unit)? = null

    data class SyncState(
        val virtualTimeMs: Long,
        val physicalTimeMs: Long,
        val isFreezing: Boolean,
        val currentSubtitle: String?,
        val currentOriginalText: String?,
        val activeInterval: TimelineInterval?
    )

    // ─── Setup ──────────────────────────────────────────────────────────

    fun setVideoSource(mediaItem: MediaItem) {
        videoPlayer.setMediaItem(mediaItem)
        videoPlayer.prepare()
    }

    fun setAdaptivePacing(slowDown: Boolean) {
        adaptivePacingSlowdown = slowDown
        if (!isFreezing) {
            val baseSpeed = if (slowDown) 0.85f else 1.0f
            if (kotlin.math.abs(videoPlayer.playbackParameters.speed - baseSpeed) > 0.01f) {
                videoPlayer.playbackParameters = PlaybackParameters(baseSpeed)
            }
            if (kotlin.math.abs(audioPlayer.playbackParameters.speed - baseSpeed) > 0.01f) {
                audioPlayer.playbackParameters = PlaybackParameters(baseSpeed)
            }
        }
    }

    private fun setupVocalEqualizer(audioSessionId: Int) {
        try {
            vocalEqualizer?.release()
            vocalEqualizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }
            vocalEqualizer?.let { eq ->
                val numBands = eq.numberOfBands
                for (i in 0 until numBands) {
                    val freq = eq.getCenterFreq(i.toShort())
                    when {
                        freq <= 80000 -> {
                            // High-pass filter: cut <80Hz by -15dB
                            eq.setBandLevel(i.toShort(), -1500)
                        }
                        freq in 2000000..4000000 -> {
                            // Presence boost: +4dB between 2kHz-4kHz
                            eq.setBandLevel(i.toShort(), 400)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup Vocal Equalizer: ${e.message}")
        }
    }

    fun setMapper(timelineMapper: VirtualTimelineMapper) {
        this.mapper = timelineMapper
        buildAudioPlaylist()
    }

    fun setSessionDir(dir: File) {
        this.sessionDir = dir
        buildAudioPlaylist()
    }

    private fun buildAudioPlaylist() {
        val dir = sessionDir ?: return
        val timelineMapper = mapper ?: return
        
        val items = mutableListOf<MediaItem>()
        var windowIndex = 0
        vocalWindowIndices.clear()

        for (interval in timelineMapper.intervals) {
            if (interval.vocalAssetLocalPath.isNotEmpty()) {
                val file = File(dir, interval.vocalAssetLocalPath)
                if (file.exists()) {
                    items.add(MediaItem.fromUri(file.absolutePath))
                    vocalWindowIndices[interval.vocalAssetLocalPath] = windowIndex
                    windowIndex++
                }
            }
        }
        
        if (items.isNotEmpty()) {
            audioPlayer.setMediaItems(items)
            audioPlayer.prepare()
        }
    }

    // ─── Sync evaluation ────────────────────────────────────────────────

    /**
     * Evaluates sync alignment for a given segment and applies the appropriate
     * synchronization strategy: clock skew, hard freeze, or normal playback.
     */
    fun evaluateSyncAlignment(
        videoTimeMs: Long,
        targetAudioDurationMs: Long,
        nativeSegmentDurationMs: Long
    ) {
        if (isFreezing) return

        val durationDrift = targetAudioDurationMs - nativeSegmentDurationMs

        when {
            durationDrift in 1..FREEZE_THRESHOLD_MS -> {
                // Video slows to `originalDur / vocalDur` (minimum 0.80x) while audio plays at 1.0x.
                val videoScalingRatio = nativeSegmentDurationMs.toFloat() / targetAudioDurationMs.toFloat().coerceAtLeast(1f)
                val clampedVideoSpeed = videoScalingRatio.coerceAtLeast(MIN_VIDEO_SPEED)
                
                if (kotlin.math.abs(videoPlayer.playbackParameters.speed - clampedVideoSpeed) > 0.01f) {
                    videoPlayer.playbackParameters = PlaybackParameters(clampedVideoSpeed)
                }
                if (kotlin.math.abs(audioPlayer.playbackParameters.speed - 1.0f) > 0.01f) {
                    audioPlayer.playbackParameters = PlaybackParameters(1.0f)
                }
                Log.d(TAG, "Clock skew: video at ${clampedVideoSpeed}x, audio at 1.0x (drift: ${durationDrift}ms)")
            }
            durationDrift > FREEZE_THRESHOLD_MS -> {
                // Hard Freeze: pause video, let audio play through
                isFreezing = true
                videoPlayer.pause()
                audioPlayer.play()
                Log.d(TAG, "Hard freeze: drift ${durationDrift}ms exceeds threshold")
            }
            else -> {
                // No drift: normal speed or adaptive pacing
                val baseSpeed = if (adaptivePacingSlowdown) 0.85f else 1.0f
                if (kotlin.math.abs(videoPlayer.playbackParameters.speed - baseSpeed) > 0.01f) {
                    videoPlayer.playbackParameters = PlaybackParameters(baseSpeed)
                }
                if (kotlin.math.abs(audioPlayer.playbackParameters.speed - baseSpeed) > 0.01f) {
                    audioPlayer.playbackParameters = PlaybackParameters(baseSpeed)
                }
            }
        }
    }

    /**
     * Called when an audio segment finishes playing.
     * Unfreezes the video and resets speed to 1.0x.
     */
    fun handleAudioSegmentComplete() {
        if (isFreezing) {
            isFreezing = false
            if (kotlin.math.abs(videoPlayer.playbackParameters.speed - 1.0f) > 0.01f) {
                videoPlayer.playbackParameters = PlaybackParameters(1.0f)
            }
            videoPlayer.play()
            Log.d(TAG, "Unfreeze: video resumed at 1.0x")
        } else {
            // Reset any clock-skewing
            val baseSpeed = if (adaptivePacingSlowdown) 0.85f else 1.0f
            if (kotlin.math.abs(videoPlayer.playbackParameters.speed - baseSpeed) > 0.01f) {
                videoPlayer.playbackParameters = PlaybackParameters(baseSpeed)
            }
        }
        currentInterval = null
    }

    // ─── Main sync loop ─────────────────────────────────────────────────

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            performSyncTick()
            handler.postDelayed(this, SYNC_TICK_MS)
        }
    }

    private fun performSyncTick() {
        val timelineMapper = mapper ?: return

        var virtualTimeMs: Long
        if (isFreezing) {
            // During freeze: audio player drives the virtual clock
            val interval = currentInterval ?: return
            val expectedIndex = vocalWindowIndices[interval.vocalAssetLocalPath] ?: -1
            val isCurrentChunkFinished = (audioPlayer.currentMediaItemIndex > expectedIndex) || (audioPlayer.playbackState == Player.STATE_ENDED)
            
            if (isCurrentChunkFinished) {
                // Unfreeze bug fix: exit hard-freeze when audio ends by stepping virtual timeline
                virtualTimeMs = interval.virtualEndMs + 1
                handleAudioSegmentComplete()
            } else {
                val audioPos = audioPlayer.currentPosition
                virtualTimeMs = interval.virtualStartMs + audioPos
            }
        } else {
            // Normal: video player drives the virtual clock
            val physicalTime = videoPlayer.currentPosition
            virtualTimeMs = timelineMapper.getVirtualTimeMs(physicalTime)
        }

        val state = timelineMapper.getPhysicalState(virtualTimeMs)

        // Apply sync state transitions
        if (state.shouldFreeze && !isFreezing) {
            // Enter freeze
            isFreezing = true
            currentInterval = state.activeInterval
            videoPlayer.pause()
        } else if (!state.shouldFreeze && isFreezing) {
            // Exit freeze
            isFreezing = false
            currentInterval = null
            if (kotlin.math.abs(videoPlayer.playbackParameters.speed - 1.0f) > 0.01f) {
                videoPlayer.playbackParameters = PlaybackParameters(1.0f)
            }
            videoPlayer.play()
        }

        // Apply clock skewing for in-segment playback
        if (!isFreezing && state.activeInterval != null) {
            val interval = state.activeInterval
            val nativeDur = interval.originalEndMs - interval.originalStartMs
            val vocalDur = interval.vocalDurationMs
            evaluateSyncAlignment(state.physicalTimeMs, vocalDur, nativeDur)
        }

        // Manage audio volume: 0.05f (soft ducking) during dubbed chunks, 1.0f during silence/gaps
        if (state.activeInterval != null) {
            videoPlayer.volume = 0.05f
        } else {
            videoPlayer.volume = 1.0f
        }

        // Load, prepare, play, pause, seek vocal chunks
        syncVocalAsset(state.activeInterval, state.vocalPlayheadMs, virtualTimeMs)

        // Notify UI
        onStateUpdate?.invoke(
            SyncState(
                virtualTimeMs = virtualTimeMs,
                physicalTimeMs = state.physicalTimeMs,
                isFreezing = isFreezing,
                currentSubtitle = state.activeInterval?.translatedText,
                currentOriginalText = state.activeInterval?.originalText,
                activeInterval = state.activeInterval
            )
        )
    }

    private fun syncVocalAsset(interval: TimelineInterval?, playheadMs: Long, virtualTimeMs: Long) {
        val dir = sessionDir ?: return

        if (interval == null) {
            if (audioPlayer.isPlaying) audioPlayer.pause()
            activeVocalPath = null
            return
        }

        val targetFile = File(dir, interval.vocalAssetLocalPath)
        val path = targetFile.absolutePath

        // Temporal frustum check: only load/prepare if startMs is within ±2 min (120_000ms) of playhead
        val dist = kotlin.math.abs(interval.virtualStartMs - virtualTimeMs)
        if (dist > 120_000L) {
            if (audioPlayer.isPlaying) audioPlayer.pause()
            activeVocalPath = null
            return
        }

        if (activeVocalPath != path) {
            val windowIndex = vocalWindowIndices[interval.vocalAssetLocalPath]
            if (windowIndex != null) {
                audioPlayer.seekTo(windowIndex, playheadMs)
                if (isPlaying) audioPlayer.play()
                activeVocalPath = path
            } else {
                audioPlayer.stop()
                activeVocalPath = null
            }
        } else {
            // Check drift between vocal playhead and timeline mapping
            val drift = kotlin.math.abs(audioPlayer.currentPosition - playheadMs)
            if (drift > SEEK_CORRECTION_THRESHOLD_MS) {
                if (!isSeeking) {
                    isSeeking = true
                    audioPlayer.seekTo(playheadMs)
                }
            }
            if (isPlaying && !audioPlayer.isPlaying && audioPlayer.playbackState != Player.STATE_ENDED) {
                audioPlayer.play()
            } else if (!isPlaying && audioPlayer.isPlaying) {
                audioPlayer.pause()
            }
        }
    }

    // ─── Playback control ───────────────────────────────────────────────

    fun play() {
        isPlaying = true
        videoPlayer.play()
        if (activeVocalPath != null && audioPlayer.playbackState != Player.STATE_ENDED) {
            audioPlayer.play()
        }
        handler.removeCallbacks(syncRunnable)
        handler.post(syncRunnable)
    }

    fun pause() {
        isPlaying = false
        videoPlayer.pause()
        audioPlayer.pause()
        handler.removeCallbacks(syncRunnable)
    }

    fun seekTo(virtualTimeMs: Long) {
        val timelineMapper = mapper ?: return
        val state = timelineMapper.getPhysicalState(virtualTimeMs)
        videoPlayer.seekTo(state.physicalTimeMs)
        syncVocalAsset(state.activeInterval, state.vocalPlayheadMs, virtualTimeMs)
    }

    fun release() {
        isPlaying = false
        handler.removeCallbacks(syncRunnable)
        videoPlayer.release()
        audioPlayer.release()
        vocalEqualizer?.release()
    }
}
