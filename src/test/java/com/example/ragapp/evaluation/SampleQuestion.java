package com.example.ragapp.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * A question about the sample documents (a line of sample-questions.json).
 *
 * @param documents files that contain the answer; null when the documents do NOT contain the answer.
 *                  The complete handbook contains every policy, so it is always accepted as well.
 * @param text      regular expression that one of the passages found by the search must match
 * @param facts     regular expressions that must all match the written answer
 */
public record SampleQuestion(String id, String question, List<String> documents, String text, List<String> facts) {

    public static final String HANDBOOK = "northwind-complete-employee-handbook-2026.pdf";

    public record File(String description, List<SampleQuestion> questions) {
    }

    public static List<SampleQuestion> load() {
        try (InputStream in = SampleQuestion.class.getResourceAsStream("/evaluation/sample-questions.json")) {
            return JsonMapper.builder().build().readValue(in, File.class).questions();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean answerable() {
        return documents != null;
    }

    /** Does this file contain the answer? */
    public boolean accepts(String fileName) {
        return answerable() && (HANDBOOK.equals(fileName) || documents.contains(fileName));
    }
}
