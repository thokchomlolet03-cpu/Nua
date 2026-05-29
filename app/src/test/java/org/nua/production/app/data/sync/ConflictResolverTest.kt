package org.nua.production.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ConflictResolverTest {

    private lateinit var conflictResolver: ConflictResolver

    @Before
    fun setUp() {
        conflictResolver = ConflictResolver()
    }

    /**
     * Test Scenario 1: The Clock-Drift Trap (Crucial Verification)
     * ARRANGE: A local record has a high timestamp but an old sequence version.
     * An incoming peer record has an old timestamp (due to clock drift) but a newer sequence version.
     * ACT: Execute the conflict merge.
     * ASSERT: The incoming record must win, proving the system ignores wall-clock errors.
     */
    @Test
    fun verifyNewerSequenceVersionOverridesOlderSequenceEvenWithDriftedTimestamp() {
        // Arrange
        val localState = TelemetryState(
            sessionId = "quiz_session_001",
            completionPercentage = 50,
            sequenceVersion = 2L,
            deviceTimestamp = 1717000000000L // Chronologically NEWER wall-clock
        )

        val incomingState = TelemetryState(
            sessionId = "quiz_session_001",
            completionPercentage = 100,
            sequenceVersion = 3L,            // Higher execution counter sequence step
            deviceTimestamp = 1500000000000L // Chronologically OLDER wall-clock due to bad device configuration
        )

        // Act
        val resultState = conflictResolver.resolveMerge(localState, incomingState)

        // Assert
        assertEquals(
            "Clock-Drift Bug Detected: Resolver wrongly favored wall-clock timestamp over sequence version number.",
            3L,
            resultState.sequenceVersion
        )
        assertEquals(100, resultState.completionPercentage)
    }

    /**
     * Test Scenario 2: Stale Peer Data Rejection
     * Verifies that if an incoming peer transmits an historical sequence variation, it is safely blocked.
     */
    @Test
    fun verifyOutdatedSequenceIncomingIsRejected() {
        // Arrange
        val localState = TelemetryState("quiz_session_001", 80, 5L, 1717000000000L)
        val incomingState = TelemetryState("quiz_session_001", 20, 4L, 1717000050000L) // Stale update

        // Act
        val resultState = conflictResolver.resolveMerge(localState, incomingState)

        // Assert
        assertEquals("Security Violation: Outdated peer sequence successfully overrode a local version.", 5L, resultState.sequenceVersion)
        assertEquals(80, resultState.completionPercentage)
    }

    /**
     * Test Scenario 3: Null Local Target Edge Case
     * Verifies that when an entirely new session entry is introduced via the mesh link, it populates cleanly.
     */
    @Test
    fun verifyNullLocalTargetAcceptsIncomingData() {
        // Arrange
        val incomingState = TelemetryState("new_session_999", 100, 1L, 1717000000000L)

        // Act
        val resultState = conflictResolver.resolveMerge(null, incomingState)

        // Assert
        assertEquals("New entry instantiation failed to initialize incoming payload values.", "new_session_999", resultState.sessionId)
    }
}
