package com.example.ragapp.privacy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Masks sensitive data in the texts of ONE request, and puts the real values back afterwards.
 *
 *   mask("Mail maria@acme.com")        ->  "Mail [EMAIL_1]"      (this is what the model sees)
 *   restore("Write to [EMAIL_1]")      ->  "Write to maria@acme.com"   (this is what the user sees)
 *
 * The same value always gets the same placeholder inside one session, so the model can still
 * tell that two mentions are the same person. A session lives for one request only and is never
 * stored, so the real values never leave this application.
 */
public class MaskingSession {

    private final boolean enabled;
    private final Map<String, String> placeholderByValue = new HashMap<>();
    private final Map<String, String> valueByPlaceholder = new LinkedHashMap<>();
    private final Map<String, Integer> counterByLabel = new HashMap<>();

    MaskingSession(boolean enabled) {
        this.enabled = enabled;
    }

    public String mask(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (PiiType type : PiiType.values()) {
            result = maskType(result, type);
        }
        return result;
    }

    /** Replaces the placeholders in a text (for example the model's answer) by the real values. */
    public String restore(String text) {
        if (text == null || valueByPlaceholder.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : valueByPlaceholder.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public boolean hasMaskedValues() {
        return !valueByPlaceholder.isEmpty();
    }

    /** How many different values were masked (never the values themselves: they must not be logged). */
    public int maskedValueCount() {
        return valueByPlaceholder.size();
    }

    /** For streamed answers, where a placeholder can arrive in pieces. */
    public StreamingRestorer streamingRestorer() {
        return new StreamingRestorer(this);
    }

    private String maskType(String text, PiiType type) {
        Matcher matcher = type.pattern().matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String match = matcher.group();
            String replacement = type.accepts(match) ? placeholderFor(type, match) : match;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String placeholderFor(PiiType type, String value) {
        String key = type.label() + "|" + value;
        return placeholderByValue.computeIfAbsent(key, k -> {
            int number = counterByLabel.merge(type.label(), 1, Integer::sum);
            String placeholder = "[" + type.label() + "_" + number + "]";
            valueByPlaceholder.put(placeholder, value);
            return placeholder;
        });
    }
}
