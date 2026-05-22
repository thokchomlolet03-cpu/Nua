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

            const directiveOff = builder.createString("NORMAL_SYNC");

            // using generated builder
            const shouldFreeze = seg.audioDurationMs > (seg.videoEndMs - seg.videoStartMs);
            return TimeSegment.createTimeSegment(
                builder,
                segIdOff,
                seg.videoStartMs,
                seg.videoEndMs,
                audioPathOff,
                seg.audioDurationMs,
                origTextOff,
                transTextOff,
                shouldFreeze,
                0, // hotspots (empty)
                directiveOff
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
        const titleOff = builder.createString(result.courseTitle);

        const root = LectureSession.createLectureSession(
            builder,
            sessionIdOff,
            sourceLangOff,
            targetLangOff,
            titleOff,
            segmentsVector,
            0, // quizzes
            graphVector,
            0  // telemetry_ledger
        );

        builder.finish(root);

        // Write binary to disk
        const buf = builder.asUint8Array();
        fs.writeFileSync(outputPath, buf);
        console.log(`  📄 Serialized .nuab bundle: ${buf.length} bytes → ${outputPath}`);
    }
}
