import ffmpeg from 'fluent-ffmpeg';

/**
 * Extracts the audio channel from a video file, downmixes to mono,
 * and resamples to 16kHz PCM WAV — matching the format expected
 * by the Android client's Vosk ASR pipeline.
 */
export function extractAudioChannel(videoUrl: string, destPath: string): Promise<void> {
    return new Promise((resolve, reject) => {
        ffmpeg(videoUrl)
            .outputOptions([
                '-vn',                    // No video
                '-acodec', 'pcm_s16le',   // 16-bit PCM
                '-ac', '1',               // Mono
                '-ar', '16000'            // 16kHz sample rate
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
