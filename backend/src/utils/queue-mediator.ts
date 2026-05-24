import { TranslationAgent } from '../agents/TranslationAgent.js';
import { NuaBundler } from '../packager/NuaBundler.js';
import { extractAudioChannel } from './audio.js';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { Storage } from '@google-cloud/storage';
import sqlite3 from 'sqlite3';

export interface MediaTask {
    lectureId: string;
    targetSourceUrl: string;
    targetLanguage: string;
    courseContextDocs: string;
    tenant: any;
    timestamp: number;
}

export class FastMediaQueue {
    private queue: MediaTask[] = [];
    private activeWorkers = 0;
    private maxConcurrentWorkers: number;

    private translationAgent: TranslationAgent;
    private bundler: NuaBundler;
    private db: sqlite3.Database;
    private storage: Storage | null;
    private isMockMode: boolean;
    private gcsBucket: string;

    constructor(
        options: { maxConcurrentWorkers: number },
        translationAgent: TranslationAgent,
        bundler: NuaBundler,
        db: sqlite3.Database,
        storage: Storage | null,
        isMockMode: boolean,
        gcsBucket: string
    ) {
        this.maxConcurrentWorkers = options.maxConcurrentWorkers;
        this.translationAgent = translationAgent;
        this.bundler = bundler;
        this.db = db;
        this.storage = storage;
        this.isMockMode = isMockMode;
        this.gcsBucket = gcsBucket;
    }

    enqueue(task: MediaTask) {
        this.queue.push(task);
        this.processNext();
    }

    private async processNext() {
        if (this.activeWorkers >= this.maxConcurrentWorkers || this.queue.length === 0) {
            return;
        }

        this.activeWorkers++;
        const task = this.queue.shift()!;

        try {
            await this.processTask(task);
        } catch (error) {
            console.error(`[FastMediaQueue] Error processing task ${task.lectureId}:`, error);
        } finally {
            this.activeWorkers--;
            this.processNext();
        }
    }

    private async processTask(task: MediaTask) {
        const { lectureId, targetSourceUrl, targetLanguage, courseContextDocs, tenant } = task;
        console.log(`📦 [FastMediaQueue] Processing: ${targetSourceUrl} → ${targetLanguage}`);

        const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'nua-'));
        try {
            console.log('  Step 1/5: Extracting audio...');
            const wavPath = path.join(workDir, 'extracted_audio.wav');
            await extractAudioChannel(targetSourceUrl, wavPath);

            console.log('  Step 2/5: Translating with Gemini 3.5 Flash...');
            const translationResult = this.isMockMode
                ? this.translationAgent.mockTranslate(targetLanguage)
                : await this.translationAgent.translateLecture(wavPath, targetLanguage, courseContextDocs);

            console.log('  Step 3/5: Pre-baking knowledge graph...');
            const knowledgeGraph = this.isMockMode
                ? this.translationAgent.mockKnowledgeGraph()
                : await this.translationAgent.compileKnowledgeGraph(translationResult.segments);

            console.log('  Step 4/5: Serializing to .nuab binary...');
            const nuabPath = path.join(workDir, 'session.nuab');
            this.bundler.serialize(translationResult, knowledgeGraph, targetLanguage, nuabPath);

            let cdnUrl = `file://${nuabPath}`;
            if (this.storage && !this.isMockMode) {
                console.log('  Step 5/5: Uploading to Cloud CDN...');
                const destFilename = `packages/${path.basename(workDir)}.nuab`;
                await this.storage.bucket(this.gcsBucket).upload(nuabPath, { destination: destFilename });
                cdnUrl = `https://storage.googleapis.com/${this.gcsBucket}/${destFilename}`;
            } else {
                console.log('  Step 5/5: Skipping upload (mock mode or no GCS)');
            }

            this.db.run("UPDATE tenants SET credits_remaining = credits_remaining - 1 WHERE id = ?", [tenant.id], (err) => {
                if (err) console.error('Failed to deduct credit:', err);
            });

            console.log(`✅ [FastMediaQueue] Ingestion complete: ${cdnUrl}`);
        } finally {
            try { fs.rmSync(workDir, { recursive: true, force: true }); } catch (_) {}
        }
    }
}
