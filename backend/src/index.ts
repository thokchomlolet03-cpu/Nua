import express from 'express';
import rateLimit from 'express-rate-limit';
import crypto from 'crypto';
import { Storage } from '@google-cloud/storage';
import { TranslationAgent } from './agents/TranslationAgent.js';
import { NuaBundler } from './packager/NuaBundler.js';
import { extractAudioChannel } from './utils/audio.js';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';

// ─── Configuration ─────────────────────────────────────────────────────

const PORT = parseInt(process.env.PORT || '8080');
const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const GCS_BUCKET = process.env.GCS_BUCKET || 'nua-cdn-distribution';
const IS_MOCK_MODE = !GEMINI_API_KEY;

if (IS_MOCK_MODE) {
    console.warn('⚠️  GEMINI_API_KEY not set — running in MOCK SANDBOX MODE');
    console.warn('   Set GEMINI_API_KEY environment variable for production.');
}

// ─── Services ──────────────────────────────────────────────────────────

const app = express();
app.use(express.json({
    limit: '50mb',
    verify: (req: any, _res, buf) => {
        req.rawBody = buf;
    }
}));

let storage: Storage | null = null;
try {
    storage = new Storage();
} catch (e) {
    console.warn('⚠️  Google Cloud Storage not configured — uploads will be skipped');
}

const translationAgent = new TranslationAgent(GEMINI_API_KEY || '');
const bundler = new NuaBundler();

// ─── Security Middleware ───────────────────────────────────────────────

const ingestionLimiter = rateLimit({
    windowMs: 15 * 60 * 1000, // 15 minute window
    max: 5,                   // Strict request limits per IP
    message: { error: 'Excessive requests generated from this origin point' }
});

const verifyHmacSignature = (req: any, res: any, next: any) => {
    const signature = req.headers['x-nua-signature'];
    if (!signature) return res.status(401).json({ error: 'Missing security signature metadata' });

    const signingSecret = process.env.SIGNING_SECRET;
    if (!signingSecret) {
        console.error('❌ SIGNING_SECRET is not configured on the server.');
        return res.status(500).json({ error: 'Server misconfiguration: SIGNING_SECRET is not configured' });
    }

    if (!req.rawBody) {
        return res.status(400).json({ error: 'Missing request body for signature verification' });
    }

    const computedHmac = crypto.createHmac('sha256', signingSecret)
                               .update(req.rawBody)
                               .digest('hex');

    try {
        const signatureBuffer = Buffer.from(signature, 'hex');
        const computedHmacBuffer = Buffer.from(computedHmac, 'hex');

        if (signatureBuffer.length !== computedHmacBuffer.length ||
            !crypto.timingSafeEqual(signatureBuffer, computedHmacBuffer)) {
            return res.status(403).json({ error: 'Access signature verification failure' });
        }
    } catch (e) {
        return res.status(403).json({ error: 'Access signature verification failure' });
    }
    next();
};

// ─── Health Check ──────────────────────────────────────────────────────

app.get('/health', (_req, res) => {
    res.json({
        status: 'OK',
        mode: IS_MOCK_MODE ? 'MOCK' : 'PRODUCTION',
        version: '1.0.0'
    });
});

// ─── Main Ingestion Endpoint ───────────────────────────────────────────

app.post('/api/v1/ingest', ingestionLimiter, verifyHmacSignature, async (req, res) => {
    let workDir: string | null = null;

    try {
        if (!req.body) throw new Error("Request body is missing");
        const { videoUrl, targetLanguage = 'hi', courseContextDocs = '' } = req.body;

        if (typeof videoUrl !== 'string' || !videoUrl.startsWith('http')) {
            return res.status(400).json({ status: 'ERROR', message: 'videoUrl must be a string starting with http' });
        }
        if (typeof targetLanguage !== 'string') {
            return res.status(400).json({ status: 'ERROR', message: 'targetLanguage must be a string' });
        }

        workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nua-'));
        console.log(`📦 Processing: ${videoUrl} → ${targetLanguage} (workDir: ${workDir})`);
        // Step 1: Extract audio from video
        console.log('  Step 1/5: Extracting audio...');
        const wavPath = path.join(workDir, 'extracted_audio.wav');
        await extractAudioChannel(videoUrl, wavPath);

        // Step 2: Transcribe + translate via Gemini 3.5 Flash (or mock)
        console.log('  Step 2/5: Translating with Gemini 3.5 Flash...');
        const translationResult = IS_MOCK_MODE
            ? translationAgent.mockTranslate(targetLanguage)
            : await translationAgent.translateLecture(wavPath, targetLanguage, courseContextDocs);

        // Step 3: Pre-bake knowledge graph (cognitive decision trees)
        console.log('  Step 3/5: Pre-baking knowledge graph...');
        const knowledgeGraph = IS_MOCK_MODE
            ? translationAgent.mockKnowledgeGraph()
            : await translationAgent.compileKnowledgeGraph(translationResult.segments);

        // Step 4: Serialize to FlatBuffers binary
        console.log('  Step 4/5: Serializing to .nuab binary...');
        const nuabPath = path.join(workDir, 'session.nuab');
        bundler.serialize(translationResult, knowledgeGraph, targetLanguage, nuabPath);

        // Step 5: Upload to Cloud CDN (if configured)
        let cdnUrl = `file://${nuabPath}`;
        if (storage && !IS_MOCK_MODE) {
            console.log('  Step 5/5: Uploading to Cloud CDN...');
            const destFilename = `packages/${path.basename(workDir)}.nuab`;
            await storage.bucket(GCS_BUCKET).upload(nuabPath, { destination: destFilename });
            cdnUrl = `https://storage.googleapis.com/${GCS_BUCKET}/${destFilename}`;
        } else {
            console.log('  Step 5/5: Skipping upload (mock mode or no GCS)');
        }

        console.log(`✅ Ingestion complete: ${cdnUrl}`);
        return res.status(200).json({
            status: 'SUCCESS',
            cdnUrl,
            segmentCount: translationResult.segments.length,
            graphNodeCount: knowledgeGraph.length,
            mode: IS_MOCK_MODE ? 'MOCK' : 'PRODUCTION'
        });

    } catch (error: any) {
        console.error('❌ Ingestion failed:', error);
        return res.status(500).json({
            status: 'ERROR',
            message: error.message || 'Unknown error'
        });
    } finally {
        // Cleanup work directory
        if (workDir) {
            try { fs.rmSync(workDir, { recursive: true, force: true }); } catch (_) {}
        }
    }
});

// ─── Start Server ──────────────────────────────────────────────────────

app.listen(PORT, () => {
    console.log(`🚀 Nua Web Studio listening on port ${PORT}`);
    console.log(`   Mode: ${IS_MOCK_MODE ? '🟡 MOCK SANDBOX' : '🟢 PRODUCTION'}`);
});
