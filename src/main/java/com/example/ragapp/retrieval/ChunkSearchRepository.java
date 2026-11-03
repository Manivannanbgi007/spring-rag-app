package com.example.ragapp.retrieval;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import com.example.ragapp.resilience.ResilienceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The two searches over the chunk table (vector_store):
 *
 *   1. vector search   - closest in MEANING to the question (pgvector)
 *   2. keyword search  - contains the question's WORDS (PostgreSQL full-text search)
 *
 * Both are plain SQL so that they return the same kind of result and can be combined.
 * (Spring AI's VectorStore interface only offers the vector search, so it is used for saving
 * and deleting chunks, and this class is used for searching.)
 *
 * Every query contains the tenant: one tenant can never find another tenant's chunks.
 */
@Repository
public class ChunkSearchRepository {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ChunkSearchRepository(DataSource dataSource, ObjectMapper objectMapper, ResilienceProperties properties) {
        // A search that takes longer than the limit is cancelled by the database driver: a slow or
        // overloaded database must not make users wait forever.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout((int) Math.max(1, properties.searchTimeout().toSeconds()));
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.objectMapper = objectMapper;
    }

    /** The chunks closest to the question vector, best first. "<=>" is pgvector's cosine distance. */
    public List<SearchHit> vectorSearch(String tenantId, float[] queryVector, int limit) {
        String sql = """
                SELECT id, content, metadata::text AS metadata,
                       1 - (embedding <=> CAST(:vector AS vector)) AS similarity
                FROM vector_store
                WHERE metadata ->> 'tenant_id' = :tenant
                ORDER BY embedding <=> CAST(:vector AS vector)
                LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("vector", toVectorText(queryVector))
                .addValue("tenant", tenantId)
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNumber) -> new SearchHit(
                rs.getObject("id", UUID.class), rs.getString("content"), parseMetadata(rs.getString("metadata")),
                rs.getDouble("similarity"), 0));
    }

    /**
     * The chunks that match the text query, best match first.
     * The similarity is calculated too, so that all chunks can be compared later. When the question
     * could not be embedded (queryVector is null), the similarity is 0 and only the keywords decide.
     */
    public List<SearchHit> textSearch(String tenantId, String textQuery, float[] queryVector, int limit) {
        String similarity = queryVector == null
                ? "0.0"
                : "1 - (embedding <=> CAST(:vector AS vector))";
        String sql = """
                SELECT id, content, metadata::text AS metadata,
                       %s AS similarity,
                       ts_rank_cd(content_tsv, query) AS text_rank
                FROM vector_store, to_tsquery('english', :query) AS query
                WHERE metadata ->> 'tenant_id' = :tenant
                  AND content_tsv @@ query
                ORDER BY text_rank DESC
                LIMIT :limit
                """.formatted(similarity);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("query", textQuery)
                .addValue("tenant", tenantId)
                .addValue("limit", limit);
        if (queryVector != null) {
            params.addValue("vector", toVectorText(queryVector));
        }
        return jdbc.query(sql, params, (rs, rowNumber) -> new SearchHit(
                rs.getObject("id", UUID.class), rs.getString("content"), parseMetadata(rs.getString("metadata")),
                rs.getDouble("similarity"), rs.getDouble("text_rank")));
    }

    /** pgvector reads a vector written as text: [0.1,0.2,0.3] */
    private String toVectorText(float[] vector) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(vector[i]);
        }
        return text.append(']').toString();
    }

    private Map<String, Object> parseMetadata(String json) {
        return objectMapper.readValue(json, JSON_MAP);
    }
}
