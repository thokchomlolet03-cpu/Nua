import * as flatbuffers from 'flatbuffers';
import * as fs from 'fs';
import { TranslationResult, GraphNodeData } from '../agents/TranslationAgent.js';
import { LectureSession, TimeSegment, GraphNode } from '../schema/nua-serialization.js';

/**
 * Serializes translation results and knowledge graphs into FlatBuffers
 * .nuab binary format for efficient mobile consumption.
 *
 * Uses the FlatBuffers builder API to produce zero-copy-ready binary
 * payloads that the Android client can memory-map directly.
 */
export class NuaBundler {

    /**
     * Serializes a translation result + knowledge graph into a .nuab file.
     */
    serialize(
        result: TranslationResult,
        knowledgeGraph: GraphNodeData[],
        targetLanguage: string,
        outputPath: string
    ): void {
        const builder = new flatbuffers.Builder(4096);

        // Build timeline segments
        const segmentOffsets = result.segments.map(seg => {
            const segIdOff = builder.createString(seg.segmentId);
            const audioPathOff = builder.createString(`vocal_chunks/vocal_${seg.videoStartMs}_${seg.videoEndMs}.wav`);
            const origTextOff = builder.createString(seg.originalText || "");
            const transTextOff = builder.createString(seg.translatedText || "");

            // Determine directive based on duration comparison
            const directive = seg.audioDurationMs > (seg.videoEndMs - seg.videoStartMs)
                ? "FREEZE_HOLD" : "NORMAL_SYNC";
            const directiveOff = builder.createString(directive);

            return TimeSegment.createTimeSegment(
                builder,
                segIdOff,
                seg.videoStartMs,
                seg.videoEndMs,
                audioPathOff,
                seg.audioDurationMs,
                origTextOff,
                transTextOff,
                directiveOff,
                0 // hotspots (empty)
            );
        });

        // Build segments vector
        const segmentsVector = LectureSession.createTimelineTracksVector(builder, segmentOffsets);

        // Build knowledge graph nodes
        const graphOffsets = knowledgeGraph.map(node => {
            const nodeIdOff = builder.createString(node.nodeId);
            const factoidOff = builder.createString(node.summaryFactoid);

            const safeKeywords = node.keywords || [];
            const kwOffsets = safeKeywords.map(kw => builder.createString(kw));
            const kwVector = GraphNode.createKeywordsVector(builder, kwOffsets);

            const safeContextTokens = node.contextTokens || [];
            const ctxOffsets = safeContextTokens.map(ct => builder.createString(ct));
            const ctxVector = GraphNode.createContextTokensVector(builder, ctxOffsets);

            return GraphNode.createGraphNode(
                builder,
                nodeIdOff,
                kwVector,
                factoidOff,
                ctxVector
            );
        });

        // Graph vector
        const graphVector = LectureSession.createKnowledgeGraphVector(builder, graphOffsets);

        // Build root LectureSession
        const sessionIdOff = builder.createString(`session_${Date.now()}`);
        const sourceLangOff = builder.createString(result.sourceLang);
        const targetLangOff = builder.createString(result.targetLang);
        const sourceVideoPathOff = builder.createString(result.courseTitle || 'unknown');

        LectureSession.startLectureSession(builder);
        LectureSession.addSchemaVersion(builder, 1);
        LectureSession.addSessionId(builder, sessionIdOff);
        LectureSession.addSourceLang(builder, sourceLangOff);
        LectureSession.addTargetLang(builder, targetLangOff);
        LectureSession.addSourceVideoPath(builder, sourceVideoPathOff);
        LectureSession.addTimelineTracks(builder, segmentsVector);
        LectureSession.addKnowledgeGraph(builder, graphVector);
        const root = LectureSession.endLectureSession(builder);

        builder.finish(root, 'NUAB');

        // Write binary to disk
        const buf = builder.asUint8Array();
        fs.writeFileSync(outputPath, buf);
        console.log(`  📄 Serialized .nuab bundle: ${buf.length} bytes → ${outputPath}`);
    }
}
