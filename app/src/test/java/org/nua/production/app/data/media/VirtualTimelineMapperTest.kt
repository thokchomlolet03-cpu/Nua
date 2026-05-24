package org.nua.production.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VirtualTimelineMapperTest {

    @Test
    fun testNormalSyncMapping() {
        val segment = PlaybackSegment(
            startMs = 1000L,
            endMs = 6000L, // 5000ms duration
            originalText = "Hello world",
            translatedText = "Namaste duniya",
            vocalAssetLocalPath = "vocal_chunks/vocal_1000_6000.wav",
            directive = "NORMAL_SYNC"
        )
        val composition = MediaComposition(
            videoId = "test_session",
            sourceVideoPath = "raw_lecture.mp4",
            segments = listOf(segment)
        )

        // Using a temporary empty folder so files don't exist and it uses default duration
        val dummyDir = File("dummy_session")
        val mapper = VirtualTimelineMapper.create(composition, dummyDir)

        // 1. Verify durations
        assertEquals(6000L, mapper.totalVirtualDurationMs)

        // 2. Map virtual to physical before segment (0 to 1000ms)
        val stateBefore = mapper.getPhysicalState(500L)
        assertEquals(500L, stateBefore.physicalTimeMs)
        assertFalse(stateBefore.shouldFreeze)
        assertEquals(null, stateBefore.activeInterval)

        // 3. Map virtual to physical inside segment (1000 to 6000ms)
        val stateInside = mapper.getPhysicalState(3000L)
        assertEquals(3000L, stateInside.physicalTimeMs)
        assertFalse(stateInside.shouldFreeze)
        assertEquals(segment.originalText, stateInside.activeInterval?.originalText)
        assertEquals(2000L, stateInside.vocalPlayheadMs) // 3000 - 1000 = 2000ms elapsed
    }

    @Test
    fun testFreezeHoldMapping() {
        // Construct a MediaComposition where the segment is 2000ms long, but vocal duration is mocked
        // Wait, since we are not writing actual WAV files here, how do we test the hold?
        // Let's create a minimal WAV file with a custom length in a temp file to test duration reading,
        // or we can test the mapping assuming the WAV file has a longer duration.
        // Let's create a tiny temporary WAV file to test!
        val tempDir = File(System.getProperty("java.io.tmpdir"), "nua_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val wavFile = File(tempDir, "vocal_1000_3000.wav")
            writeFakeWavFile(wavFile, 4000L) // 4000ms vocal duration (original is 2000ms, start=1000, end=3000)

            val segment = PlaybackSegment(
                startMs = 1000L,
                endMs = 3000L, // 2000ms original duration
                originalText = "Important info",
                translatedText = "Mahatvapurna jankari",
                vocalAssetLocalPath = wavFile.name,
                directive = "FREEZE_HOLD"
            )

            val composition = MediaComposition(
                videoId = "test_hold",
                sourceVideoPath = "raw_lecture.mp4",
                segments = listOf(segment)
            )

            val mapper = VirtualTimelineMapper.create(composition, tempDir)

            // 1. Durations: original ended at 3000ms. Cumulative hold is 4000 - 2000 = 2000ms.
            // Total virtual duration = 3000 + 2000 = 5000ms.
            assertEquals(5000L, mapper.totalVirtualDurationMs)

            // 2. Playback state before segment (500ms)
            val stateBefore = mapper.getPhysicalState(500L)
            assertEquals(500L, stateBefore.physicalTimeMs)
            assertFalse(stateBefore.shouldFreeze)

            // 3. Playback state inside segment, normal play section (1000ms to 3000ms virtual)
            // Virtual time 2000ms -> Physical time 2000ms (1000 + (2000 - 1000))
            val stateNormal = mapper.getPhysicalState(2000L)
            assertEquals(2000L, stateNormal.physicalTimeMs)
            assertFalse(stateNormal.shouldFreeze)
            assertEquals(1000L, stateNormal.vocalPlayheadMs)

            // 4. Playback state inside segment, freeze hold section (3000ms to 5000ms virtual)
            // Virtual time 4000ms -> Physical time 3000ms (frozen at endMs), shouldFreeze = true
            val stateFreeze = mapper.getPhysicalState(4000L)
            assertEquals(3000L, stateFreeze.physicalTimeMs)
            assertTrue(stateFreeze.shouldFreeze)
            assertEquals(3000L, stateFreeze.vocalPlayheadMs)

            // 5. Playback state after segment (virtual 5000ms) -> Physical time 3000ms
            val stateEnd = mapper.getPhysicalState(5000L)
            assertEquals(3000L, stateEnd.physicalTimeMs)

            // Test getVirtualTimeMs mapping
            assertEquals(500L, mapper.getVirtualTimeMs(500L))
            assertEquals(2000L, mapper.getVirtualTimeMs(2000L))
            assertEquals(5500L, mapper.getVirtualTimeMs(3500L))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun writeFakeWavFile(file: File, durationMs: Long) {
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val bytesPerSecond = sampleRate * channels * (bitsPerSample / 8)
        val pcmLength = (durationMs * bytesPerSecond) / 1000
        val totalLength = pcmLength + 36

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[4] = (totalLength and 0xffL).toByte()
        header[5] = ((totalLength shr 8) and 0xffL).toByte()
        header[6] = ((totalLength shr 16) and 0xffL).toByte()
        header[7] = ((totalLength shr 24) and 0xffL).toByte()

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16 // fmt chunk size
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1 // PCM
        header[21] = 0

        header[22] = channels.toByte()
        header[23] = 0

        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate ushr 8) and 0xff).toByte()
        header[26] = ((sampleRate ushr 16) and 0xff).toByte()
        header[27] = ((sampleRate ushr 24) and 0xff).toByte()

        header[28] = (bytesPerSecond and 0xff).toByte()
        header[29] = ((bytesPerSecond ushr 8) and 0xff).toByte()
        header[30] = ((bytesPerSecond ushr 16) and 0xff).toByte()
        header[31] = ((bytesPerSecond ushr 24) and 0xff).toByte()

        header[32] = (channels * 2).toByte()
        header[33] = 0

        header[34] = bitsPerSample.toByte()
        header[35] = 0

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (pcmLength and 0xffL).toByte()
        header[41] = ((pcmLength shr 8) and 0xffL).toByte()
        header[42] = ((pcmLength shr 16) and 0xffL).toByte()
        header[43] = ((pcmLength shr 24) and 0xffL).toByte()

        val fullData = ByteArray(44 + pcmLength.toInt())
        System.arraycopy(header, 0, fullData, 0, 44)
        file.writeBytes(fullData)
    }
}
