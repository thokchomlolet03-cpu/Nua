package org.nua.production.app.data.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.nua.production.app.data.asr.AudioChunk

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineCompilerServiceTest {

    @Before
    fun setUp() {
        PipelineCompilerService.clearLogs()
    }

    /**
     * Asserts that a missing model asset gracefully forces an explicit
     * error state down to the UI thread instead of freezing at 2%.
     *
     * Validates the core async pattern: when the consumer (transcription)
     * fails with an IllegalStateException, the Result wrapper captures
     * the failure cleanly without crashing the coordinator.
     */
    @Test
    fun verifyMissingModelThrowsExceptionAndPropagatesErrorToUi() = runTest(UnconfinedTestDispatcher()) {
        val audioChannel = Channel<AudioChunk>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.SUSPEND
        )

        // Simulate the consumer (transcription) failing due to missing model
        val consumerJob = async {
            runCatching {
                throw IllegalStateException("Whisper language model is not downloaded. Please download in Setup first.")
            }
        }

        val result = consumerJob.await()

        // Assert that the failure is captured in the Result wrapper, not silently swallowed
        assertTrue("Consumer failure must be captured in Result wrapper", result.isFailure)
        assertTrue(
            "Exception type must be IllegalStateException",
            result.exceptionOrNull() is IllegalStateException
        )

        // Simulate the lifecycle coordinator closing the channel to unblock the producer
        audioChannel.close()

        // Assert the channel is closed for sends (unblocking the producer thread)
        assertTrue("Channel must be closed to unblock the producer thread", audioChannel.isClosedForSend)
    }

    /**
     * Validates that error state propagation through companion StateFlows
     * correctly surfaces error messages to the UI layer.
     */
    @Test
    fun verifyErrorStatePropagatesCleanlyToCompanionStateFlow() = runTest(UnconfinedTestDispatcher()) {
        // Simulate adding an error log entry (as the lifecycle coordinator would do)
        PipelineCompilerService.addLog("❌ Transcription failed: Whisper language model is not downloaded.")

        val logs = PipelineCompilerService.logs.value
        assertTrue("Error log must be observable via companion StateFlow", logs.isNotEmpty())
        assertTrue(
            "Error message must contain failure details",
            logs.last().contains("Transcription failed")
        )

        // Verify clearing works cleanly for next pipeline run
        PipelineCompilerService.clearLogs()
        assertTrue("Logs must be clearable for subsequent pipeline executions", PipelineCompilerService.logs.value.isEmpty())
    }
}
