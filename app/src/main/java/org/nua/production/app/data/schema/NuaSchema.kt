package org.nua.production.app.data.schema

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer

/**
 * Kotlin wrapper for the NuaSerialization FlatBuffers schema (v4.0).
 * Provides zero-copy deserialization for session bundles (.nuab files).
 *
 * These classes mirror the schema in schema/nua_schema.fbs and use the
 * FlatBuffers Java runtime for memory-mapped binary access.
 */

// ─── Hotspot ───────────────────────────────────────────────────────────────

class Hotspot : Table() {
    companion object {
        fun getRootAsHotspot(bb: ByteBuffer): Hotspot {
            val obj = Hotspot()
            obj.__init(bb.getInt(bb.position()) + bb.position(), bb)
            return obj
        }

        fun createHotspot(builder: FlatBufferBuilder, tokenOffset: Int, definitionOffset: Int): Int {
            builder.startTable(2)
            builder.addOffset(1, definitionOffset, 0)
            builder.addOffset(0, tokenOffset, 0)
            return builder.endTable()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val token: String? get() {
        val o = __offset(4)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val conceptDefinition: String? get() {
        val o = __offset(6)
        return if (o != 0) __string(o + bb_pos) else null
    }
}

// ─── Quiz ──────────────────────────────────────────────────────────────────

class Quiz : Table() {
    companion object {
        fun getRootAsQuiz(bb: ByteBuffer): Quiz {
            val obj = Quiz()
            obj.__init(bb.getInt(bb.position()) + bb.position(), bb)
            return obj
        }

        fun createQuiz(
            builder: FlatBufferBuilder,
            triggerTimestampMsOffset: Long,
            questionOffset: Int,
            optionsVectorOffset: Int,
            correctIndex: UByte
        ): Int {
            builder.startTable(4)
            builder.addOffset(2, optionsVectorOffset, 0)
            builder.addOffset(1, questionOffset, 0)
            builder.addInt(0, triggerTimestampMsOffset.toInt(), 0)
            builder.addByte(3, correctIndex.toByte(), 0)
            return builder.endTable()
        }

        fun createOptionsVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val triggerTimestampMs: Long get() {
        val o = __offset(4)
        return if (o != 0) bb.getInt(o + bb_pos).toLong() and 0xFFFFFFFFL else 0L
    }
    val question: String? get() {
        val o = __offset(6)
        return if (o != 0) __string(o + bb_pos) else null
    }
    fun options(j: Int): String? {
        val o = __offset(8)
        return if (o != 0) __string(__vector(o) + j * 4) else null
    }
    val optionsLength: Int get() {
        val o = __offset(8)
        return if (o != 0) __vector_len(o) else 0
    }
    val correctIndex: UByte get() {
        val o = __offset(10)
        return if (o != 0) bb.get(o + bb_pos).toUByte() else 0u
    }
}

// ─── GraphNode ─────────────────────────────────────────────────────────────

class GraphNode : Table() {
    companion object {
        fun createGraphNode(
            builder: FlatBufferBuilder,
            nodeIdOffset: Int,
            keywordsVectorOffset: Int,
            summaryFactoidOffset: Int,
            contextTokensVectorOffset: Int
        ): Int {
            builder.startTable(4)
            builder.addOffset(3, contextTokensVectorOffset, 0)
            builder.addOffset(2, summaryFactoidOffset, 0)
            builder.addOffset(1, keywordsVectorOffset, 0)
            builder.addOffset(0, nodeIdOffset, 0)
            return builder.endTable()
        }

        fun createKeywordsVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }

        fun createContextTokensVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val nodeId: String? get() {
        val o = __offset(4)
        return if (o != 0) __string(o + bb_pos) else null
    }
    fun keywords(j: Int): String? {
        val o = __offset(6)
        return if (o != 0) __string(__vector(o) + j * 4) else null
    }
    val keywordsLength: Int get() {
        val o = __offset(6)
        return if (o != 0) __vector_len(o) else 0
    }
    val summaryFactoid: String? get() {
        val o = __offset(8)
        return if (o != 0) __string(o + bb_pos) else null
    }
    fun contextTokens(j: Int): String? {
        val o = __offset(10)
        return if (o != 0) __string(__vector(o) + j * 4) else null
    }
    val contextTokensLength: Int get() {
        val o = __offset(10)
        return if (o != 0) __vector_len(o) else 0
    }
}

// ─── OptionSelection ───────────────────────────────────────────────────────

class OptionSelection : Table() {
    companion object {
        fun createOptionSelection(
            builder: FlatBufferBuilder,
            quizIdOffset: Int,
            selectedOptionIndex: UByte,
            latencyMs: Long,
            isCorrect: Boolean
        ): Int {
            builder.startTable(4)
            builder.addInt(2, latencyMs.toInt(), 0)
            builder.addOffset(0, quizIdOffset, 0)
            builder.addByte(1, selectedOptionIndex.toByte(), 0)
            builder.addBoolean(3, isCorrect, false)
            return builder.endTable()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val quizId: String? get() {
        val o = __offset(4)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val selectedOptionIndex: UByte get() {
        val o = __offset(6)
        return if (o != 0) bb.get(o + bb_pos).toUByte() else 0u
    }
    val latencyMs: Long get() {
        val o = __offset(8)
        return if (o != 0) bb.getInt(o + bb_pos).toLong() and 0xFFFFFFFFL else 0L
    }
    val isCorrect: Boolean get() {
        val o = __offset(10)
        return if (o != 0) bb.get(o + bb_pos).toInt() != 0 else false
    }
}

// ─── TelemetryPayload ─────────────────────────────────────────────────────

class TelemetryPayload : Table() {
    companion object {
        fun getRootAsTelemetryPayload(bb: ByteBuffer): TelemetryPayload {
            val obj = TelemetryPayload()
            obj.__init(bb.getInt(bb.position()) + bb.position(), bb)
            return obj
        }

        fun createTelemetryPayload(
            builder: FlatBufferBuilder,
            sessionIdOffset: Int,
            completionPercentage: UByte,
            quizResponsesVectorOffset: Int,
            cryptographicSignatureOffset: Int
        ): Int {
            builder.startTable(4)
            builder.addOffset(3, cryptographicSignatureOffset, 0)
            builder.addOffset(2, quizResponsesVectorOffset, 0)
            builder.addOffset(0, sessionIdOffset, 0)
            builder.addByte(1, completionPercentage.toByte(), 0)
            return builder.endTable()
        }

        fun createQuizResponsesVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val sessionId: String? get() {
        val o = __offset(4)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val completionPercentage: UByte get() {
        val o = __offset(6)
        return if (o != 0) bb.get(o + bb_pos).toUByte() else 0u
    }
    fun quizResponses(j: Int): OptionSelection? {
        val o = __offset(8)
        if (o == 0) return null
        val obj = OptionSelection()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val quizResponsesLength: Int get() {
        val o = __offset(8)
        return if (o != 0) __vector_len(o) else 0
    }
    val cryptographicSignature: String? get() {
        val o = __offset(10)
        return if (o != 0) __string(o + bb_pos) else null
    }
}

// ─── TimeSegment ──────────────────────────────────────────────────────────

class TimeSegment : Table() {
    companion object {
        fun createTimeSegment(
            builder: FlatBufferBuilder,
            segmentIdOffset: Int,
            videoStartMs: Long,
            videoEndMs: Long,
            audioSourcePathOffset: Int,
            audioDurationMs: Long,
            originalTextOffset: Int,
            translatedTextOffset: Int,
            directiveOffset: Int = 0,
            hotspotsVectorOffset: Int = 0
        ): Int {
            builder.startTable(9)
            builder.addOffset(8, hotspotsVectorOffset, 0)
            builder.addOffset(7, directiveOffset, 0)
            builder.addOffset(6, translatedTextOffset, 0)
            builder.addOffset(5, originalTextOffset, 0)
            builder.addInt(4, audioDurationMs.toInt(), 0)
            builder.addOffset(3, audioSourcePathOffset, 0)
            builder.addInt(2, videoEndMs.toInt(), 0)
            builder.addInt(1, videoStartMs.toInt(), 0)
            builder.addOffset(0, segmentIdOffset, 0)
            return builder.endTable()
        }

        fun createHotspotsVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val segmentId: String? get() {
        val o = __offset(4)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val videoStartMs: Long get() {
        val o = __offset(6)
        return if (o != 0) bb.getInt(o + bb_pos).toLong() and 0xFFFFFFFFL else 0L
    }
    val videoEndMs: Long get() {
        val o = __offset(8)
        return if (o != 0) bb.getInt(o + bb_pos).toLong() and 0xFFFFFFFFL else 0L
    }
    val audioSourcePath: String? get() {
        val o = __offset(10)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val audioDurationMs: Long get() {
        val o = __offset(12)
        return if (o != 0) bb.getInt(o + bb_pos).toLong() and 0xFFFFFFFFL else 0L
    }
    val originalText: String? get() {
        val o = __offset(14)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val translatedText: String? get() {
        val o = __offset(16)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val directive: String? get() {
        val o = __offset(18)
        return if (o != 0) __string(o + bb_pos) else null
    }
    fun hotspots(j: Int): Hotspot? {
        val o = __offset(20)
        if (o == 0) return null
        val obj = Hotspot()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val hotspotsLength: Int get() {
        val o = __offset(20)
        return if (o != 0) __vector_len(o) else 0
    }
}

// ─── LectureSession (Root) ────────────────────────────────────────────────

class LectureSession : Table() {
    companion object {
        fun getRootAsLectureSession(bb: ByteBuffer): LectureSession {
            val obj = LectureSession()
            bb.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            obj.__init(bb.getInt(bb.position()) + bb.position(), bb)
            return obj
        }

        fun createLectureSession(
            builder: FlatBufferBuilder,
            schemaVersion: UShort = 1u,
            sessionIdOffset: Int,
            sourceLangOffset: Int,
            targetLangOffset: Int,
            courseTitleOffset: Int = 0,
            sourceVideoPathOffset: Int = 0,
            timelineTracksVectorOffset: Int,
            quizzesVectorOffset: Int = 0,
            knowledgeGraphVectorOffset: Int = 0,
            telemetryLedgerVectorOffset: Int = 0
        ): Int {
            builder.startTable(10)
            builder.addOffset(9, telemetryLedgerVectorOffset, 0)
            builder.addOffset(8, knowledgeGraphVectorOffset, 0)
            builder.addOffset(7, quizzesVectorOffset, 0)
            builder.addOffset(6, timelineTracksVectorOffset, 0)
            builder.addOffset(5, sourceVideoPathOffset, 0)
            builder.addOffset(4, courseTitleOffset, 0)
            builder.addOffset(3, targetLangOffset, 0)
            builder.addOffset(2, sourceLangOffset, 0)
            builder.addOffset(1, sessionIdOffset, 0)
            builder.addShort(0, schemaVersion.toShort(), 1)
            return builder.endTable()
        }

        fun createTimelineTracksVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }

        fun createQuizzesVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }

        fun createKnowledgeGraphVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }

        fun createTelemetryLedgerVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }

        fun finishLectureSessionBuffer(builder: FlatBufferBuilder, offset: Int) {
            builder.finish(offset, "NUAB")
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val schemaVersion: UShort get() {
        val o = __offset(4)
        return if (o != 0) bb.getShort(o + bb_pos).toUShort() else 1u
    }
    val sessionId: String? get() {
        val o = __offset(6)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val sourceLang: String? get() {
        val o = __offset(8)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val targetLang: String? get() {
        val o = __offset(10)
        return if (o != 0) __string(o + bb_pos) else null
    }
    @Deprecated("Use sourceVideoPath instead", replaceWith = ReplaceWith("sourceVideoPath"))
    val courseTitle: String? get() {
        val o = __offset(12)
        return if (o != 0) __string(o + bb_pos) else null
    }
    val sourceVideoPath: String? get() {
        val o = __offset(14)
        return if (o != 0) __string(o + bb_pos) else null
    }
    fun timelineTracks(j: Int): TimeSegment? {
        val o = __offset(16)
        if (o == 0) return null
        val obj = TimeSegment()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val timelineTracksLength: Int get() {
        val o = __offset(16)
        return if (o != 0) __vector_len(o) else 0
    }
    fun quizzes(j: Int): Quiz? {
        val o = __offset(18)
        if (o == 0) return null
        val obj = Quiz()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val quizzesLength: Int get() {
        val o = __offset(18)
        return if (o != 0) __vector_len(o) else 0
    }
    fun knowledgeGraph(j: Int): GraphNode? {
        val o = __offset(20)
        if (o == 0) return null
        val obj = GraphNode()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val knowledgeGraphLength: Int get() {
        val o = __offset(20)
        return if (o != 0) __vector_len(o) else 0
    }
    fun telemetryLedger(j: Int): TelemetryPayload? {
        val o = __offset(22)
        if (o == 0) return null
        val obj = TelemetryPayload()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val telemetryLedgerLength: Int get() {
        val o = __offset(22)
        return if (o != 0) __vector_len(o) else 0
    }
}
