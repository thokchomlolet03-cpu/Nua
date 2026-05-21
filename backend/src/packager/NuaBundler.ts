import * as flatbuffers from 'flatbuffers';
import * as fs from 'fs';
import { TranslationResult, GraphNodeData } from '../agents/TranslationAgent.js';

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
            const origTextOff = builder.createString(seg.originalText);
            const transTextOff = builder.createString(seg.translatedText);

            // TimeSegment table (9 fields)
            builder.startObject(9);
            builder.addFieldOffset(0, segIdOff, 0);           // segment_id
            builder.addFieldInt32(1, seg.videoStartMs, 0);     // video_start_ms
            builder.addFieldInt32(2, seg.videoEndMs, 0);       // video_end_ms
            builder.addFieldOffset(3, audioPathOff, 0);        // audio_source_path
            builder.addFieldInt32(4, seg.audioDurationMs, 0);  // audio_duration_ms
            builder.addFieldOffset(5, origTextOff, 0);         // original_text
            builder.addFieldOffset(6, transTextOff, 0);        // translated_text
            builder.addFieldInt8(7, seg.audioDurationMs > (seg.videoEndMs - seg.videoStartMs) ? 1 : 0, 0); // should_freeze
            builder.addFieldOffset(8, 0, 0);                   // hotspots (empty)
            return builder.endObject();
        });

        // Build segments vector
        builder.startVector(4, segmentOffsets.length, 4);
        for (let i = segmentOffsets.length - 1; i >= 0; i--) {
            builder.addOffset(segmentOffsets[i]);
        }
        const segmentsVector = builder.endVector();

        // Build knowledge graph nodes
        const graphOffsets = knowledgeGraph.map(node => {
            const nodeIdOff = builder.createString(node.nodeId);
            const factoidOff = builder.createString(node.summaryFactoid);

            // Keywords vector
            const kwOffsets = node.keywords.map(kw => builder.createString(kw));
            builder.startVector(4, kwOffsets.length, 4);
            for (let i = kwOffsets.length - 1; i >= 0; i--) {
                builder.addOffset(kwOffsets[i]);
            }
            const kwVector = builder.endVector();

            // Context tokens vector
            const ctxOffsets = node.contextTokens.map(ct => builder.createString(ct));
            builder.startVector(4, ctxOffsets.length, 4);
            for (let i = ctxOffsets.length - 1; i >= 0; i--) {
                builder.addOffset(ctxOffsets[i]);
            }
            const ctxVector = builder.endVector();

            // GraphNode table (4 fields)
            builder.startObject(4);
            builder.addFieldOffset(0, nodeIdOff, 0);     // node_id
            builder.addFieldOffset(1, kwVector, 0);       // keywords
            builder.addFieldOffset(2, factoidOff, 0);     // summary_factoid
            builder.addFieldOffset(3, ctxVector, 0);      // context_tokens
            return builder.endObject();
        });

        // Graph vector
        builder.startVector(4, graphOffsets.length, 4);
        for (let i = graphOffsets.length - 1; i >= 0; i--) {
            builder.addOffset(graphOffsets[i]);
        }
        const graphVector = builder.endVector();

        // Build root LectureSession
        const sessionIdOff = builder.createString(`session_${Date.now()}`);
        const sourceLangOff = builder.createString(result.sourceLang);
        const targetLangOff = builder.createString(result.targetLang);
        const titleOff = builder.createString(result.courseTitle);

        builder.startObject(8);
        builder.addFieldOffset(0, sessionIdOff, 0);      // session_id
        builder.addFieldOffset(1, sourceLangOff, 0);     // source_lang
        builder.addFieldOffset(2, targetLangOff, 0);     // target_lang
        builder.addFieldOffset(3, titleOff, 0);          // course_title
        builder.addFieldOffset(4, segmentsVector, 0);    // timeline_tracks
        builder.addFieldOffset(5, 0, 0);                 // quizzes (empty)
        builder.addFieldOffset(6, graphVector, 0);       // knowledge_graph
        builder.addFieldOffset(7, 0, 0);                 // telemetry_ledger (empty)
        const root = builder.endObject();

        builder.finish(root);

        // Write binary to disk
        const buf = builder.asUint8Array();
        fs.writeFileSync(outputPath, Buffer.from(buf));
        console.log(`  📄 Serialized .nuab bundle: ${buf.length} bytes → ${outputPath}`);
    }
}
