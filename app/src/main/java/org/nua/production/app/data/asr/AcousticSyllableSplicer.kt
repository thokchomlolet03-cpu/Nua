package org.nua.production.app.data.asr

import android.util.Log

/**
 * Acoustic Syllable Splicing Layer.
 * Replaces macro-sentence boundary segmentation with syllable/word-level splicing.
 * By matching segment boundary split points to natural silence gaps between individual sounds,
 * the time-warping engine can apply subtle speed tweaks between words rather than major jumps.
 */
object AcousticSyllableSplicer {

    private const val TAG = "AcousticSyllableSplicer"

    // Silence thresholds for matching split points
    private const val OPTIMAL_SILENCE_GAP_SEC = 0.30 // 300ms pause between sounds
    private const val MIN_SILENCE_GAP_SEC = 0.15     // 150ms micro-pause
    private const val TARGET_SEGMENT_DURATION_SEC = 3.0 // Shorter segments for micro-speed adjustments
    private const val MAX_WORDS_PER_SEGMENT = 6

    data class WordItem(
        val word: String,
        val start: Double,
        val end: Double,
        val conf: Double
    )

    /**
     * Splices a flat stream of transcribed words into smaller, acoustic-silence-aligned segments.
     */
    fun spliceIntoSegments(words: List<WordItem>): List<TextSegment> {
        if (words.isEmpty()) return emptyList()

        val segments = ArrayList<TextSegment>()
        var currentChunk = ArrayList<WordItem>()

        for (i in words.indices) {
            val word = words[i]
            if (currentChunk.isEmpty()) {
                currentChunk.add(word)
                continue
            }

            val lastWord = currentChunk.last()
            val gap = word.start - lastWord.end
            val currentDuration = word.end - currentChunk.first().start

            // Decide whether to split at this word boundary
            var shouldSplit = false

            when {
                // If we detect a clear silence gap of 300ms+, split immediately
                gap >= OPTIMAL_SILENCE_GAP_SEC -> {
                    shouldSplit = true
                }
                // If segment has reached target size and we see a micro-pause, split
                currentDuration >= TARGET_SEGMENT_DURATION_SEC && gap >= MIN_SILENCE_GAP_SEC -> {
                    shouldSplit = true
                }
                // Hard ceiling: split if we exceed max words to avoid massive chunks
                currentChunk.size >= MAX_WORDS_PER_SEGMENT -> {
                    shouldSplit = true
                }
            }

            if (shouldSplit) {
                segments.add(buildSegment(currentChunk))
                currentChunk = ArrayList()
            }
            currentChunk.add(word)
        }

        if (currentChunk.isNotEmpty()) {
            segments.add(buildSegment(currentChunk))
        }

        Log.d(TAG, "Spliced ${words.size} words into ${segments.size} syllable-aligned segments")
        return segments
    }

    private fun buildSegment(words: List<WordItem>): TextSegment {
        val text = words.joinToString(" ") { it.word }
        return TextSegment(
            text = text,
            startTimeSec = words.first().start,
            endTimeSec = words.last().end
        )
    }
}
