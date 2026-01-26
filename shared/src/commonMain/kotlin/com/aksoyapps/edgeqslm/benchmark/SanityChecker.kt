package com.aksoyapps.edgeqslm.benchmark

/**
 * Lightweight sanity checks for output quality.
 * NOT a full benchmark suite - these are minimal checks for thesis RQ2.
 */
object SanityChecker {
    
    // Configuration
    private const val MIN_TOKENS_FOR_COHERENCE = 3
    private const val REPETITION_NGRAM_SIZE = 3
    private const val HIGH_REPETITION_THRESHOLD = 0.3f  // 30% repeated content
    private const val AVG_CHARS_PER_TOKEN = 4.0f        // Rough estimate for English
    
    /**
     * Perform all sanity checks on an output.
     */
    fun check(output: String, expectedMinTokens: Int = 10): SanityCheckResult {
        val trimmed = output.trim()
        val charCount = trimmed.length
        val estimatedTokens = estimateTokenCount(trimmed)
        
        // Check for empty output
        val isEmpty = trimmed.isEmpty()
        
        // Calculate repetition score
        val (repetitionScore, repeatedNGrams) = if (isEmpty) {
            0f to 0
        } else {
            calculateRepetitionScore(trimmed)
        }
        
        // Check coherence
        val (isCoherent, coherenceReason) = checkCoherence(
            output = trimmed,
            isEmpty = isEmpty,
            estimatedTokens = estimatedTokens,
            repetitionScore = repetitionScore,
            expectedMinTokens = expectedMinTokens
        )
        
        return SanityCheckResult(
            isEmpty = isEmpty,
            charCount = charCount,
            estimatedTokenCount = estimatedTokens,
            repetitionScore = repetitionScore,
            repeatedNGramCount = repeatedNGrams,
            isCoherent = isCoherent,
            coherenceReason = coherenceReason
        )
    }
    
    /**
     * Estimate token count from character count.
     * This is a rough approximation - actual tokens depend on tokenizer.
     */
    fun estimateTokenCount(text: String): Int {
        if (text.isEmpty()) return 0
        
        // Count words as rough token estimate
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wordCount = words.size
        
        // Also consider punctuation and special characters
        val punctuationCount = text.count { it.isLetterOrDigit().not() && !it.isWhitespace() }
        
        // Rough estimate: words + some fraction of punctuation
        return (wordCount + punctuationCount / 2).coerceAtLeast(1)
    }
    
    /**
     * Calculate repetition score based on repeated n-grams.
     * Returns (score, count) where score is 0.0-1.0 and count is number of repeated n-grams.
     */
    private fun calculateRepetitionScore(text: String): Pair<Float, Int> {
        if (text.length < REPETITION_NGRAM_SIZE * 2) {
            return 0f to 0
        }
        
        // Split into words for word-level n-grams
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        
        if (words.size < REPETITION_NGRAM_SIZE) {
            return 0f to 0
        }
        
        // Generate n-grams
        val ngrams = mutableListOf<String>()
        for (i in 0..words.size - REPETITION_NGRAM_SIZE) {
            val ngram = words.subList(i, i + REPETITION_NGRAM_SIZE).joinToString(" ")
            ngrams.add(ngram)
        }
        
        if (ngrams.isEmpty()) return 0f to 0
        
        // Count unique n-grams
        val ngramCounts = ngrams.groupingBy { it }.eachCount()
        val repeatedNgrams = ngramCounts.filter { it.value > 1 }
        val totalRepeatedOccurrences = repeatedNgrams.values.sum() - repeatedNgrams.size
        
        // Calculate score: ratio of repeated content
        val repetitionScore = if (ngrams.isNotEmpty()) {
            totalRepeatedOccurrences.toFloat() / ngrams.size
        } else {
            0f
        }
        
        return repetitionScore.coerceIn(0f, 1f) to repeatedNgrams.size
    }
    
    /**
     * Check for token-level repetition (repeated individual tokens).
     */
    fun checkTokenRepetition(text: String): Float {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return 0f
        
        var consecutiveRepeats = 0
        for (i in 1 until words.size) {
            if (words[i].equals(words[i - 1], ignoreCase = true)) {
                consecutiveRepeats++
            }
        }
        
        return consecutiveRepeats.toFloat() / (words.size - 1)
    }
    
    /**
     * Check basic coherence of output.
     */
    private fun checkCoherence(
        output: String,
        isEmpty: Boolean,
        estimatedTokens: Int,
        repetitionScore: Float,
        expectedMinTokens: Int
    ): Pair<Boolean, String> {
        // Check 1: Not empty
        if (isEmpty) {
            return false to "Output is empty"
        }
        
        // Check 2: Minimum tokens
        if (estimatedTokens < MIN_TOKENS_FOR_COHERENCE) {
            return false to "Too few tokens (${estimatedTokens} < $MIN_TOKENS_FOR_COHERENCE)"
        }
        
        // Check 3: Not too repetitive
        if (repetitionScore > HIGH_REPETITION_THRESHOLD) {
            return false to "High repetition score (${String.format("%.1f%%", repetitionScore * 100)})"
        }
        
        // Check 4: Contains actual content (not just punctuation/whitespace)
        val alphanumericRatio = output.count { it.isLetterOrDigit() }.toFloat() / output.length
        if (alphanumericRatio < 0.5f) {
            return false to "Low alphanumeric content ratio (${String.format("%.1f%%", alphanumericRatio * 100)})"
        }
        
        // Check 5: Meets expected minimum tokens (soft check)
        if (estimatedTokens < expectedMinTokens / 2) {
            return false to "Below expected length (${estimatedTokens} vs expected ~${expectedMinTokens})"
        }
        
        return true to "Passes all coherence checks"
    }
    
    /**
     * Get summary of sanity check for logging.
     */
    fun getSummary(result: SanityCheckResult): String {
        return buildString {
            append("SanityCheck: ")
            append(if (result.isCoherent) "✓ PASS" else "✗ FAIL")
            append(" | ")
            append("chars=${result.charCount}, ")
            append("tokens≈${result.estimatedTokenCount}, ")
            append("rep=${String.format("%.1f%%", result.repetitionScore * 100)}")
            if (!result.isCoherent) {
                append(" | reason: ${result.coherenceReason}")
            }
        }
    }
}
