package com.example.ragapp.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.example.ragapp.common.AiServiceUnavailableException;
import com.example.ragapp.common.ServiceBusyException;
import com.example.ragapp.common.SourceReference;
import com.example.ragapp.document.DocumentStatus;
import com.example.ragapp.document.PolicyDocumentRepository;
import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.privacy.MaskingSession;
import com.example.ragapp.privacy.PiiMasker;
import com.example.ragapp.privacy.StreamingRestorer;
import com.example.ragapp.resilience.ProtectedCalls;
import com.example.ragapp.retrieval.RagProperties;
import com.example.ragapp.retrieval.RetrievalResult;
import com.example.ragapp.retrieval.RetrievalService;
import com.example.ragapp.retrieval.RetrievedChunk;
import com.example.ragapp.usage.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * The RAG chat flow (Retrieval-Augmented Generation):
 *
 *   1. RETRIEVE  - find the document chunks that best match the question (hybrid search + rerank)
 *   2. AUGMENT   - put those chunks into the prompt as numbered context
 *   3. GENERATE  - the language model answers using only that context
 *   4. CITE      - the application finds the passages the answer is based on (AnswerAttribution)
 *
 * If step 1 finds nothing relevant, the model is not called at all.
 *
 * Privacy: sensitive data in the question and in the documents is replaced by placeholders
 * before anything is sent to a model, and the real values are put back into the answer
 * (see MaskingSession). Sources and quotes are taken from the original document text.
 *
 * When something is not available, the user still gets a useful, honest reply ("graceful degradation"):
 *   - embedding model down  -> the search continues with keywords only (see RetrievalService)
 *   - language model down   -> the closest passages from the documents are returned instead of an answer
 *   - database down         -> a clear "not available" error (HTTP 503), nothing can replace the documents
 * Such replies carry degraded = true, so the screen can say that the answer is limited.
 *
 * Every step is timed and traced (RagMetrics), and the tokens of the model calls are counted.
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    private static final int FALLBACK_PASSAGES = 3;
    private static final String NO_DOCUMENTS_ANSWER = "No documents are available yet. Please upload a policy document "
            + "and wait until its status is COMPLETED, then ask your question again.";
    private static final String FALLBACK_INTRO = "The language model is not available right now, so I cannot write "
            + "an answer. These passages from the documents are the closest to your question:";
    private static final String LIMITED_SEARCH_NOTE = " The meaning-based search is temporarily unavailable, "
            + "so this result may be incomplete. Please try again in a few minutes.";

    private final RetrievalService retrievalService;
    private final ContextBuilder contextBuilder;
    private final PromptAssembler promptAssembler;
    private final AnswerAttribution attribution;
    private final PiiMasker piiMasker;
    private final ProtectedCalls protectedCalls;
    private final RagMetrics metrics;
    private final ChatClient chatClient;
    private final RagProperties properties;
    private final PolicyDocumentRepository documentRepository;

    public RagChatService(RetrievalService retrievalService, ContextBuilder contextBuilder,
                          PromptAssembler promptAssembler, AnswerAttribution attribution, PiiMasker piiMasker,
                          ProtectedCalls protectedCalls, RagMetrics metrics,
                          @Qualifier("answerChatClient") ChatClient chatClient, RagProperties properties,
                          PolicyDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
        this.retrievalService = retrievalService;
        this.contextBuilder = contextBuilder;
        this.promptAssembler = promptAssembler;
        this.attribution = attribution;
        this.piiMasker = piiMasker;
        this.protectedCalls = protectedCalls;
        this.metrics = metrics;
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /** What the search found, turned into the context for the model. */
    private record Retrieved(ContextBuilder.BuiltContext context, boolean degraded, TokenUsage usage) {
    }

    /** Waits for the complete answer and returns it in one piece. */
    public RagAnswer answer(String tenantId, String question) {
        MaskingSession pii = piiMasker.newSession();
        String safeQuestion = pii.mask(question);

        Retrieved retrieved = retrieve(tenantId, safeQuestion);
        ContextBuilder.BuiltContext context = retrieved.context();
        if (context.chunks().isEmpty()) {
            return nothingFound(tenantId, retrieved.degraded(), retrieved.usage());
        }

        String prompt = promptAssembler.assemble(context, safeQuestion, pii);
        logMasking(pii);

        org.springframework.ai.chat.model.ChatResponse modelResponse;
        try {
            modelResponse = metrics.stage("generation", () -> protectedCalls.llm().call(() ->
                    chatClient.prompt().user(prompt).call().chatResponse()));
        } catch (AiServiceUnavailableException | ServiceBusyException e) {
            log.warn("Language model not available, returning passages instead: {}", e.getMessage());
            return passagesInsteadOfAnswer(question, context, retrieved.usage());
        }

        String modelAnswer = modelResponse.getResult().getOutput().getText();
        Usage used = modelResponse.getMetadata().getUsage();
        TokenUsage usage = retrieved.usage().plus(TokenUsage.ofLanguageModel(used.getPromptTokens(), used.getCompletionTokens()));
        return buildResponse(pii.restore(modelAnswer), context, retrieved.degraded(), usage);
    }

    /**
     * Sends the answer piece by piece while the model is writing it ("token" events),
     * then one final "done" event with the complete answer and the sources.
     */
    public Flux<ChatEvent> stream(String tenantId, String question) {
        return Flux.defer(() -> {
            MaskingSession pii = piiMasker.newSession();
            String safeQuestion = pii.mask(question);

            Retrieved retrieved = retrieve(tenantId, safeQuestion);
            ContextBuilder.BuiltContext context = retrieved.context();
            if (context.chunks().isEmpty()) {
                RagAnswer none = nothingFound(tenantId, retrieved.degraded(), retrieved.usage());
                return Flux.just(ChatEvent.token(none.answer()), ChatEvent.done(none));
            }

            String prompt = promptAssembler.assemble(context, safeQuestion, pii);
            logMasking(pii);

            StringBuilder fullAnswer = new StringBuilder();
            AtomicBoolean anyWordSent = new AtomicBoolean(false);
            AtomicReference<Usage> modelUsage = new AtomicReference<>();   // the last piece of the stream carries it
            StreamingRestorer restorer = pii.streamingRestorer();

            Flux<ChatEvent> tokens = metrics.stageStream("generation", protectedCalls.llm()
                            .stream(() -> chatClient.prompt().user(prompt).stream().chatResponse()))
                    .map(piece -> wordsAndUsage(piece, modelUsage))
                    .doOnNext(piece -> {
                        fullAnswer.append(piece);
                        if (!piece.isEmpty()) {
                            anyWordSent.set(true);
                        }
                    })
                    .map(restorer::feed)                 // placeholders become the real values again
                    .filter(piece -> !piece.isEmpty())
                    .map(ChatEvent::token);

            // Text that was held back because it could have been the start of a placeholder
            Flux<ChatEvent> rest = Flux.defer(() -> {
                String remaining = restorer.flush();
                return remaining.isEmpty() ? Flux.empty() : Flux.just(ChatEvent.token(remaining));
            });

            // Runs after the last token: now the full text is known, so sources can be chosen
            Flux<ChatEvent> done = Flux.defer(() -> {
                Usage used = modelUsage.get();
                TokenUsage usage = used == null ? retrieved.usage()
                        : retrieved.usage().plus(TokenUsage.ofLanguageModel(used.getPromptTokens(), used.getCompletionTokens()));
                return Flux.just(ChatEvent.done(
                        buildResponse(pii.restore(fullAnswer.toString()), context, retrieved.degraded(), usage)));
            });

            return tokens.concatWith(rest).concatWith(done)
                    // If the model fails BEFORE the first word, show the passages instead.
                    // (If it fails in the middle of an answer, the error is passed on: see below.)
                    .onErrorResume(error -> !anyWordSent.get() && isUnavailable(error), error -> {
                        log.warn("Language model not available, returning passages instead: {}", error.getMessage());
                        RagAnswer fallback = passagesInsteadOfAnswer(question, context, retrieved.usage());
                        return Flux.just(ChatEvent.token(fallback.answer()), ChatEvent.done(fallback));
                    });
        })
        .subscribeOn(Schedulers.boundedElastic())   // retrieval blocks, so keep it off the main threads
        .onErrorResume(error -> {
            log.error("Streaming answer failed", error);
            return Flux.just(ChatEvent.error("The answer could not be generated right now. Please try again."));
        });
    }

    // ----- steps -----

    private Retrieved retrieve(String tenantId, String question) {
        RetrievalResult result;
        try {
            result = retrievalService.search(tenantId, question);
        } catch (AiServiceUnavailableException | ServiceBusyException e) {
            throw e;
        } catch (RuntimeException e) {
            // Mostly the database (for example a query that ran into the time limit)
            throw new AiServiceUnavailableException("The document search is not available right now.", e);
        }
        return new Retrieved(contextBuilder.build(result.chunks()), result.degraded(),
                TokenUsage.ofEmbedding(result.embeddingTokens()));
    }

    /** The text of one streamed piece. The token counts arrive with the last piece: they are kept on the side. */
    private String wordsAndUsage(org.springframework.ai.chat.model.ChatResponse piece, AtomicReference<Usage> usageHolder) {
        if (piece.getMetadata() != null && piece.getMetadata().getUsage() != null
                && piece.getMetadata().getUsage().getTotalTokens() != null
                && piece.getMetadata().getUsage().getTotalTokens() > 0) {
            usageHolder.set(piece.getMetadata().getUsage());
        }
        if (piece.getResult() == null || piece.getResult().getOutput() == null
                || piece.getResult().getOutput().getText() == null) {
            return "";
        }
        return piece.getResult().getOutput().getText();
    }

    private boolean isUnavailable(Throwable error) {
        return error instanceof AiServiceUnavailableException || error instanceof ServiceBusyException;
    }

    private void logMasking(MaskingSession pii) {
        if (pii.hasMaskedValues()) {
            log.info("Privacy: {} sensitive value(s) masked before the model call", pii.maskedValueCount());
            metrics.piiMasked(pii.maskedValueCount());
        }
    }

    private RagAnswer buildResponse(String modelAnswer, ContextBuilder.BuiltContext context, boolean degraded,
                                    TokenUsage usage) {
        if (AnswerChecker.saysNotFound(modelAnswer)) {
            log.info("Answer: model found no answer in the context");
            return notFound(degraded, usage);
        }

        String answer = AnswerChecker.removeCitationMarkers(modelAnswer);

        // The application (not the model) decides which chunks support the answer
        List<RetrievedChunk> chunks = context.chunks();
        List<Integer> supporting = attribution.selectSupportingChunks(answer, chunks);
        if (supporting.isEmpty()) {
            // The answer says things that are not in the documents: do not show it
            log.warn("Answer rejected: not supported by the retrieved documents");
            metrics.answerRejected();
            return notFound(degraded, usage);
        }

        List<SourceReference> sources = new ArrayList<>();
        for (int position : supporting) {
            sources.add(toSource(sources.size() + 1, answer, chunks.get(position)));
        }
        log.info("Answer: found, {} sources", sources.size());
        return new RagAnswer(answer, true, sources, degraded, usage);
    }

    /**
     * The search returned nothing. If the tenant has no processed document at all, say that, because "I could not
     * find this information" would send the user to rephrase a question when the real problem is an empty library.
     */
    private RagAnswer nothingFound(String tenantId, boolean searchWasLimited, TokenUsage usage) {
        if (!searchWasLimited && !documentRepository.existsByTenantIdAndStatus(tenantId, DocumentStatus.COMPLETED)) {
            log.info("Answer: the tenant has no processed documents");
            return new RagAnswer(NO_DOCUMENTS_ANSWER, false, List.of(), false, usage);
        }
        return notFound(searchWasLimited, usage);
    }

    private RagAnswer notFound(boolean searchWasLimited, TokenUsage usage) {
        String text = searchWasLimited ? AnswerChecker.NOT_FOUND_ANSWER + LIMITED_SEARCH_NOTE
                : AnswerChecker.NOT_FOUND_ANSWER;
        return new RagAnswer(text, false, List.of(), searchWasLimited, usage);
    }

    /**
     * The fallback when the language model is down: no written answer, but the passages that are
     * closest to the question, each with its document and page. The user can still read the policy.
     */
    private RagAnswer passagesInsteadOfAnswer(String question, ContextBuilder.BuiltContext context, TokenUsage usage) {
        List<RetrievedChunk> best = context.chunks().subList(0, Math.min(FALLBACK_PASSAGES, context.chunks().size()));

        StringBuilder text = new StringBuilder(FALLBACK_INTRO);
        List<SourceReference> sources = new ArrayList<>();
        for (RetrievedChunk chunk : best) {
            SourceReference source = toSource(sources.size() + 1, question, chunk);
            sources.add(source);
            text.append("\n\n").append(source.number()).append(". ").append(chunk.title())
                    .append(" (").append(ContextBuilder.location(chunk)).append("): \"")
                    .append(source.snippet()).append('"');
        }
        return new RagAnswer(text.toString(), true, sources, true, usage);
    }

    private SourceReference toSource(int number, String textToMatch, RetrievedChunk chunk) {
        String quote = attribution.bestPassage(textToMatch, chunk.text(), properties.snippetLength());
        return new SourceReference(number, chunk.documentId(), chunk.fileName(), chunk.title(),
                chunk.version(), chunk.pageNumber(), chunk.section(), quote, chunk.score());
    }
}
