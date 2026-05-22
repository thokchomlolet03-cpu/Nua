import ffmpeg from 'fluent-ffmpeg';

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
        ffmpeg(videoUrl)
            .outputOptions([
                '-vn',                    // No video
                '-acodec', 'pcm_s16le',   // 16-bit PCM
                '-ac', '1',               // Mono
                '-ar', '16000',           // 16kHz sample rate
                '-timeout', '15000000'    // 15s connection timeout (in microseconds)
            ])
            .save(destPath)
            .on('end', () => {
                console.log(`  🔊 Audio extracted: ${destPath}`);
                resolve();
            })
            .on('error', (err) => {
                reject(new Error(`Audio extraction failed: ${err.message}`));
            });
    });
}
