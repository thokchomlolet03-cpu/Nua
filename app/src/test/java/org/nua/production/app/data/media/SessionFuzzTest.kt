package org.nua.production.app.data.media

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Automated fuzz testing system for FlatBuffers serialization and parsing limits.
 * Validates that SessionManager does not crash when parsing malformed or corrupted binaries.
 */
class SessionFuzzTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sessionManager: SessionManager
    private val random = Random(42) // Deterministic seed for reproducible runs

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        sessionManager = SessionManager(filesDir)
    }

    @Test
    fun testRandomByteFuzzing() {
        val tempDir = tempFolder.newFolder("fuzz_session_random")
        val manifestFile = File(tempDir, "manifest.nuab")

        // Try various random payloads from 0 bytes to 10KB
        for (i in 0..500) {
            val length = random.nextInt(0, 10240)
            val randomBytes = ByteArray(length)
            random.nextBytes(randomBytes)
            manifestFile.writeBytes(randomBytes)

            try {
                // Should return null or throw a safe parse exception, but NEVER crash the process
                val session = sessionManager.loadManifestBinary(tempDir)
                // If it successfully parses something (highly unlikely), verify we can access fields safely
                if (session != null) {
                    session.sessionId
                    session.schemaVersion
                    session.timelineTracksLength
                }
            } catch (e: Exception) {
                // Caught exceptions are fine, indicating parser safely rejected the malformed data
            } catch (e: IndexOutOfBoundsException) {
                // Out of bounds is also fine for corrupted flatbuffer offset structures
            }
        }
    }

    @Test
    fun testMutationFuzzing() {
        // First create a valid manifest binary
        val tempDir = tempFolder.newFolder("fuzz_session_mutation")
        val composition = MediaComposition(
            videoId = "valid_id",
            sourceVideoPath = "video.mp4",
            segments = listOf(
                PlaybackSegment(
                    startMs = 0,
                    endMs = 1000,
                    originalText = "Hello",
                    translatedText = "Hi",
                    vocalAssetLocalPath = "vocal.wav"
                )
            )
        )
        sessionManager.saveManifest(tempDir, composition)
        val validFile = File(tempDir, "manifest.nuab")
        assertTrue(validFile.exists())
        val validBytes = validFile.readBytes()

        // Mutate the valid bytes in different ways and try parsing
        for (i in 0..500) {
            val mutatedBytes = validBytes.clone()
            
            // Apply 1 to 5 random byte corruptions/mutations
            val mutationsCount = random.nextInt(1, 6)
            for (m in 0 until mutationsCount) {
                val mutationType = random.nextInt(3)
                when (mutationType) {
                    0 -> {
                        // Flip a single random byte
                        val index = random.nextInt(mutatedBytes.size)
                        mutatedBytes[index] = random.nextInt(256).toByte()
                    }
                    1 -> {
                        // Truncate at a random point
                        val truncateLen = random.nextInt(1, mutatedBytes.size)
                        val truncated = mutatedBytes.copyOfRange(0, truncateLen)
                        validFile.writeBytes(truncated)
                        try {
                            sessionManager.loadManifestBinary(tempDir)
                        } catch (e: Exception) {}
                        catch (e: IndexOutOfBoundsException) {}
                        continue
                    }
                    2 -> {
                        // Set a chunk of bytes to zeros
                        val start = random.nextInt(mutatedBytes.size)
                        val len = random.nextInt(1, (mutatedBytes.size - start).coerceAtLeast(2))
                        for (j in start until (start + len)) {
                            mutatedBytes[j] = 0
                        }
                    }
                }
            }

            validFile.writeBytes(mutatedBytes)
            try {
                sessionManager.loadManifestBinary(tempDir)
            } catch (e: Exception) {
                // Expected parsing safety behavior
            } catch (e: IndexOutOfBoundsException) {
                // Expected parsing safety behavior
            }
        }
    }
}
