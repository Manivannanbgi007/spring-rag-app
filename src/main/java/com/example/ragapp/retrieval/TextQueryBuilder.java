package com.example.ragapp.retrieval;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a question into PostgreSQL full-text queries. Two versions of the same words:
 *
 *   all("What is form AB-1007?")  ->  "what & is & form & ab & 1007"   (a chunk must contain ALL words)
 *   any("What is form AB-1007?")  ->  "what | is | form | ab | 1007"   (a chunk may contain ANY word)
 *
 * The strict version is tried first. A chunk that contains every word of the question is a very
 * precise match. The loose version is the fallback for long questions where no single chunk contains
 * all the words. (PostgreSQL has no notion of "rare word", so with the loose version a common word
 * like "form" matches every page and hides the one rare word that matters, such as "1007".)
 *
 * Only letters and digits are kept, so nothing the user types can break or change the query
 * syntax. PostgreSQL itself removes common words ("what", "is") and reduces words to their
 * stems ("days" -> "day") when it runs the query.
 */
public final class TextQueryBuilder {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final int MAX_TERMS = 12;

    private TextQueryBuilder() {
    }

    /** A chunk must contain all words. Empty string when the question has no usable words. */
    public static String all(String question) {
        return String.join(" & ", terms(question));
    }

    /** A chunk may contain any of the words. Empty string when the question has no usable words. */
    public static String any(String question) {
        return String.join(" | ", terms(question));
    }

    private static Set<String> terms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(question.toLowerCase());
        while (matcher.find() && terms.size() < MAX_TERMS) {
            terms.add(matcher.group());
        }
        return terms;
    }
}
