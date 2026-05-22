import { GoogleGenAI } from '@google/genai';

export interface TranslationSegment {
    segmentId: string;
    videoStartMs: number;
    videoEndMs: number;
    originalText: string;
    translatedText: string;
    audioDurationMs: number;
}

export interface TranslationResult {
    segments: TranslationSegment[];
    sourceLang: string;
    targetLang: string;
    courseTitle: string;
}

export interface GraphNodeData {
    nodeId: string;
    keywords: string[];
    summaryFactoid: string;
    contextTokens: string[];
}

/**
 * Multi-turn translation agent using Gemini 3.5 Flash.
 * 
 * Uses the official @google/genai SDK (not the deprecated @google/generative-ai).
 * Implements context-aware, curriculum-grounded lecture translation with
 * technical term preservation and Hinglish output.
 */
export class TranslationAgent {
    private genAI: GoogleGenAI | null = null;

    constructor(apiKey: string) {
        if (apiKey) {
            this.genAI = new GoogleGenAI({ apiKey });
        }
    }

    private async withRetry<T>(operation: () => Promise<T>, maxRetries = 3): Promise<T> {
        let attempt = 0;
        let delay = 1000;
        while (attempt < maxRetries) {
            try {
                return await operation();
            } catch (error: any) {
                attempt++;
                if (attempt >= maxRetries) throw error;
                console.warn(`GenAI API call failed (attempt ${attempt}/${maxRetries}): ${error.message}. Retrying in ${delay}ms...`);
                await new Promise(resolve => setTimeout(resolve, delay));
                delay *= 2;
            }
        }
        throw new Error('Retry loop failed');
    }

    /**
     * Translates a lecture audio file using Gemini 3.5 Flash.
     * Produces timestamped, translated segments.
     */
    async translateLecture(
        wavPath: string,
        targetLanguage: string,
        courseContextDocs: string
    ): Promise<TranslationResult> {
        if (!this.genAI) {
            throw new Error('Gemini API key not configured');
        }

        const systemPrompt = `You are a lecture translation assistant.
Analyze the provided lecture audio and produce a JSON array of timestamped segments.
For each segment, provide:
- segmentId: unique identifier
- videoStartMs: start time in milliseconds
- videoEndMs: end time in milliseconds
- originalText: the English transcription
- translatedText: translation to ${targetLanguage} (Hinglish: use Devanagari for Hindi structure, keep scientific terms in English)
- audioDurationMs: estimated spoken duration of the translation

${courseContextDocs ? `Supporting course materials for context:\n${courseContextDocs}` : ''}

Output ONLY valid JSON. No explanations.`;

        const model = this.genAI.models;
        let uploadResult: any = null;
        let response;
        try {
            uploadResult = await this.withRetry(() => this.genAI!.files.upload({ file: wavPath, config: { mimeType: 'audio/wav' } }));
            
            response = await this.withRetry(() => model.generateContent({
                model: 'gemini-3.5-flash',
                contents: [uploadResult, systemPrompt]
            }));
        } catch (e: any) {
            if (uploadResult && uploadResult.name) {
                try {
                    await this.genAI.files.delete({ name: uploadResult.name });
                } catch (delError) {
                    // Ignore cleanup error
                }
            }
            throw new Error(`Failed to generate content: ${e.message}`);
        }

        try {
            const text = response.text || '';
            // Extract JSON from response robustly
            const firstBracket = text.indexOf('[');
            const lastBracket = text.lastIndexOf(']');
            if (firstBracket === -1 || lastBracket === -1 || lastBracket < firstBracket) {
                throw new Error('Failed to extract JSON array from Gemini response');
            }
            const jsonStr = text.substring(firstBracket, lastBracket + 1);
            const segments: TranslationSegment[] = JSON.parse(jsonStr);

            return {
                segments,
                sourceLang: 'en',
                targetLang: targetLanguage,
                courseTitle: 'Lecture Translation'
            };
        } catch (e: any) {
            throw new Error(`Failed to parse Gemini response: ${e.message}`);
        } finally {
            if (uploadResult && uploadResult.name) {
                try {
                    await this.genAI.files.delete({ name: uploadResult.name });
                } catch (e) {
                    console.error("Failed to delete file from GenAI:", e);
                }
            }
        }
    }

