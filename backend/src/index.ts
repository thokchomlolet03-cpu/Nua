import express from 'express';
import rateLimit from 'express-rate-limit';
import crypto from 'crypto';
import { Storage } from '@google-cloud/storage';
import { TranslationAgent } from './agents/TranslationAgent.js';
import { NuaBundler } from './packager/NuaBundler.js';
import { FastMediaQueue } from './utils/queue-mediator.js';
import { extractAudioChannel } from './utils/audio.js';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { Firestore } from '@google-cloud/firestore';

// ─── Database ──────────────────────────────────────────────────────────

const db = new Firestore();
async function initDb() {
    try {
        const tenantsSnapshot = await db.collection('tenants').limit(1).get();
        if (tenantsSnapshot.empty) {
            await db.collection('tenants').doc('test-tenant-id').set({
                id: 'test-tenant-id',
                name: 'Mock Institute',
                api_key: 'test-tenant-key',
                credits_remaining: 50
            });
        }
    } catch (err) {
        console.error('DB Init Error:', err);
    }
}
initDb();

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

// Handle malformed JSON
app.use((err: any, req: any, res: any, next: any) => {
    if (err instanceof SyntaxError && 'body' in err) {
        return res.status(400).json({ error: 'Malformed JSON payload' });
    }
    next(err);
});

let storage: Storage | null = null;
try {
    storage = new Storage();
} catch (e) {
    console.warn('⚠️  Google Cloud Storage not configured — uploads will be skipped');
}

const translationAgent = new TranslationAgent(GEMINI_API_KEY || '');
const bundler = new NuaBundler();
const mediaIngestionQueue = new FastMediaQueue(
    { maxConcurrentWorkers: 3 },
    translationAgent,
    bundler,
    db,
    storage,
    IS_MOCK_MODE,
    GCS_BUCKET
);

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

const verifyTenantLicense = async (req: any, res: any, next: any) => {
    const apiKey = req.headers['x-api-key'];
    if (!apiKey || typeof apiKey !== 'string') return res.status(401).json({ error: 'Missing or invalid x-api-key header for Enterprise licensing' });

    try {
        const snapshot = await db.collection('tenants').where('api_key', '==', apiKey).limit(1).get();
        if (snapshot.empty) return res.status(403).json({ error: 'Invalid organization API key' });
        
        const doc = snapshot.docs[0];
        const row = doc.data();
        if (row.credits_remaining <= 0) return res.status(402).json({ error: 'Organization has exhausted processing credits' });
        
        req.tenant = { ...row, _docId: doc.id };
        next();
    } catch (e) {
        return res.status(500).json({ error: 'Database error verifying license' });
    }
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

app.post('/api/v1/ingest', ingestionLimiter, verifyHmacSignature, verifyTenantLicense, async (req: any, res: any) => {
    try {
        if (!req.body) throw new Error("Request body is missing");
        const { id, videoUrl, targetLanguage = 'hi', courseContextDocs = '' } = req.body;

        if (typeof videoUrl !== 'string' || !videoUrl.startsWith('http')) {
            return res.status(400).json({ status: 'ERROR', message: 'videoUrl must be a string starting with http' });
        }
        if (typeof targetLanguage !== 'string') {
            return res.status(400).json({ status: 'ERROR', message: 'targetLanguage must be a string' });
        }

        const lectureId = id || `req-${Date.now()}`;
        const assetProcessingTask = {
            lectureId,
            targetSourceUrl: videoUrl,
            targetLanguage,
            courseContextDocs,
            tenant: req.tenant,
            timestamp: Date.now()
        };

        // Pushes work payloads out to the queue mediator, freeing up the network thread instantly
        mediaIngestionQueue.enqueue(assetProcessingTask);
        return res.status(202).json({ status: 'Processing task enqueued successfully' });

    } catch (error: any) {
        console.error('❌ Ingestion enqueue failed:', error);
        return res.status(500).json({
            status: 'ERROR',
            message: error.message || 'Unknown error'
        });
    }
});

// ─── Start Server ──────────────────────────────────────────────────────

app.listen(PORT, () => {
    console.log(`🚀 Nua Web Studio listening on port ${PORT}`);
    console.log(`   Mode: ${IS_MOCK_MODE ? '🟡 MOCK SANDBOX' : '🟢 PRODUCTION'}`);
});
