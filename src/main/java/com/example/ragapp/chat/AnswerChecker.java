package com.example.ragapp.chat;

import java.util.regex.Pattern;

/** Small helpers that inspect the text written by the language model. */
final class AnswerChecker {

    /** The exact sentence the model is told to use when the context has no answer. */
    static final String NOT_FOUND_ANSWER = "I could not find this information in the available documents.";

    private static final String NOT_FOUND_MARKER = "could not find this information";
    private static final Pattern CITATION_MARKER = Pattern.compile("\\s*\\[\\d{1,2}]");

    private AnswerChecker() {
    }

    static boolean saysNotFound(String answer) {
        return answer == null || answer.isBlank() || answer.toLowerCase().contains(NOT_FOUND_MARKER);
    }

    /**
     * Removes source markers like [3] if the model wrote them anyway.
     * Our own source list is shown instead, because the model's numbers cannot be trusted.
     */
    static String removeCitationMarkers(String answer) {
        return CITATION_MARKER.matcher(answer).replaceAll("").strip();
    }
}
