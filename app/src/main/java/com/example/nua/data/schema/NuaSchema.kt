package com.example.nua.data.schema

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer

/**
 * Kotlin wrapper for the NuaSerialization FlatBuffers schema.
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

    val token: String? get() = __string(__offset(4))
    val conceptDefinition: String? get() = __string(__offset(6))
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
            triggerTimestampMs: Long,
            questionOffset: Int,
            optionsOffset: Int,
            correctIndex: UByte
        ): Int {
            builder.startTable(4)
            builder.addInt(0, triggerTimestampMs.toInt(), 0)
            builder.addOffset(1, questionOffset, 0)
            builder.addOffset(2, optionsOffset, 0)
            builder.addByte(3, correctIndex.toByte(), 0)
            return builder.endTable()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val triggerTimestampMs: Long get() = bb.getInt(__offset(4)).toLong().and(0xFFFFFFFFL)
    val question: String? get() = __string(__offset(6))
    fun options(j: Int): String? = __string(__vector(__offset(8)) + j * 4)
    val optionsLength: Int get() { val o = __offset(10); return if (o != 0) __vector_len(o) else 0 }
    val correctIndex: UByte get() = (bb.get(__offset(12)).toInt() and 0xFF).toUByte()
}

// ─── GraphNode ─────────────────────────────────────────────────────────────

class GraphNode : Table() {
    companion object {
        fun createGraphNode(
            builder: FlatBufferBuilder,
            nodeIdOffset: Int,
            keywordsOffset: Int,
            summaryFactoidOffset: Int,
            contextTokensOffset: Int
        ): Int {
            builder.startTable(4)
            builder.addOffset(0, nodeIdOffset, 0)
            builder.addOffset(1, keywordsOffset, 0)
            builder.addOffset(2, summaryFactoidOffset, 0)
            builder.addOffset(3, contextTokensOffset, 0)
            return builder.endTable()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val nodeId: String? get() = __string(__offset(4))
    fun keywords(j: Int): String? = __string(__vector(__offset(6)) + j * 4)
    val keywordsLength: Int get() { val o = __offset(6); return if (o != 0) __vector_len(o) else 0 }
    val summaryFactoid: String? get() = __string(__offset(8))
    fun contextTokens(j: Int): String? = __string(__vector(__offset(10)) + j * 4)
    val contextTokensLength: Int get() { val o = __offset(10); return if (o != 0) __vector_len(o) else 0 }
}

// ─── TelemetryPayload ─────────────────────────────────────────────────────

class TelemetryPayload : Table() {
    companion object {
        fun createTelemetryPayload(
            builder: FlatBufferBuilder,
            sessionIdOffset: Int,
            completionPercentage: UByte,
            quizScoresJsonOffset: Int,
            cryptographicSignatureOffset: Int
        ): Int {
            builder.startTable(4)
            builder.addOffset(0, sessionIdOffset, 0)
            builder.addByte(1, completionPercentage.toByte(), 0)
            builder.addOffset(2, quizScoresJsonOffset, 0)
            builder.addOffset(3, cryptographicSignatureOffset, 0)
            return builder.endTable()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val sessionId: String? get() = __string(__offset(4))
    val completionPercentage: UByte get() = (bb.get(__offset(6)).toInt() and 0xFF).toUByte()
    val quizScoresJson: String? get() = __string(__offset(8))
    val cryptographicSignature: String? get() = __string(__offset(10))
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
            shouldFreeze: Boolean,
            hotspotsOffset: Int
        ): Int {
            builder.startTable(9)
            builder.addOffset(0, segmentIdOffset, 0)
            builder.addInt(1, videoStartMs.toInt(), 0)
            builder.addInt(2, videoEndMs.toInt(), 0)
            builder.addOffset(3, audioSourcePathOffset, 0)
            builder.addInt(4, audioDurationMs.toInt(), 0)
            builder.addOffset(5, originalTextOffset, 0)
            builder.addOffset(6, translatedTextOffset, 0)
            builder.addBoolean(7, shouldFreeze, false)
            builder.addOffset(8, hotspotsOffset, 0)
            return builder.endTable()
        }

        fun createHotspotsVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val segmentId: String? get() = __string(__offset(4))
    val videoStartMs: Long get() = bb.getInt(__offset(6)).toLong().and(0xFFFFFFFFL)
    val videoEndMs: Long get() = bb.getInt(__offset(8)).toLong().and(0xFFFFFFFFL)
    val audioSourcePath: String? get() = __string(__offset(10))
    val audioDurationMs: Long get() = bb.getInt(__offset(12)).toLong().and(0xFFFFFFFFL)
    val originalText: String? get() = __string(__offset(14))
    val translatedText: String? get() = __string(__offset(16))
    val shouldFreeze: Boolean get() = 0.toByte() != bb.get(__offset(18))
    fun hotspots(j: Int): Hotspot? {
        val o = __offset(20)
        if (o == 0) return null
        val obj = Hotspot()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val hotspotsLength: Int get() { val o = __offset(20); return if (o != 0) __vector_len(o) else 0 }
}

// ─── LectureSession (Root Type) ───────────────────────────────────────────

class LectureSession : Table() {
    companion object {
        fun getRootAsLectureSession(bb: ByteBuffer): LectureSession {
            val obj = LectureSession()
            obj.__init(bb.getInt(bb.position()) + bb.position(), bb)
            return obj
        }

        fun createLectureSession(
            builder: FlatBufferBuilder,
            sessionIdOffset: Int,
            sourceLangOffset: Int,
            targetLangOffset: Int,
            courseTitleOffset: Int,
            timelineTracksOffset: Int,
            quizzesOffset: Int,
            knowledgeGraphOffset: Int,
            telemetryLedgerOffset: Int
        ): Int {
            builder.startTable(8)
            builder.addOffset(0, sessionIdOffset, 0)
            builder.addOffset(1, sourceLangOffset, 0)
            builder.addOffset(2, targetLangOffset, 0)
            builder.addOffset(3, courseTitleOffset, 0)
            builder.addOffset(4, timelineTracksOffset, 0)
            builder.addOffset(5, quizzesOffset, 0)
            builder.addOffset(6, knowledgeGraphOffset, 0)
            builder.addOffset(7, telemetryLedgerOffset, 0)
            return builder.endTable()
        }

        fun createTimelineTracksVector(builder: FlatBufferBuilder, data: IntArray): Int {
            builder.startVector(4, data.size, 4)
            for (i in data.indices.reversed()) builder.addOffset(data[i])
            return builder.endVector()
        }

        fun finishLectureSessionBuffer(builder: FlatBufferBuilder, rootTable: Int) {
            builder.finish(rootTable)
        }
    }

    fun __init(i: Int, bb: ByteBuffer) { __reset(i, bb) }

    val sessionId: String? get() = __string(__offset(4))
    val sourceLang: String? get() = __string(__offset(6))
    val targetLang: String? get() = __string(__offset(8))
    val courseTitle: String? get() = __string(__offset(10))

    fun timelineTracks(j: Int): TimeSegment? {
        val o = __offset(12)
        if (o == 0) return null
        val obj = TimeSegment()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val timelineTracksLength: Int get() { val o = __offset(12); return if (o != 0) __vector_len(o) else 0 }

    fun quizzes(j: Int): Quiz? {
        val o = __offset(14)
        if (o == 0) return null
        val obj = Quiz()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val quizzesLength: Int get() { val o = __offset(14); return if (o != 0) __vector_len(o) else 0 }

    fun knowledgeGraph(j: Int): GraphNode? {
        val o = __offset(16)
        if (o == 0) return null
        val obj = GraphNode()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val knowledgeGraphLength: Int get() { val o = __offset(16); return if (o != 0) __vector_len(o) else 0 }

    fun telemetryLedger(j: Int): TelemetryPayload? {
        val o = __offset(18)
        if (o == 0) return null
        val obj = TelemetryPayload()
        obj.__init(__indirect(__vector(o) + j * 4), bb)
        return obj
    }
    val telemetryLedgerLength: Int get() { val o = __offset(18); return if (o != 0) __vector_len(o) else 0 }
}
