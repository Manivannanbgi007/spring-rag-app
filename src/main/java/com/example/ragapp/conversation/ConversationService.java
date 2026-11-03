package com.example.ragapp.conversation;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.ragapp.common.Caller;
import com.example.ragapp.common.ConversationExpiredException;
import com.example.ragapp.common.ConversationNotFoundException;
import com.example.ragapp.common.SourceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores and reads conversations. Every method takes the Caller, and every lookup includes
 * tenant and user, so nobody can reach someone else's conversation.
 *
 * Memory versus RAG: the saved messages are only used to understand follow-up questions.
 * Facts always come from the documents (RAG), never from earlier answers.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final int MAX_TITLE_LENGTH = 80;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final FeedbackRepository feedbackRepository;
    private final ConversationProperties properties;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationRepository conversations, ChatMessageRepository messages,
                               FeedbackRepository feedbackRepository, ConversationProperties properties,
                               ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.feedbackRepository = feedbackRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * The state of a conversation at the start of a request.
     * Nothing is saved yet: a new conversation is only stored once its first answer exists,
     * so a failed request leaves no empty conversation behind.
     */
    public record OpenConversation(UUID id, boolean isNew, List<ChatTurn> history) {
    }

    /** Starts a new conversation (id = null) or continues an existing one. */
    public OpenConversation open(Caller caller, UUID conversationId) {
        if (conversationId == null) {
            return new OpenConversation(UUID.randomUUID(), true, List.of());
        }
        Conversation conversation = find(caller, conversationId);
        if (isExpired(conversation)) {
            throw new ConversationExpiredException();
        }

        List<ChatMessage> recent = messages.findByConversationIdOrderByIdDesc(
                conversationId, Limit.of(properties.historyMessages()));
        Collections.reverse(recent);   // oldest first
        List<ChatTurn> history = recent.stream()
                .map(message -> new ChatTurn(message.getRole(), message.getContent()))
                .toList();
        return new OpenConversation(conversationId, false, history);
    }

    /** Saves the question and its answer. Returns the id of the answer (used for feedback). */
    @Transactional
    public long saveExchange(Caller caller, OpenConversation open, String question, String standaloneQuestion,
                             String answer, boolean found, List<SourceReference> sources) {
        Conversation conversation = open.isNew()
                ? new Conversation(open.id(), caller.tenantId(), caller.userId(), titleFrom(question))
                : find(caller, open.id());
        conversation.touch();
        conversations.save(conversation);

        messages.save(ChatMessage.question(open.id(), question,
                standaloneQuestion.equals(question) ? null : standaloneQuestion));
        ChatMessage saved = messages.save(ChatMessage.answer(open.id(), answer, found, toJson(sources)));
        return saved.getId();
    }

    public List<ConversationSummary> list(Caller caller) {
        return conversations.findByTenantIdAndOwnerIdOrderByLastActivityAtDesc(caller.tenantId(), caller.userId())
                .stream()
                .map(c -> new ConversationSummary(c.getId(), c.getTitle(), c.getCreatedAt(),
                        c.getLastActivityAt(), isExpired(c)))
                .toList();
    }

    public List<MessageResponse> getMessages(Caller caller, UUID conversationId) {
        find(caller, conversationId);   // checks that it exists and belongs to the caller
        List<ChatMessage> history = messages.findByConversationIdOrderByIdAsc(conversationId);

        Map<Long, FeedbackRating> ratings = feedbackRepository
                .findByMessageIdIn(history.stream().map(ChatMessage::getId).toList()).stream()
                .collect(Collectors.toMap(Feedback::getMessageId, Feedback::getRating, (a, b) -> b));

        return history.stream()
                .map(m -> new MessageResponse(m.getId(), m.getRole(), m.getContent(), m.getFound(),
                        fromJson(m.getSourcesJson()), m.getStandaloneQuestion(), ratings.get(m.getId()),
                        m.getCreatedAt()))
                .toList();
    }

    public ConversationExport export(Caller caller, UUID conversationId) {
        Conversation conversation = find(caller, conversationId);
        return new ConversationExport(conversation.getId(), conversation.getTitle(), conversation.getCreatedAt(),
                Instant.now(), getMessages(caller, conversationId));
    }

    /** Deletes the conversation. The database removes its messages and feedback too. */
    public void delete(Caller caller, UUID conversationId) {
        conversations.delete(find(caller, conversationId));
        log.info("Conversation {} deleted by its owner", conversationId);
    }

    /** Used by the retention job: deletes conversations that have been idle for too long. */
    public int deleteIdleConversations() {
        Instant cutoff = Instant.now().minusSeconds(properties.retentionDays() * 24L * 3600L);
        return conversations.deleteIdleSince(cutoff);
    }

    // ----- helpers -----

    /** The same "not found" error for a missing conversation and for someone else's conversation. */
    private Conversation find(Caller caller, UUID conversationId) {
        return conversations.findByIdAndTenantIdAndOwnerId(conversationId, caller.tenantId(), caller.userId())
                .orElseThrow(() -> new ConversationNotFoundException("Conversation not found: " + conversationId));
    }

    private boolean isExpired(Conversation conversation) {
        return conversation.getLastActivityAt().plus(properties.sessionTimeout()).isBefore(Instant.now());
    }

    private String titleFrom(String question) {
        String oneLine = question.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= MAX_TITLE_LENGTH ? oneLine : oneLine.substring(0, MAX_TITLE_LENGTH) + "...";
    }

    private String toJson(List<SourceReference> sources) {
        return objectMapper.writeValueAsString(sources);
    }

    private List<SourceReference> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;   // questions have no sources
        }
        return objectMapper.readValue(json, new TypeReference<List<SourceReference>>() { });
    }
}
