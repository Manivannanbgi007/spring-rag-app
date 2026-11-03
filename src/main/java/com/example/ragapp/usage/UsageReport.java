package com.example.ragapp.usage;

import java.time.LocalDate;
import java.util.List;

/**
 * The usage of one tenant over the last few days.
 *
 * @param questions     number of questions
 * @param answered      questions that got an answer from the documents
 * @param notFound      questions the documents could not answer
 * @param degraded      questions answered in limited mode (a model was unavailable)
 * @param averageMs     average time to answer
 * @param p95Ms         95 of 100 questions were answered faster than this
 */
public record UsageReport(String tenantId, int days, Totals totals, List<Day> perDay) {

    public record Totals(long questions, long answered, long notFound, long degraded, long promptTokens,
                         long completionTokens, long embeddingTokens, double costUsd, long averageMs, long p95Ms) {
    }

    public record Day(LocalDate day, long questions, long promptTokens, long completionTokens, long embeddingTokens,
                      double costUsd) {
    }
}
