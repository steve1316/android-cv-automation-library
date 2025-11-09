package com.steve1316.automation_library.utils

import net.ricecode.similarity.JaroWinklerStrategy
import net.ricecode.similarity.StringSimilarityServiceImpl
import kotlin.math.abs

/**
 * Helper functions for parsing text.
 */
object TextUtils {
    /** Finds the closest substring in a string.
    *
    * @param query The string to attempt to find inside the source string.
    * @param source The string within which we will search for the query string.
    * @param threshold Threshold value for filtering the result by similarity.
    *
    * @return The result string if score > threshold, else NULL.
    */
    fun findMostSimilarSubstring(query: String, source: String, threshold: Double = 0.8): String? {
        // Return early if there is an exact match.
        if (query in source) {
            return query
        }

        val service = StringSimilarityServiceImpl(JaroWinklerStrategy())
        var bestMatch: String? = null
        var bestScore: Double = 0.0

        // Generate all possible substrings.
        for (i in 0..query.length) {
            for (j in (i + 1)..query.length) {
                val sub = query.substring(i, j)
                // Only compare if the substring length is reasonably close to the source length.
                if (abs(sub.length - source.length) <= 2) {
                    val score = service.score(sub, source)
                    if (score > bestScore && score >= threshold) {
                        bestScore = score
                        bestMatch = sub
                    }
                }
            }
        }
        return bestMatch
    }

    /** Finds the closest matching string in a list of strings.
    *
    * @param query The string to find in the list.
    * @param choices The list of strings to search.
    * @param threshold Threshold value for filtering the result by similarity.
    *
    * @return The result string if score > threshold, else NULL.
    */
    fun matchStringInList(query: String, choices: List<String>, threshold: Double = 0.8): String? {
        // Return early if there is an exact match.
        if (choices.contains(query)) {
            return query
        }

        var result: String? = null
        var bestScore = 0.0
        val service = StringSimilarityServiceImpl(JaroWinklerStrategy())
        choices.forEach { choice ->
            val score = service.score(query, choice)
            if (score > bestScore) {
                bestScore = score
                result = choice
            }
        }

        if (bestScore < threshold) {
            return null
        }

        return result
    }
}