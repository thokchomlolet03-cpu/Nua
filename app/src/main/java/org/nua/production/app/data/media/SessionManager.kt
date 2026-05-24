package org.nua.production.app.data.media

import android.content.Context
import android.util.Log
import com.google.flatbuffers.FlatBufferBuilder
import org.nua.production.app.data.schema.LectureSession
import org.nua.production.app.data.schema.TimeSegment
import org.nua.production.app.data.schema.Hotspot
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages session lifecycle: creation, persistence (FlatBuffers .nuab),
 * discovery, deletion, and one-time legacy JSON migration.
 */
class SessionManager(private val filesDir: File) {

    companion object {
        private const val TAG = "SessionManager"
        private const val MANIFEST_BINARY = "manifest.nuab"
        private const val MANIFEST_JSON_LEGACY = "manifest.json"
    }

    private val legacyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // ─── Directory management ─────────────────────────────────────────────

    fun getSessionsRootDir(): File {
        val dir = File(filesDir, "sessions")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSessionDir(videoName: String): File {
        val sanitized = videoName.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9_]"), "_")
        val timestamp = System.currentTimeMillis()
        val dir = File(getSessionsRootDir(), "session_${sanitized}_$timestamp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getVocalChunksDir(sessionDir: File): File {
        val dir = File(sessionDir, "vocal_chunks")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─── FlatBuffers binary persistence (.nuab) ───────────────────────────

    /**
     * Saves a compiled session as a FlatBuffers .nuab binary.
     */
    fun saveManifest(sessionDir: File, composition: MediaComposition) {
        val builder = FlatBufferBuilder(1024)

        // Serialize each segment
        val segmentOffsets = composition.segments.map { seg ->
            val segIdOff = builder.createString(seg.segmentId ?: "seg_${seg.startMs}")
            val audioPathOff = builder.createString(seg.vocalAssetLocalPath)
            val origTextOff = builder.createString(seg.originalText)
            val transTextOff = builder.createString(seg.translatedText)

            // B2 fix: Properly serialize hotspots instead of passing 0
            val hotspotsOffsetList = seg.hotspots.map { hs ->
                val tokenOff = builder.createString(hs.token)
                val defOff = builder.createString(hs.conceptDefinition)
                Hotspot.createHotspot(builder, tokenOff, defOff)
            }.toIntArray()
            val hotspotsVectorOff = if (hotspotsOffsetList.isNotEmpty()) {
                TimeSegment.createHotspotsVector(builder, hotspotsOffsetList)
            } else {
                0
            }

            // B5 fix: Serialize directive string for lossless round-trip
            val directiveOff = builder.createString(seg.directive)

            TimeSegment.createTimeSegment(
                builder,
                segmentIdOffset = segIdOff,
                videoStartMs = seg.startMs,
                videoEndMs = seg.endMs,
                audioSourcePathOffset = audioPathOff,
                audioDurationMs = seg.audioDurationMs ?: (seg.endMs - seg.startMs),
                originalTextOffset = origTextOff,
                translatedTextOffset = transTextOff,
                directiveOffset = directiveOff,
                hotspotsVectorOffset = hotspotsVectorOff
            )
        }.toIntArray()

        val tracksVector = LectureSession.createTimelineTracksVector(builder, segmentOffsets)
        
        val quizzesOffsets = composition.quizzes.map { q ->
            val questionOff = builder.createString(q.question)
            val optionsOffs = q.options.map { builder.createString(it) }.toIntArray()
            val optionsVector = org.nua.production.app.data.schema.Quiz.createOptionsVector(builder, optionsOffs)
            
            org.nua.production.app.data.schema.Quiz.createQuiz(
                builder,
                q.triggerTimestampMs,
                questionOff,
                optionsVector,
                q.correctIndex.toUByte()
            )
        }.toIntArray()
        val quizzesVector = LectureSession.createQuizzesVector(builder, quizzesOffsets)

        val kgOffsets = composition.knowledgeGraph.map { k ->
            val nodeIdOff = builder.createString(k.nodeId)
            val summaryOff = builder.createString(k.summaryFactoid)
            val kwOffs = k.keywords.map { builder.createString(it) }.toIntArray()
            val kwVector = org.nua.production.app.data.schema.GraphNode.createKeywordsVector(builder, kwOffs)
            val ctxOffs = k.contextTokens.map { builder.createString(it) }.toIntArray()
            val ctxVector = org.nua.production.app.data.schema.GraphNode.createContextTokensVector(builder, ctxOffs)
            val idfTokensVector = org.nua.production.app.data.schema.GraphNode.createIdfTokensVector(builder, intArrayOf())
            org.nua.production.app.data.schema.GraphNode.startIdfValuesVector(builder, 0)
            val idfValuesVector = builder.endVector()

            org.nua.production.app.data.schema.GraphNode.createGraphNode(
                builder,
                nodeIdOff,
                kwVector,
                summaryOff,
                ctxVector,
                idfTokensVector,
                idfValuesVector
            )
        }.toIntArray()
        val kgVector = LectureSession.createKnowledgeGraphVector(builder, kgOffsets)

        val sessionIdOff = builder.createString(composition.videoId)
        val sourceVideoPathOff = builder.createString(composition.sourceVideoPath)
        val sourceLangOff = builder.createString("en")
        val targetLangOff = builder.createString("hi")
        val sessionIdOffset = sessionIdOff
        val sourceVideoOff = sourceVideoPathOff

        val root = LectureSession.createLectureSession(
            builder,
            schemaVersion = 1u,
            sessionIdOffset = sessionIdOff,
            sourceLangOffset = sourceLangOff,
            targetLangOffset = targetLangOff,
            sourceVideoPathOffset = sourceVideoOff,
            timelineTracksVectorOffset = tracksVector,
            quizzesVectorOffset = quizzesVector,
            knowledgeGraphVectorOffset = kgVector
        )
        LectureSession.finishLectureSessionBuffer(builder, root)

        val file = File(sessionDir, MANIFEST_BINARY)
        file.writeBytes(builder.sizedByteArray())
        Log.d(TAG, "Saved .nuab manifest (${file.length()} bytes) to ${file.absolutePath}")
    }

    /**
     * Loads a session from a FlatBuffers .nuab binary using memory-mapped I/O.
     * Returns null if the file doesn't exist or is corrupt.
     */
    fun loadManifestBinary(sessionDir: File): LectureSession? {
        val file = File(sessionDir, MANIFEST_BINARY)
        if (!file.exists() || file.length() < 4) return null

        return try {
            // B6 fix: Removed dead RandomAccessFile/FileChannel wrappers
            val mappedBuffer = ByteBuffer.wrap(file.readBytes())
            mappedBuffer.order(ByteOrder.LITTLE_ENDIAN)
            LectureSession.getRootAsLectureSession(mappedBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load .nuab manifest", e)
            null
        }
    }

    /**
     * Loads a session manifest, trying .nuab first, then legacy .json with migration.
     */
    fun loadManifest(sessionDir: File): MediaComposition? {
        // Try binary format first
        val binary = loadManifestBinary(sessionDir)
        if (binary != null) {
            return binaryToComposition(binary)
        }

        // Fall back to legacy JSON
        val jsonFile = File(sessionDir, MANIFEST_JSON_LEGACY)
        if (!jsonFile.exists()) return null

        return try {
            val composition = legacyJson.decodeFromString(
                MediaComposition.serializer(), jsonFile.readText()
            )
            // One-time migration: serialize to .nuab and purge legacy JSON
            migrateJsonToNuab(sessionDir, composition, jsonFile)
            composition
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load legacy JSON manifest", e)
            null
        }
    }

    // ─── Legacy migration ─────────────────────────────────────────────────

    /**
     * One-time migration: converts a legacy JSON manifest to .nuab binary
     * and deletes the original JSON file to eliminate technical debt.
     */
    private fun migrateJsonToNuab(sessionDir: File, composition: MediaComposition, jsonFile: File) {
        try {
            saveManifest(sessionDir, composition)
            jsonFile.delete()
            Log.i(TAG, "Migrated legacy JSON → .nuab for ${sessionDir.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Migration failed for ${sessionDir.name}, keeping JSON", e)
        }
    }

    /**
     * Converts a FlatBuffers LectureSession back to a MediaComposition
     * for backward compatibility with existing ViewModel/player code.
     */
    private fun binaryToComposition(session: LectureSession): MediaComposition {
        val segments = (0 until session.timelineTracksLength).map { i ->
            val seg = session.timelineTracks(i)!!
            val hotspotsList = (0 until seg.hotspotsLength).map { j ->
                val hs = seg.hotspots(j)!!
                HotspotInfo(
                    token = hs.token ?: "",
                    conceptDefinition = hs.conceptDefinition ?: ""
                )
            }
            PlaybackSegment(
                startMs = seg.videoStartMs,
                endMs = seg.videoEndMs,
                originalText = seg.originalText ?: "",
                translatedText = seg.translatedText ?: "",
                vocalAssetLocalPath = seg.audioSourcePath ?: "",
                directive = seg.directive?.takeIf { it.isNotEmpty() } ?: PlaybackDirective.NORMAL_SYNC,
                segmentId = seg.segmentId,
                audioDurationMs = seg.audioDurationMs,
                hotspots = hotspotsList
            )
        }

        val quizzesList = (0 until session.quizzesLength).map { i ->
            val q = session.quizzes(i)!!
            val optionsList = (0 until q.optionsLength).map { j ->
                q.options(j) ?: ""
            }
            QuizInfo(
                triggerTimestampMs = q.triggerTimestampMs,
                question = q.question ?: "",
                options = optionsList,
                correctIndex = q.correctIndex.toInt()
            )
        }

        val kgList = (0 until session.knowledgeGraphLength).map { i ->
            val k = session.knowledgeGraph(i)!!
            val kwList = (0 until k.keywordsLength).map { j -> k.keywords(j) ?: "" }
            val ctxList = (0 until k.contextTokensLength).map { j -> k.contextTokens(j) ?: "" }
            GraphNodeInfo(
                nodeId = k.nodeId ?: "",
                keywords = kwList,
                summaryFactoid = k.summaryFactoid ?: "",
                contextTokens = ctxList
            )
        }

        return MediaComposition(
            videoId = session.sessionId ?: "",
            sourceVideoPath = session.sourceVideoPath ?: @Suppress("DEPRECATION") session.courseTitle ?: "raw_lecture.mp4",
            segments = segments,
            quizzes = quizzesList,
            knowledgeGraph = kgList
        )
    }

    // ─── Session discovery ────────────────────────────────────────────────

    fun listCompletedSessions(): List<File> {
        val root = getSessionsRootDir()
        val dirs = root.listFiles { file ->
            file.isDirectory && file.name.startsWith("session_") &&
                (File(file, MANIFEST_BINARY).exists() || File(file, MANIFEST_JSON_LEGACY).exists())
        }
        return dirs?.sortedByDescending {
            val nuab = File(it, MANIFEST_BINARY)
            val json = File(it, MANIFEST_JSON_LEGACY)
            when {
                nuab.exists() -> nuab.lastModified()
                json.exists() -> json.lastModified()
                else -> it.lastModified()
            }
        } ?: emptyList()
    }

    fun deleteSession(sessionDir: File): Boolean {
        return sessionDir.deleteRecursively()
    }
}
