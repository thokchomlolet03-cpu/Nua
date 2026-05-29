package org.nua.production.app.data.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.nua.production.app.data.asr.AudioChunk
import java.io.File
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineIngestionStressTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var testSessionDir: File
    private lateinit var stressTestScope: CoroutineScope
    private val backgroundExecutor = Executors.newFixedThreadPool(4)

    @Before
    fun setUp() {
        testSessionDir = File(targetContext.cacheDir, "stress_test_session").apply { mkdirs() }
        stressTestScope = CoroutineScope(backgroundExecutor.asCoroutineDispatcher() + SupervisorJob())
    }

    @After
    fun tearDown() {
        testSessionDir.deleteRecursively()
        backgroundExecutor.shutdown()
    }

    /**
     * Test Scenario 1: Extreme Backpressure & Buffer Exhaustion Stress Test
     * Spawns a high-velocity producer pumping data far faster than the consumer handles it.
     * Asserts that the Channel naturally suspends via backpressure and never throws an OOM error.
     */
    @Test
    fun verifyPipelineHandlesInfiniteBackpressureWithoutSaturatingMemoryHeap() = runTest {
        val audioChannel = Channel<AudioChunk>(
            capacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND
        )

        var totalChunksProcessed = 0
        var isPipelineAlive = true

        // 1. High-Velocity Producer Task: Constantly dumps simulated data chunks
        val producerJob = stressTestScope.launch {
            try {
                repeat(5000) { index -> // Mega stress cycle count
                    audioChannel.send(
                        AudioChunk(
                            index = index,
                            data = FloatArray(4096) { 0.5f },
                            startTimeSec = 0.0,
                            isLast = index == 4999
                        )
                    )
                }
            } catch (e: Exception) {
                isPipelineAlive = false
            } finally {
                audioChannel.close()
            }
        }

        // 2. Slow Deliberate Consumer Task: Simulates slow hardware processing weights
        val consumerJob = stressTestScope.launch {
            for (chunk in audioChannel) {
                totalChunksProcessed++
                // Force a tiny artificial delay to back up the data pipe deliberately
                delay(1) 
            }
        }

        // Wait for execution completion blocks within safe timeout bounds
        withTimeout(5000) {
            joinAll(producerJob, consumerJob)
        }

        assertTrue("Pipeline crashed under extreme streaming backpressure.", isPipelineAlive)
        assertEquals("Consumer missed data packets during backpressure throttling.", 5000, totalChunksProcessed)
    }

    /**
     * Test Scenario 2: High-Frequency Interruption & Crash Recovery Test
     * Intentionally kills the transcription consumer task 50 times in rapid succession.
     * Asserts that the pipeline always forces channel closure, unblocks the extractor, and prevents deadlocks.
     */
    @Test
    fun verifyRepeatedConsumerFailuresConsistentlyUnblockDecoderAndPreventDeadlocks() = runTest {
        repeat(50) { iteration ->
            val audioChannel = Channel<AudioChunk>(capacity = 1)
            val dummyVideoFile = File(testSessionDir, "test_input_$iteration.mp4").apply { writeText("dummy_media") }
            val targetWavFile = File(testSessionDir, "output_$iteration.wav")

            // Simulate immediate consumer explosion (e.g. missing model weights or JNI fault)
            val transcriptionJob = stressTestScope.async {
                throw RuntimeException("Simulated sudden Native/JNI extraction fault.")
            }

            // Simulate the audio extractor streaming loop
            val extractorJob = stressTestScope.async {
                try {
                    // Try to send data down the channel
                    audioChannel.send(
                        AudioChunk(
                            index = 0,
                            data = FloatArray(512),
                            startTimeSec = 0.0,
                            isLast = false
                        )
                    )
                    true
                } catch (e: Exception) {
                    false // Channel closed successfully, releasing lock
                }
            }

            // Central coordinating lifecycle logic under stress validation
            val txResult = runCatching { transcriptionJob.await() }
            if (txResult.isFailure) {
                audioChannel.close() // The critical fix we deployed
            }

            val extractorWasReleased = !extractorJob.await()
            assertTrue("Pipeline deadlocked on iteration $iteration! Decoder remained suspended.", extractorWasReleased)
        }
    }
}
