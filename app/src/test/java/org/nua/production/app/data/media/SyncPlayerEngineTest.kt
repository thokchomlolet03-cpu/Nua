package org.nua.production.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SyncPlayerEngineTest {

    private lateinit var playerEngine: SyncPlayerEngine

    @Before
    fun setUp() {
        playerEngine = SyncPlayerEngine()
    }

    /**
     * Test Scenario 1: Audio Is Longer Than Video (The Original Defect Path)
     * ARRANGE: A 2-second video tracking a 4-second audio segment.
     * ACT: Execute the playback speed calculator.
     * ASSERT: Ensure the float precision evaluates to exactly 0.5f instead of freezing at 0.0f.
     */
    @Test
    fun verifyFloatingPointDivisionWhenAudioIsLonger() {
        // Arrange
        val nativeVideoDuration = 2000L  
        val targetAudioDuration = 4000L  

        // Act
        val actualSpeed = playerEngine.calculateVideoPlaybackSpeed(nativeVideoDuration, targetAudioDuration)

        // Assert
        assertEquals(
            "Floating-point verification failed: Speed evaluated to 0.0f instead of fractional stretch coefficient.",
            0.5f, 
            actualSpeed, 
            0.001f
        )
    }

    /**
     * Test Scenario 2: Upper and Lower Clamping Boundaries
     * Verifies that extreme duration variations do not pass unstable values to the video player.
     */
    @Test
    fun verifyPlaybackSpeedCoercionLimits() {
        // Upper bound: Video is 10s, Audio is a microsecond spike (Should clamp to 5.0f)
        val fastVideoSpeed = playerEngine.calculateVideoPlaybackSpeed(10000L, 100L)
        assertEquals("Upper ceiling boundary clamp breached. Expected maximum safety limit of 5.0f.", 5.0f, fastVideoSpeed, 0.0f)

        // Lower bound: Video is 100ms, Audio is 10s (Should clamp to 0.2f)
        val slowVideoSpeed = playerEngine.calculateVideoPlaybackSpeed(100L, 10000L)
        assertEquals("Lower floor boundary clamp breached. Expected minimum safety limit of 0.2f.", 0.2f, slowVideoSpeed, 0.0f)
    }

    /**
     * Test Scenario 3: Division-By-Zero Crash Safeguards
     * Confirms that passing invalid, empty, or negative parameters falls back to a safe 1.0f speed.
     */
    @Test
    fun verifyDivisionByZeroSafeguards() {
        val nativeVideoDuration = 3000L
        
        // Denominator is exactly zero
        val zeroResult = playerEngine.calculateVideoPlaybackSpeed(nativeVideoDuration, 0L)
        assertEquals("Zero-duration denominator failed to resolve to baseline default.", 1.0f, zeroResult, 0.0f)

        // Denominator is corrupted negative data
        val negativeResult = playerEngine.calculateVideoPlaybackSpeed(nativeVideoDuration, -500L)
        assertEquals("Negative-duration denominator failed to resolve to baseline default.", 1.0f, negativeResult, 0.0f)
    }
}
