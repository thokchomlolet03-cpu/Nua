package org.nua.production.app.media.tts

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DubbingTtsEngineTest {

    private lateinit var ttsEngine: DubbingTtsEngine

    @Before
    fun setUp() {
        ttsEngine = DubbingTtsEngine()
    }

    /**
     * Test Scenario 1: The NaN Crash Prevention Path (The Core Fix Validation)
     * ARRANGE: Both the estimated speaking duration and target video window are exactly 0.0f.
     * ACT: Execute the TTS speed rate calculator.
     * ASSERT: Ensure the calculation does not throw an exception and gracefully defaults to 1.0f.
     */
    @Test
    fun verifyNaNCalculationSafeguardReturnsBaseline() {
        // Arrange
        val estimatedDuration = 0.0f
        val targetDuration = 0.0f

        try {
            // Act
            val actualRate = ttsEngine.calculateTtsPlaybackRate(estimatedDuration, targetDuration)
            
            // Assert
            assertEquals("NaN math anomaly failed to fallback to 1.0f baseline speed factor.", 1.0f, actualRate, 0.0f)
        } catch (e: IllegalArgumentException) {
            org.junit.Assert.fail("Failsafe Failure: The engine threw an IllegalArgumentException when encountering NaN.")
        }
    }

    /**
     * Test Scenario 2: Standard Division-By-Zero (Non-Zero Numerator)
     * Verifies that when text exists but the timeline window is zero, the engine handles it without crashing.
     */
    @Test
    fun verifyDivisionByZeroTargetDurationReturnsBaseline() {
        // Arrange
        val estimatedDuration = 4.5f
        val targetDuration = 0.0f

        // Act
        val actualRate = ttsEngine.calculateTtsPlaybackRate(estimatedDuration, targetDuration)

        // Assert
        assertEquals("Division by zero failed to resolve gracefully to 1.0f baseline speed factor.", 1.0f, actualRate, 0.0f)
    }

    /**
     * Test Scenario 3: Happy Path and Bounds Coercion Limits
     * Verifies that valid ratios are calculated precisely and extreme variants are clamped securely.
     */
    @Test
    fun verifyPlaybackSpeedCoercionLimits() {
        // Scenario A: Precise conversion within normal limits (1.5x)
        val normalRate = ttsEngine.calculateTtsPlaybackRate(3.0f, 2.0f)
        assertEquals("Valid rate calculation failed to map correctly.", 1.5f, normalRate, 0.001f)

        // Scenario B: Underflow variation (Ratio is 0.5x, should clamp up to 1.0f)
        val slowRate = ttsEngine.calculateTtsPlaybackRate(1.0f, 2.0f)
        assertEquals("Lower bounds floor clamp failed to restrict speed down-drift.", 1.0f, slowRate, 0.0f)

        // Scenario C: Overflow variation (Ratio is 3.0x, should clamp down to 2.0f)
        val fastRate = ttsEngine.calculateTtsPlaybackRate(6.0f, 2.0f)
        assertEquals("Upper bounds ceiling clamp failed to restrict speed up-drift.", 2.0f, fastRate, 0.0f)
    }
}
