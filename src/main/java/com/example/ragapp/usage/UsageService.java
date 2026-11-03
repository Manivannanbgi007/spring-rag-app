package com.example.ragapp.usage;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Keeps one small row per question (tokens, estimated cost, duration) and reports on them per tenant.
 * The rows contain no text and no user id, so they can be kept long after the conversations are deleted.
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final JdbcTemplate jdbc;

    public UsageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Never throws: losing one usage row must not make a question fail. */
    public void record(String tenantId, String outcome, TokenUsage usage, double costUsd, long durationMs) {
        try {
            jdbc.update("""
                    INSERT INTO usage_records (tenant_id, outcome, prompt_tokens, completion_tokens, embedding_tokens,
                                               cost_usd, duration_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, outcome, usage.promptTokens(), usage.completionTokens(), usage.embeddingTokens(),
                    costUsd, (int) Math.min(durationMs, Integer.MAX_VALUE));
        } catch (RuntimeException e) {
            log.warn("Could not save the usage record: {}", e.getMessage());
        }
    }

    public UsageReport report(String tenantId, int days) {
        UsageReport.Totals totals = jdbc.queryForObject("""
                SELECT count(*) AS questions,
                       count(*) FILTER (WHERE outcome = 'answered')  AS answered,
                       count(*) FILTER (WHERE outcome = 'not_found') AS not_found,
                       count(*) FILTER (WHERE outcome = 'degraded')  AS degraded,
                       coalesce(sum(prompt_tokens), 0)     AS prompt_tokens,
                       coalesce(sum(completion_tokens), 0) AS completion_tokens,
                       coalesce(sum(embedding_tokens), 0)  AS embedding_tokens,
                       coalesce(sum(cost_usd), 0)          AS cost_usd,
                       coalesce(avg(duration_ms), 0)::bigint AS average_ms,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)::bigint AS p95_ms
                FROM usage_records
                WHERE tenant_id = ? AND created_at >= now() - make_interval(days => ?)
                """, (rs, row) -> new UsageReport.Totals(rs.getLong("questions"), rs.getLong("answered"),
                rs.getLong("not_found"), rs.getLong("degraded"), rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"), rs.getLong("embedding_tokens"), rs.getDouble("cost_usd"),
                rs.getLong("average_ms"), rs.getLong("p95_ms")), tenantId, days);

        List<UsageReport.Day> perDay = jdbc.query("""
                SELECT created_at::date AS day, count(*) AS questions,
                       sum(prompt_tokens) AS prompt_tokens, sum(completion_tokens) AS completion_tokens,
                       sum(embedding_tokens) AS embedding_tokens, sum(cost_usd) AS cost_usd
                FROM usage_records
                WHERE tenant_id = ? AND created_at >= now() - make_interval(days => ?)
                GROUP BY created_at::date
                ORDER BY created_at::date DESC
                """, (rs, row) -> new UsageReport.Day(rs.getObject("day", LocalDate.class), rs.getLong("questions"),
                rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getLong("embedding_tokens"),
                rs.getDouble("cost_usd")), tenantId, days);

        return new UsageReport(tenantId, days, totals, perDay);
    }
}
