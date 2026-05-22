package org.nua.production.app.data.media

import org.nua.production.app.data.schema.GraphNode
import org.nua.production.app.data.schema.Hotspot
import org.nua.production.app.data.schema.LectureSession
import org.nua.production.app.data.schema.OptionSelection
import org.nua.production.app.data.schema.Quiz
import org.nua.production.app.data.schema.TelemetryPayload
import org.nua.production.app.data.schema.TimeSegment
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Validates FlatBuffers binary round-trip stability for the Nua v4.0 schema.
 * Generates test vectors dynamically from the schema contract and verifies
 * all fields survive serialization → deserialization.
 */
class SchemaValidationTest {

    @Test
    fun verifySchemaVersionRoundTrip() {
        val builder = FlatBufferBuilder(1024)
        val sessionIdOff = builder.createString("test_session_001")
        val sourceLangOff = builder.createString("en")
        val targetLangOff = builder.createString("hi")

        val segIdOff = builder.createString("seg_0")
        val audioPathOff = builder.createString("vocal_chunks/vocal_0_5000.wav")
        val origTextOff = builder.createString("Hello world")
        val transTextOff = builder.createString("Namaste duniya")
        val directiveOff = builder.createString("NORMAL_SYNC")

        val seg = TimeSegment.createTimeSegment(
            builder, segIdOff, 0, 5000, audioPathOff, 4500,
            origTextOff, transTextOff, directiveOff, 0
        )
        val segsVector = LectureSession.createTimelineTracksVector(builder, intArrayOf(seg))

        val root = LectureSession.createLectureSession(
            builder,
            schemaVersion = 1u,
            sessionIdOffset = sessionIdOff,
            sourceLangOffset = sourceLangOff,
            targetLangOffset = targetLangOff,
            timelineTracksVectorOffset = segsVector
        )
        LectureSession.finishLectureSessionBuffer(builder, root)

        val buf = ByteBuffer.wrap(builder.sizedByteArray())
        val session = LectureSession.getRootAsLectureSession(buf)

        assertEquals(1.toUShort(), session.schemaVersion)
        assertEquals("test_session_001", session.sessionId)
        assertEquals("en", session.sourceLang)
        assertEquals("hi", session.targetLang)
        assertEquals(1, session.timelineTracksLength)

        val readSeg = session.timelineTracks(0)!!
        assertEquals("seg_0", readSeg.segmentId)
        assertEquals(0L, readSeg.videoStartMs)
        assertEquals(5000L, readSeg.videoEndMs)
        assertEquals("Hello world", readSeg.originalText)
        assertEquals("Namaste duniya", readSeg.translatedText)
        assertEquals("NORMAL_SYNC", readSeg.directive)
        assertEquals(4500L, readSeg.audioDurationMs)
    }

    @Test
    fun verifyDirectiveStringRoundTrip() {
        val builder = FlatBufferBuilder(512)
        val segIdOff = builder.createString("seg_freeze")
        val audioPathOff = builder.createString("vocal.wav")
        val origOff = builder.createString("Original")
        val transOff = builder.createString("Translated")
        val directiveOff = builder.createString("FREEZE_HOLD")

        val seg = TimeSegment.createTimeSegment(
            builder, segIdOff, 1000, 3000, audioPathOff, 5000,
            origOff, transOff, directiveOff, 0
        )
        val segsVector = LectureSession.createTimelineTracksVector(builder, intArrayOf(seg))

        val sessionIdOff = builder.createString("test")
        val langOff = builder.createString("en")
        val root = LectureSession.createLectureSession(
            builder,
            schemaVersion = 1u,
            sessionIdOffset = sessionIdOff,
            sourceLangOffset = langOff,
            targetLangOffset = langOff,
            timelineTracksVectorOffset = segsVector
        )
        LectureSession.finishLectureSessionBuffer(builder, root)

        val session = LectureSession.getRootAsLectureSession(ByteBuffer.wrap(builder.sizedByteArray()))
        assertEquals("FREEZE_HOLD", session.timelineTracks(0)!!.directive)
    }

    @Test
    fun verifyOptionSelectionRoundTrip() {
        val builder = FlatBufferBuilder(512)

        val quizIdOff = builder.createString("q1")
        val optSel = OptionSelection.createOptionSelection(
            builder, quizIdOff, 2u, 1500L, true
        )

        val responsesVector = TelemetryPayload.createQuizResponsesVector(builder, intArrayOf(optSel))
        val sessionIdOff = builder.createString("session_test")
        val sigOff = builder.createString("abc123")

        val payload = TelemetryPayload.createTelemetryPayload(
            builder, sessionIdOff, 85u, responsesVector, sigOff
        )
        builder.finish(payload)

        val buf = ByteBuffer.wrap(builder.sizedByteArray())
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val tp = TelemetryPayload()
        tp.__init(buf.getInt(buf.position()) + buf.position(), buf)

        assertEquals("session_test", tp.sessionId)
        assertEquals(85.toUByte(), tp.completionPercentage)
        assertEquals("abc123", tp.cryptographicSignature)
        assertEquals(1, tp.quizResponsesLength)

        val response = tp.quizResponses(0)!!
        assertEquals("q1", response.quizId)
        assertEquals(2.toUByte(), response.selectedOptionIndex)
        assertEquals(1500L, response.latencyMs)
        assertTrue(response.isCorrect)
    }

