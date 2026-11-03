package com.example.ragapp.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.ragapp.common.AiServiceUnavailableException;
import com.example.ragapp.common.ServiceBusyException;
import com.example.ragapp.document.DocumentStatus;
import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.document.PolicyDocument;
import com.example.ragapp.document.PolicyDocumentRepository;
import com.example.ragapp.retrieval.RankFusion.FusedHit;
import com.example.ragapp.retrieval.Reranker.RankedHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds the document chunks that best answer a question, in two stages.
 *
 * Stage 1 - FIND (hybrid search): two searches over this tenant's chunks
 *             a) vector search  : similar in meaning        (good for different wording)
 *             b) keyword search : contains the same words   (good for codes, names, numbers)
 *           and the two result lists are combined (RankFusion).
 * Stage 2 - RERANK: the best candidates get a closer look and are reordered (Reranker).
 *
 * Chunks of documents that are still being processed are never returned.
 * The settings (app.rag in application.yml) can switch hybrid search and reranking off,
 * which is useful to compare the options.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /** Below this similarity, a vector match is only trusted when a keyword search finds one of the question's words too. */
    private static final double MIN_SIMILARITY_WITHOUT_KEYWORD_MATCH = 0.60;

    private final EmbeddingGateway embeddingGateway;
    private final ChunkSearchRepository searchRepository;
    private final PolicyDocumentRepository documentRepository;
    private final Reranker reranker;
    private final RagProperties properties;
    private final RagMetrics metrics;

    public RetrievalService(EmbeddingGateway embeddingGateway, ChunkSearchRepository searchRepository,
                            PolicyDocumentRepository documentRepository, Reranker reranker,
                            RagProperties properties, RagMetrics metrics) {
        this.embeddingGateway = embeddingGateway;
        this.searchRepository = searchRepository;
        this.documentRepository = documentRepository;
        this.reranker = reranker;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * If the embedding model is unavailable, the search does not fail: it continues with the keyword
     * search alone and says so in the result ("degraded"). A result that is a little less complete is
     * better than no result.
     * A failing database cannot be replaced by anything, so that error is passed on.
     */
    public RetrievalResult search(String tenantId, String question) {
        float[] queryVector = null;
        int embeddingTokens = 0;
        boolean degraded = false;
        try {
            EmbeddingGateway.QueryEmbedding embedding = embeddingGateway.embed(question);
            queryVector = embedding.vector();
            embeddingTokens = embedding.tokens();
        } catch (AiServiceUnavailableException | ServiceBusyException e) {
            degraded = true;
            log.warn("Embedding model not available, searching by keywords only: {}", e.getMessage());
        }

        // Stage 1a: meaning. Weak matches (below the threshold) are dropped.
        final float[] vector = queryVector;
        List<SearchHit> vectorHits = vector == null
                ? List.of()
                : metrics.stage("vector_search", () ->
                        searchRepository.vectorSearch(tenantId, vector, properties.candidates())).stream()
                        .filter(hit -> hit.similarity() >= properties.similarityThreshold())
                        .toList();

        // Stage 1b: keywords (always used when the meaning search is not available)
        final boolean searchDegraded = degraded;
        List<SearchHit> textHits = metrics.stage("keyword_search", () ->
                keywordSearch(tenantId, question, vector, searchDegraded));

        // A vague question ("prompt") has no word in common with any chunk, so only a loosely similar vector
        // is left, and a language model will happily make an answer out of it. Do not use such a match.
        if (isOnlyAVagueMatch(question, vectorHits, textHits, vector)) {
            log.info("Retrieval: no chunk contains any word of the question and the best similarity is below {}: nothing relevant",
                    MIN_SIMILARITY_WITHOUT_KEYWORD_MATCH);
            vectorHits = List.of();
        }

        // Stage 1c: combine, and keep only chunks of fully processed documents
        List<FusedHit> fused = RankFusion.fuse(vectorHits, textHits, properties.rrfK());
        List<FusedHit> usable = onlyCompletedDocuments(fused);
        List<FusedHit> candidates = usable.subList(0, Math.min(usable.size(), properties.rerankCandidates()));

        // Stage 2: reorder
        List<RankedHit> ranked = properties.rerankEnabled()
                ? metrics.stage("rerank", () -> reranker.rerank(question, candidates))
                : candidates.stream().map(c -> new RankedHit(c.hit(), c.hit().similarity())).toList();

        List<RetrievedChunk> chunks = ranked.stream()
                .limit(properties.topK())
                .map(this::toChunk)
                .toList();

        // Diagnostics: counts and scores only, never the question text (privacy)
        log.info("Retrieval: vector={} keyword={} combined={} usable={} -> returned {} (hybrid={}, rerank={}, degraded={}), scores={}",
                vectorHits.size(), textHits.size(), fused.size(), usable.size(), chunks.size(),
                properties.hybridEnabled(), properties.rerankEnabled(), degraded,
                chunks.stream().map(chunk -> String.format("%.3f", chunk.score())).toList());
        metrics.retrieval(chunks.size(), chunks.isEmpty() ? 0 : chunks.get(0).score(), degraded);
        return new RetrievalResult(chunks, degraded, embeddingTokens);
    }

    /**
     * True when the question has searchable words, no chunk contains any of them, and even the best vector match is
     * only loosely similar. A real question that is worded very differently from the document still scores
     * clearly higher (0.60 and more with nomic-embed-text) or shares at least one word with it.
     */
    private boolean isOnlyAVagueMatch(String question, List<SearchHit> vectorHits, List<SearchHit> textHits, float[] vector) {
        if (vector == null || !properties.hybridEnabled() || !textHits.isEmpty() || vectorHits.isEmpty()
                || TextQueryBuilder.all(question).isEmpty()) {
            return false;
        }
        double best = vectorHits.stream().mapToDouble(SearchHit::similarity).max().orElse(0);
        return best < MIN_SIMILARITY_WITHOUT_KEYWORD_MATCH;
    }

    private List<SearchHit> keywordSearch(String tenantId, String question, float[] queryVector, boolean degraded) {
        if (!properties.hybridEnabled() && !degraded) {
            return List.of();
        }
        String allWords = TextQueryBuilder.all(question);
        if (allWords.isEmpty()) {
            return List.of();   // the question has no words we can search for
        }

        // First the strict search: chunks that contain EVERY word (very precise, ideal for codes and names).
        List<SearchHit> hits = searchRepository.textSearch(tenantId, allWords, queryVector, properties.candidates());
        if (!hits.isEmpty()) {
            return hits;
        }
        // Nothing contains all the words (normal for a long question): chunks that contain ANY word.
        return searchRepository.textSearch(tenantId, TextQueryBuilder.any(question), queryVector, properties.candidates());
    }

    /** A document that is still being processed has only some of its chunks stored, so skip it. */
    private List<FusedHit> onlyCompletedDocuments(List<FusedHit> fused) {
        Set<UUID> documentIds = fused.stream()
                .map(hit -> documentIdOf(hit.hit()))
                .collect(Collectors.toSet());
        Set<UUID> completed = documentRepository.findAllById(documentIds).stream()
                .filter(doc -> doc.getStatus() == DocumentStatus.COMPLETED)
                .map(PolicyDocument::getId)
                .collect(Collectors.toSet());

        List<FusedHit> result = new ArrayList<>();
        for (FusedHit hit : fused) {
            if (completed.contains(documentIdOf(hit.hit()))) {
                result.add(hit);
            }
        }
        return result;
    }

    private UUID documentIdOf(SearchHit hit) {
        return UUID.fromString((String) hit.metadata().get("document_id"));
    }

    private RetrievedChunk toChunk(RankedHit ranked) {
        SearchHit hit = ranked.hit();
        Map<String, Object> meta = hit.metadata();
        Object page = meta.get("page_number");
        return new RetrievedChunk(
                documentIdOf(hit),
                (String) meta.get("file_name"),
                (String) meta.get("title"),
                (String) meta.get("version"),
                page instanceof Number number ? number.intValue() : null,
                (String) meta.get("section"),
                hit.text(),
                ranked.score());
    }
}
