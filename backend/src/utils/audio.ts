import ffmpeg from 'fluent-ffmpeg';
import * as fs from 'fs';
import { URL } from 'url';
import dns from 'dns/promises';

function isPrivateIp(ip: string): boolean {
    if (!ip) return true;

    // IPv4 check
    const v4Match = ip.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
    if (v4Match) {
        const [_, o1, o2, o3, o4] = v4Match.map(Number);
        if (o1 === 127 || o1 === 10 || o1 === 0) return true;
        if (o1 === 172 && (o2 >= 16 && o2 <= 31)) return true;
        if (o1 === 192 && o2 === 168) return true;
        if (o1 === 169 && o2 === 254) return true;
        return false;
    }

    // IPv6 check
    const ipLower = ip.toLowerCase();
    if (ipLower === '::1' || ipLower === '::') return true;
    if (ipLower.startsWith('fe80:')) return true;
    if (ipLower.startsWith('fc00:') || ipLower.startsWith('fd00:')) return true;

    return false;
}

async function validateVideoUrl(videoUrl: string): Promise<void> {
    if (!videoUrl.startsWith('http://') && !videoUrl.startsWith('https://')) {
        throw new Error("Invalid protocol. Only HTTP(S) URLs are allowed.");
    }

    const parsedUrl = new URL(videoUrl);
    const hostname = parsedUrl.hostname;

    if (!hostname) {
        throw new Error("Invalid URL: Hostname is empty.");
    }

    // If it's already an IP address, validate it directly
    if (/^[0-9a-fA-F.:]+$/.test(hostname)) {
        if (isPrivateIp(hostname)) {
            throw new Error("Access to private/local IP addresses is forbidden.");
        }
        return;
    }

    // Resolve DNS
    try {
        const addresses = await dns.resolve(hostname).catch(async () => {
            // fallback to lookup if resolve fails (e.g. localhost)
            const lookupResult = await dns.lookup(hostname, { all: true });
            return lookupResult.map(r => r.address);
        });

        for (const addr of addresses) {
            if (isPrivateIp(addr)) {
                throw new Error(`Access to private/local IP address ${addr} is forbidden.`);
            }
        }
    } catch (err: any) {
        if (err.message.includes('forbidden')) {
            throw err;
        }
        throw new Error(`Failed to resolve host ${hostname}: ${err.message}`);
    }
}

/**
 * Extracts the audio channel from a video file, downmixes to mono,
 * and resamples to 16kHz PCM WAV — matching the format expected
 * by the Android client's Vosk ASR pipeline.
 */
export async function extractAudioChannel(videoUrl: string, destPath: string): Promise<void> {
    await validateVideoUrl(videoUrl);

    return new Promise((resolve, reject) => {
        const cmd = ffmpeg(videoUrl)
            .outputOptions([
                '-vn',                    // No video
                '-acodec', 'pcm_s16le',   // 16-bit PCM
                '-ac', '1',               // Mono
                '-ar', '16000',           // 16kHz sample rate
                '-timeout', '15000000'    // 15s connection timeout (in microseconds)
            ])
            .save(destPath);

        const timeoutId = setTimeout(() => {
            cmd.kill('SIGKILL');
            reject(new Error('Audio extraction timed out after 3 minutes'));
        }, 180000);

        cmd.on('end', () => {
            clearTimeout(timeoutId);
            if (fs.existsSync(destPath) && fs.statSync(destPath).size > 0) {
                console.log(`  🔊 Audio extracted: ${destPath}`);
                resolve();
            } else {
                reject(new Error('Audio extraction failed: Output file is empty or missing'));
            }
        }).on('error', (err) => {
            clearTimeout(timeoutId);
            reject(new Error(`Audio extraction failed: ${err.message}`));
        });
    });
}