    /**
     * Pre-bakes a hierarchical knowledge graph from translated segments.
     * These are stored in the .nuab bundle for offline RAG on the client.
     */
    async compileKnowledgeGraph(segments: TranslationSegment[]): Promise<GraphNodeData[]> {
        if (!this.genAI) {
            throw new Error('Gemini API key not configured');
        }

        const segmentTexts = segments.map(s => s.originalText).join('\n');

        const prompt = `Analyze the following lecture transcript and generate a knowledge graph as a JSON array.
Each node should have:
- nodeId: unique identifier (e.g., "concept_1")
- keywords: array of relevant search keywords
- summaryFactoid: a single concise fact that summarizes this concept
- contextTokens: array of related terms for semantic matching

Transcript:
${segmentTexts}

Output ONLY valid JSON array. No explanations.`;

        let response;
        try {
            response = await this.withRetry(() => this.genAI!.models.generateContent({
                model: 'gemini-3.5-flash',
                contents: prompt
            }));
        } catch (e: any) {
            console.error('Failed to generate knowledge graph:', e.message);
            return [];
        }

        try {
            const text = response.text || '';
            const firstBracket = text.indexOf('[');
            const lastBracket = text.lastIndexOf(']');
            if (firstBracket === -1 || lastBracket === -1 || lastBracket < firstBracket) return [];
            return JSON.parse(text.substring(firstBracket, lastBracket + 1));
        } catch {
            return [];
        }
    }

    // ─── Mock implementations ───────────────────────────────────────────

    mockTranslate(targetLanguage: string): TranslationResult {
        return {
            segments: [
                {
                    segmentId: 'seg_0',
                    videoStartMs: 0,
                    videoEndMs: 5000,
                    originalText: 'Welcome to today\'s lecture on photosynthesis.',
                    translatedText: 'Photosynthesis पर आज की lecture में आपका स्वागत है।',
                    audioDurationMs: 6200
                },
                {
                    segmentId: 'seg_1',
                    videoStartMs: 5000,
                    videoEndMs: 12000,
                    originalText: 'Photosynthesis is a chemical process by which plants convert sunlight into energy.',
                    translatedText: 'Photosynthesis एक chemical process है जिसके द्वारा plants sunlight को energy में convert करते हैं।',
                    audioDurationMs: 9500
                },
                {
                    segmentId: 'seg_2',
                    videoStartMs: 12000,
                    videoEndMs: 18000,
                    originalText: 'This process occurs primarily in the chloroplasts of plant cells.',
                    translatedText: 'यह process mainly plant cells के chloroplasts में होता है।',
                    audioDurationMs: 7000
                }
            ],
            sourceLang: 'en',
            targetLang: targetLanguage,
            courseTitle: 'Mock Lecture — Photosynthesis'
        };
    }

    mockKnowledgeGraph(): GraphNodeData[] {
        return [
            {
                nodeId: 'concept_photosynthesis',
                keywords: ['photosynthesis', 'light', 'energy', 'plants', 'chlorophyll'],
                summaryFactoid: 'Photosynthesis converts light energy into chemical energy stored in glucose, using CO2 and H2O.',
                contextTokens: ['6CO2', '6H2O', 'C6H12O6', 'chloroplast', 'thylakoid']
            },
            {
                nodeId: 'concept_chloroplast',
                keywords: ['chloroplast', 'cell', 'organelle', 'plant'],
                summaryFactoid: 'Chloroplasts are double-membrane organelles in plant cells where photosynthesis occurs.',
                contextTokens: ['stroma', 'granum', 'thylakoid membrane', 'pigment']
            }
        ];
    }
}
