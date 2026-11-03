package com.example.ragapp.chat;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.example.ragapp.conversation.ChatTurn;
import com.example.ragapp.conversation.MessageRole;
import com.example.ragapp.privacy.MaskingSession;
import com.example.ragapp.observability.RagMetrics;
import com.example.ragapp.privacy.PiiMasker;
import com.example.ragapp.resilience.ProtectedCalls;
import com.example.ragapp.retrieval.TextWords;
import com.example.ragapp.usage.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Follow-up questions: "What about sick leave?" means nothing on its own.
 * Searching the documents with those words would find nothing useful.
 *
 * So, when a conversation already has messages, the language model first rewrites the new
 * question into a complete one ("How many days of sick leave do employees get?").
 * The rewritten question is used for the document search. This is how memory helps:
 * it explains what the user means, but it never provides facts. Facts come from the documents.
 */
@Component
public class QuestionRewriter {

    private static final Logger log = LoggerFactory.getLogger(QuestionRewriter.class);

    private static final int MAX_TURN_CHARS = 300;
    private static final Duration REWRITE_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern LABEL = Pattern.compile(
            "^(rewritten question|rewritten|standalone question|follow-up question|question|user|assistant)\\s*:\\s*",
            Pattern.CASE_INSENSITIVE);

    private final ChatClient chatClient;
    private final PiiMasker piiMasker;
    private final ProtectedCalls protectedCalls;
    private final RagMetrics metrics;
    private final PromptTemplate template;

    /**
     * @param question the question to search with
     * @param usage    tokens the rewriting itself used (none when the question was not rewritten)
     */
    public record RewriteResult(String question, TokenUsage usage) {
    }

    public QuestionRewriter(@Qualifier("rewriteChatClient") ChatClient chatClient, PiiMasker piiMasker,
                            ProtectedCalls protectedCalls, RagMetrics metrics,
                            @Value("classpath:prompts/rewrite-question.st") Resource prompt) {
        this.chatClient = chatClient;
        this.piiMasker = piiMasker;
        this.protectedCalls = protectedCalls;
        this.metrics = metrics;
        this.template = new PromptTemplate(prompt);
    }

    /** Returns a question that can be understood on its own. Falls back to the original question. */
    public RewriteResult rewrite(List<ChatTurn> history, String question) {
        if (history.isEmpty()) {
            return new RewriteResult(question, TokenUsage.NONE);   // first question: nothing to resolve
        }
        try {
            // Sensitive data is masked before the model sees it, and restored in its answer
            MaskingSession pii = piiMasker.newSession();
            String prompt = template.render(Map.of(
                    "history", pii.mask(formatHistory(history)),
                    "question", pii.mask(question)));
            // A short time limit: the rewrite is only a helper, so it must never hold up the real answer
            org.springframework.ai.chat.model.ChatResponse response = metrics.stage("rewrite",
                    () -> protectedCalls.llm().call(
                            () -> chatClient.prompt().user(prompt).call().chatResponse(), REWRITE_TIMEOUT));
            String modelOutput = pii.restore(response.getResult().getOutput().getText());

            String rewritten = pickSafeResult(modelOutput, question, history);
            log.info("Follow-up question {}", rewritten.equals(question) ? "kept as is" : "rewritten");
            org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
            return new RewriteResult(rewritten, TokenUsage.ofLanguageModel(usage.getPromptTokens(), usage.getCompletionTokens()));
        } catch (RuntimeException e) {
            // Graceful degradation: without the rewrite the search still works, just less well
            log.warn("Could not rewrite the follow-up question, using it unchanged: {}", e.getMessage());
            return new RewriteResult(question, TokenUsage.NONE);
        }
    }

    /**
     * The model output is not trusted either: take the first line, remove labels and quotes,
     * and use the original question when the result is empty or much too long.
     */
    static String pickSafeResult(String modelOutput, String original) {
        return pickSafeResult(modelOutput, original, List.of());
    }

    /** Same, and with the earlier messages of the conversation, to catch the model repeating or losing the question. */
    static String pickSafeResult(String modelOutput, String original, List<ChatTurn> history) {
        if (modelOutput == null) {
            return original;
        }
        String firstLine = modelOutput.strip().lines().findFirst().orElse("").strip();
        String cleaned = LABEL.matcher(firstLine).replaceFirst("").strip();
        cleaned = cleaned.replaceAll("^[\"'](.*)[\"']$", "$1").strip();

        boolean tooLong = cleaned.length() > Math.max(300, original.length() * 3);

        // Small models sometimes ANSWER instead of rewriting. A question stays a question.
        boolean turnedIntoStatement = original.contains("?") && !cleaned.contains("?");

        // ... or they talk about the conversation ("Is there a dress code mentioned in the conversation?")
        boolean mentionsConversation = cleaned.toLowerCase().contains("conversation")
                && !original.toLowerCase().contains("conversation");

        if (cleaned.isBlank() || tooLong || turnedIntoStatement || mentionsConversation) {
            return original;
        }
        if (repeatsAnEarlierQuestion(cleaned, original, history) || dropsWhatTheUserAsked(cleaned, original, history)) {
            return original;
        }
        return cleaned;
    }

    /**
     * Found by the quality evaluation (case F01): asked "And how long is maternity leave?" after a question about
     * sick leave, the model "rewrote" it as the sick leave question, a copy of the earlier question.
     */
    private static boolean repeatsAnEarlierQuestion(String rewritten, String original, List<ChatTurn> history) {
        String rewrittenKey = normalize(rewritten);
        if (rewrittenKey.equals(normalize(original))) {
            return false;   // unchanged is fine
        }
        return history.stream()
                .filter(turn -> turn.role() == MessageRole.USER)
                .anyMatch(turn -> normalize(turn.content()).equals(rewrittenKey));
    }

    /**
     * The words of the new question that do not occur anywhere in the conversation so far are what the user
     * really asks for ("maternity", "carry over"). A rewrite that contains NONE of them lost the question.
     */
    private static boolean dropsWhatTheUserAsked(String rewritten, String original, List<ChatTurn> history) {
        if (history.isEmpty()) {
            return false;
        }
        Set<String> conversationWords = new HashSet<>();
        history.forEach(turn -> conversationWords.addAll(TextWords.words(turn.content())));
        Set<String> newWords = new HashSet<>(TextWords.words(original));
        newWords.removeAll(conversationWords);
        return !newWords.isEmpty() && Collections.disjoint(newWords, TextWords.words(rewritten));
    }

    private static String normalize(String text) {
        return text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static String formatHistory(List<ChatTurn> history) {
        StringBuilder text = new StringBuilder();
        for (ChatTurn turn : history) {
            String label = turn.role() == MessageRole.USER ? "User" : "Assistant";
            String content = turn.content().replaceAll("\\s+", " ").strip();
            if (content.length() > MAX_TURN_CHARS) {
                content = content.substring(0, MAX_TURN_CHARS) + "...";
            }
            text.append(label).append(": ").append(content).append('\n');
        }
        return text.toString().strip();
    }
}
