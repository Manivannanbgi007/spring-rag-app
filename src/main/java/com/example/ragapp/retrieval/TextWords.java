package com.example.ragapp.retrieval;

import java.util.HashSet;
import java.util.Set;

/**
 * Splits text into comparable words. Used wherever we compare the words of two texts
 * (reranking a search result, finding the source of an answer).
 */
public final class TextWords {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "are", "was", "were", "has", "have", "had", "per", "that", "this", "with",
            "from", "all", "any", "may", "can", "will", "must", "not", "its", "their", "your", "you", "our",
            "than", "then", "also", "each", "into", "when", "which", "who", "how", "what", "about", "after",
            "before", "while", "they", "them", "been", "being", "does", "did", "but", "one", "get", "gets");

    private TextWords() {
    }

    /**
     * Lower-case words and numbers, without very common words and one-letter words.
     * A trailing "s" is removed, so "days" and "day" count as the same word.
     */
    public static Set<String> words(String text) {
        Set<String> result = new HashSet<>();
        for (String word : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            boolean isNumber = !word.isEmpty() && word.chars().allMatch(Character::isDigit);
            if ((isNumber || word.length() >= 3) && !STOP_WORDS.contains(word)) {
                result.add(word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word);
            }
        }
        return result;
    }
}
