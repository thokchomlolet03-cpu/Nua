package org.nua.production.app.data.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TelemetryPruningTest {

    private lateinit var context: Context
    private lateinit var telemetryStore: LocalTelemetryStore
    private lateinit var telemetryDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        telemetryStore = LocalTelemetryStore.getInstance(context)
        
        // Locate the target storage directory used by the production code
        telemetryDir = File(context.filesDir, "telemetry_ledger")
        telemetryDir.mkdirs()
        
        // Ensure clean slate before running tests
        clearTelemetryDirectory()
    }

    @After
    fun tearDown() {
        // Clean up generated artifacts post test run
        clearTelemetryDirectory()
    }

    /**
     * Comprehensive Storage Bounds Test:
     * 1. Spawns 105 total '.tlm' files with staggered historical timestamps.
     * 2. Introduces a foreign control file '.txt' to verify type isolation safety.
     * 3. Executes the chronological pruning sequence.
     * 4. Asserts that exactly 100 '.tlm' files remain, the 5 oldest are deleted, 
     * the youngest remain intact, and the foreign file is untouched.
     */
    @Test
    fun verifyChronologicalPruningAndTypeIsolation() {
        val totalFilesToCreate = 105
        val baseTime = System.currentTimeMillis()
        
        // 1. Bulk generate telemetry files with distinct modification times (1 minute gaps)
        // File 0 will be the absolute oldest; File 104 will be the absolute newest.
        for (i in 0 until totalFilesToCreate) {
            val file = File(telemetryDir, "session_mock_payload_$i.tlm")
            file.writeText("{\"mock_session_id\":\"id_$i\",\"completion_percentage\":100}")
            
            // Explicitly set historical modification time spaced out cleanly
            val artificialTimestamp = baseTime - ((totalFilesToCreate - i) * 60_000L)
            val timeSetSuccess = file.setLastModified(artificialTimestamp)
            assertTrue("File system must successfully assign artificial timestamp metadata", timeSetSuccess)
        }

        // 2. Add an isolated control file without the target extension to verify type safety
        val controlFile = File(telemetryDir, "system_configuration_manifest.txt")
        controlFile.writeText("CRITICAL_CONFIG_DO_NOT_DELETE")
        val controlTimestamp = baseTime - (200 * 60_000L) // Set it as older than all other files
        controlFile.setLastModified(controlTimestamp)

        // Verify initial directory count bounds before pruning
        val totalTlmBefore = telemetryDir.listFiles { _, name -> name.endsWith(".tlm") }?.size ?: 0
        assertEquals("Initial setup must contain exactly 105 telemetry files", 105, totalTlmBefore)

        // 3. EXECUTE THE PRUNING CODE PHASE
        telemetryStore.pruneTelemetryStorageCeiling()

        // 4. COMPREHENSIVE RECOVERY ASSERTIONS
        val tlmFilesAfter = telemetryDir.listFiles { _, name -> name.endsWith(".tlm") }?.sortedBy { it.lastModified() }
        assertNotNull("Processed file array list must not be null", tlmFilesAfter)
        assertEquals("The directory must prune down to exactly the 100-file ceiling limit", 100, tlmFilesAfter!!.size)

        // Assert that the 5 oldest files (indices 0, 1, 2, 3, 4) were the ones eliminated
        for (i in 0 until 5) {
            val missingFile = File(telemetryDir, "session_mock_payload_$i.tlm")
            assertFalse("The historic file asset ($i) should be permanently deleted", missingFile.exists())
        }

        // Assert that the younger files (indices 5 to 104) are perfectly intact
        for (i in 5 until totalFilesToCreate) {
            val survivingFile = File(telemetryDir, "session_mock_payload_$i.tlm")
            assertTrue("The younger file asset ($i) must survive chronological pruning", survivingFile.exists())
        }

        // Assert that the non-telemetry file was completely ignored despite being the oldest file overall
        assertTrue(
            "Type Safety Failure: A non-telemetry file extension was erroneously pruned from storage",
            controlFile.exists()
        )
    }

    /**
     * Concurrency Stress Test:
     * 1. Generates an overflow pool of 110 telemetry files.
     * 2. Prepares a multi-threaded pool executing on 10 isolated parallel threads.
     * 3. Launches 10 concurrent requests to prune the storage ceiling at the exact same moment.
     * 4. Verifies that the internal synchronization lock prevents race conditions,
     * no native crash exceptions are thrown, and the directory ends up with exactly 100 files.
     */
    @Test
    fun verifyConcurrentPruningRaceConditionImmunity() = runBlocking {
        val totalFilesToCreate = 110
        val baseTime = System.currentTimeMillis()

        // 1. Setup the overflow directory state
        for (i in 0 until totalFilesToCreate) {
            val file = File(telemetryDir, "concurrent_mock_payload_$i.tlm")
            file.writeText("{\"session\":\"$i\"}")
            // Space timestamps out so sorting order is clear
            file.setLastModified(baseTime - ((totalFilesToCreate - i) * 60_000L))
        }

        // 2. Build a multi-threaded dispatcher pool (10 parallel lanes)
        val threadPool = Executors.newFixedThreadPool(10).asCoroutineDispatcher()
        val failureCount = AtomicInteger(0)
        val totalConcurrentLaunches = 10

        // 3. ACT: Blast the method from 10 threads simultaneously
        val jobs = List(totalConcurrentLaunches) {
            launch(threadPool) {
                try {
                    telemetryStore.pruneTelemetryStorageCeiling()
                } catch (e: Exception) {
                    // Track if any thread throws a ConcurrentModificationException or NullPointerException
                    failureCount.incrementAndGet()
                }
            }
        }

        // Wait for all 10 racing threads to finish their execution paths
        jobs.forEach { it.join() }
        threadPool.close()

        // 4. ASSERT
        assertEquals(
            "Concurrency Failure: Code threw an unexpected exception during parallel multi-threaded execution.",
            0,
            failureCount.get()
        )

        val remainingFiles = telemetryDir.listFiles { _, name -> name.endsWith(".tlm") }
        assertNotNull("Remaining files array must not be null", remainingFiles)
        assertEquals(
            "The synchronization lock failed to accurately clean directory bounds under high concurrency stress.",
            100,
            remainingFiles!!.size
        )
    }

    private fun clearTelemetryDirectory() {
        if (telemetryDir.exists()) {
            telemetryDir.listFiles()?.forEach { it.delete() }
        }
    }
}
