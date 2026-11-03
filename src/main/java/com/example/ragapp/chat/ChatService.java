package com.example.ragapp.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.ragapp.chat.QuestionRewriter.RewriteResult;
import com.example.ragapp.common.Caller;
import com.example.ragapp.conversation.ConversationService;
import com.example.ragapp.conversation.ConversationService.OpenConversation;
import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.usage.CostCalculator;
import com.example.ragapp.usage.TokenUsage;
import com.example.ragapp.usage.UsageInfo;
import com.example.ragapp.usage.UsageService;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Puts one chat request together:
 *
 *   open the conversation  ->  rewrite follow-up question  ->  RAG answer  ->  save the exchange
 *
 * RagChatService knows nothing about conversations. ConversationService knows nothing about RAG.
 * This class connects them. It is also the place where one request is measured as a whole:
 * the timer and trace of the request (rag.chat), the tokens and the estimated cost, the usage record
 * for the tenant report, and ONE summary line in the log.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ConversationService conversationService;
    private final QuestionRewriter questionRewriter;
    private final RagChatService ragChatService;
    private final RagMetrics metrics;
    private final UsageService usageService;
    private final CostCalculator costCalculator;

    public ChatService(ConversationService conversationService, QuestionRewriter questionRewriter,
                       RagChatService ragChatService, RagMetrics metrics, UsageService usageService,
                       CostCalculator costCalculator) {
        this.conversationService = conversationService;
        this.questionRewriter = questionRewriter;
        this.ragChatService = ragChatService;
        this.metrics = metrics;
        this.usageService = usageService;
        this.costCalculator = costCalculator;
    }

    /** Waits for the complete answer. */
    public ChatResponse ask(Caller caller, ChatRequest request) {
        long startNanos = System.nanoTime();
        RagMetrics.ChatObservation chat = metrics.startChat("blocking");
        try (Observation.Scope scope = chat.open()) {
            String question = request.question().strip();
            OpenConversation conversation = conversationService.open(caller, request.conversationId());

            RewriteResult rewrite = questionRewriter.rewrite(conversation.history(), question);
            RagAnswer answer = ragChatService.answer(caller.tenantId(), rewrite.question());
            return finish(caller, conversation, question, rewrite, answer, startNanos, chat);
        } catch (RuntimeException e) {
            chat.fail(e);
            throw e;
        }
    }

    /** Streams the answer. See ChatEvent for the events that are sent. */
    public Flux<ChatEvent> stream(Caller caller, ChatRequest request) {
        String question = request.question().strip();

        // Done now, before streaming starts, so "not found" or "expired" become normal HTTP errors
        OpenConversation conversation = conversationService.open(caller, request.conversationId());

        return Flux.defer(() -> {
            long startNanos = System.nanoTime();
            RagMetrics.ChatObservation chat = metrics.startChat("stream");

            return chat.inContext(Flux.defer(() -> {
                RewriteResult rewrite = questionRewriter.rewrite(conversation.history(), question);

                // Tell the user how a follow-up was understood (only when it changed)
                Flux<ChatEvent> rewrittenQuestion = rewrite.question().equals(question)
                        ? Flux.empty()
                        : Flux.just(ChatEvent.question(rewrite.question()));

                Flux<ChatEvent> answer = ragChatService.stream(caller.tenantId(), rewrite.question())
                        .publishOn(Schedulers.boundedElastic())   // saving to the database blocks
                        .map(event -> event.data() instanceof RagAnswer ragAnswer
                                ? ChatEvent.done(finish(caller, conversation, question, rewrite, ragAnswer, startNanos, chat))
                                : event);

                return rewrittenQuestion.concatWith(answer);
            }))
            .subscribeOn(Schedulers.boundedElastic())   // rewriting calls the model and blocks
            .doOnError(chat::fail)
            .doOnCancel(chat::cancelled)
            .doFinally(signal -> chat.finish("error", false));   // only counts when nothing ended the request before
        })
        .onErrorResume(error -> {
            log.error("Chat stream failed", error);
            return Flux.just(ChatEvent.error("The answer could not be generated right now. Please try again."));
        });
    }

    /** Saves the exchange and the usage, records the metrics and builds the response. */
    private ChatResponse finish(Caller caller, OpenConversation conversation, String question, RewriteResult rewrite,
                                RagAnswer answer, long startNanos, RagMetrics.ChatObservation chat) {
        TokenUsage usage = rewrite.usage().plus(answer.usage());
        double cost = costCalculator.estimate(usage);
        String outcome = !answer.found() ? "not_found" : answer.degraded() ? "degraded" : "answered";

        long messageId = conversationService.saveExchange(caller, conversation, question, rewrite.question(),
                answer.answer(), answer.found(), answer.sources());

        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        usageService.record(caller.tenantId(), outcome, usage, cost, durationMs);
        metrics.tokens(caller.tenantId(), "prompt", usage.promptTokens());
        metrics.tokens(caller.tenantId(), "completion", usage.completionTokens());
        metrics.tokens(caller.tenantId(), "embedding", usage.embeddingTokens());
        metrics.cost(caller.tenantId(), cost);
        chat.finish(outcome, answer.degraded());

        // ONE line per question, with everything an operator needs, and nothing personal:
        // no question, no answer, no document text, no user id (only a short hash of it).
        log.atInfo()
                .addKeyValue("tenant", caller.tenantId())
                .addKeyValue("user", shortHash(caller.userId()))
                .addKeyValue("conversationId", conversation.id())
                .addKeyValue("outcome", outcome)
                .addKeyValue("degraded", answer.degraded())
                .addKeyValue("sources", answer.sources().size())
                .addKeyValue("durationMs", durationMs)
                .addKeyValue("promptTokens", usage.promptTokens())
                .addKeyValue("completionTokens", usage.completionTokens())
                .addKeyValue("embeddingTokens", usage.embeddingTokens())
                .addKeyValue("costUsd", cost)
                .log("Chat request completed: outcome={} conversation={} sources={} durationMs={} tokens(prompt/completion/embedding)={}/{}/{}",
                        outcome, conversation.id(), answer.sources().size(), durationMs,
                        usage.promptTokens(), usage.completionTokens(), usage.embeddingTokens());

        return new ChatResponse(conversation.id(), messageId, rewrite.question(), answer.answer(), answer.found(),
                answer.degraded(), answer.sources(), UsageInfo.of(usage, cost));
    }

    /** A short, one-way fingerprint of the user id: logs can show "the same user" without naming the person. */
    private static String shortHash(String userId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(userId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
