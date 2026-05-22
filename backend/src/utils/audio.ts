import ffmpeg from 'fluent-ffmpeg';
import * as fs from 'fs';

/**
 * Extracts the audio channel from a video file, downmixes to mono,
 * and resamples to 16kHz PCM WAV — matching the format expected
 * by the Android client's Vosk ASR pipeline.
 */
export function extractAudioChannel(videoUrl: string, destPath: string): Promise<void> {
    if (!videoUrl.startsWith('http://') && !videoUrl.startsWith('https://')) {
        return Promise.reject(new Error("Invalid protocol. Only HTTP(S) URLs are allowed."));
    }
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