    @Test
    fun verifyQuizAndKnowledgeGraphRoundTrip() {
        val builder = FlatBufferBuilder(1024)

        // Quiz
        val qOff = builder.createString("What is photosynthesis?")
        val opt1 = builder.createString("A process of light")
        val opt2 = builder.createString("Plant growth")
        val optsVec = Quiz.createOptionsVector(builder, intArrayOf(opt1, opt2))
        val quiz = Quiz.createQuiz(builder, 5000L, qOff, optsVec, 0u)
        val quizzesVec = LectureSession.createQuizzesVector(builder, intArrayOf(quiz))

        // Knowledge Graph
        val nodeIdOff = builder.createString("concept_1")
        val factoidOff = builder.createString("Photosynthesis converts light to energy")
        val kw1 = builder.createString("photosynthesis")
        val kw2 = builder.createString("light")
        val kwVec = GraphNode.createKeywordsVector(builder, intArrayOf(kw1, kw2))
        val ctx1 = builder.createString("biology")
        val ctxVec = GraphNode.createContextTokensVector(builder, intArrayOf(ctx1))
        val graphNode = GraphNode.createGraphNode(builder, nodeIdOff, kwVec, factoidOff, ctxVec)
        val graphVec = LectureSession.createKnowledgeGraphVector(builder, intArrayOf(graphNode))

        // Build root
        val sessionIdOff = builder.createString("test_full")
        val langOff = builder.createString("en")
        val segIdOff = builder.createString("seg_0")
        val audioOff = builder.createString("v.wav")
        val origOff = builder.createString("Hello")
        val transOff = builder.createString("Namaste")
        val dirOff = builder.createString("NORMAL_SYNC")
        val seg = TimeSegment.createTimeSegment(builder, segIdOff, 0, 5000, audioOff, 5000, origOff, transOff, dirOff, 0)
        val segsVec = LectureSession.createTimelineTracksVector(builder, intArrayOf(seg))

        val root = LectureSession.createLectureSession(
            builder,
            schemaVersion = 1u,
            sessionIdOffset = sessionIdOff,
            sourceLangOffset = langOff,
            targetLangOffset = langOff,
            timelineTracksVectorOffset = segsVec,
            quizzesVectorOffset = quizzesVec,
            knowledgeGraphVectorOffset = graphVec
        )
        LectureSession.finishLectureSessionBuffer(builder, root)

        val session = LectureSession.getRootAsLectureSession(ByteBuffer.wrap(builder.sizedByteArray()))

        // Verify quiz
        assertEquals(1, session.quizzesLength)
        val readQuiz = session.quizzes(0)!!
        assertEquals("What is photosynthesis?", readQuiz.question)
        assertEquals(5000L, readQuiz.triggerTimestampMs)
        assertEquals(2, readQuiz.optionsLength)
        assertEquals(0.toUByte(), readQuiz.correctIndex)

        // Verify knowledge graph
        assertEquals(1, session.knowledgeGraphLength)
        val readNode = session.knowledgeGraph(0)!!
        assertEquals("concept_1", readNode.nodeId)
        assertEquals("Photosynthesis converts light to energy", readNode.summaryFactoid)
        assertEquals(2, readNode.keywordsLength)
        assertEquals("photosynthesis", readNode.keywords(0))
        assertEquals(1, readNode.contextTokensLength)
        assertEquals("biology", readNode.contextTokens(0))
    }

    @Test
    fun verifyHotspotRoundTrip() {
        val builder = FlatBufferBuilder(512)

        val tokenOff = builder.createString("mitochondria")
        val defOff = builder.createString("The powerhouse of the cell")
        val hotspot = Hotspot.createHotspot(builder, tokenOff, defOff)
        val hotspotsVec = TimeSegment.createHotspotsVector(builder, intArrayOf(hotspot))

        val segIdOff = builder.createString("seg_hs")
        val audioOff = builder.createString("v.wav")
        val origOff = builder.createString("Cell biology")
        val transOff = builder.createString("Koshika vigyaan")
        val dirOff = builder.createString("NORMAL_SYNC")
        val seg = TimeSegment.createTimeSegment(
            builder, segIdOff, 0, 3000, audioOff, 3000,
            origOff, transOff, dirOff, hotspotsVec
        )
        val segsVec = LectureSession.createTimelineTracksVector(builder, intArrayOf(seg))

        val sessionIdOff = builder.createString("test_hotspot")
        val langOff = builder.createString("en")
        val root = LectureSession.createLectureSession(
            builder, schemaVersion = 1u,
            sessionIdOffset = sessionIdOff,
            sourceLangOffset = langOff,
            targetLangOffset = langOff,
            timelineTracksVectorOffset = segsVec
        )
        LectureSession.finishLectureSessionBuffer(builder, root)

        val session = LectureSession.getRootAsLectureSession(ByteBuffer.wrap(builder.sizedByteArray()))
        val readSeg = session.timelineTracks(0)!!
        assertEquals(1, readSeg.hotspotsLength)
        val hs = readSeg.hotspots(0)!!
        assertEquals("mitochondria", hs.token)
        assertEquals("The powerhouse of the cell", hs.conceptDefinition)
    }
}
