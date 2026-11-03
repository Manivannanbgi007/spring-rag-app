package com.example.ragapp.conversation;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One question or one answer inside a conversation (a row in "chat_messages"). */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // the database numbers the messages 1, 2, 3 ...
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Column(nullable = false)
    private String content;

    private Boolean found;

    @Column(name = "standalone_question")
    private String standaloneQuestion;

    @Column(name = "sources_json")
    private String sourcesJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ChatMessage() {
        // required by JPA
    }

    public static ChatMessage question(UUID conversationId, String question, String standaloneQuestion) {
        ChatMessage message = new ChatMessage();
        message.conversationId = conversationId;
        message.role = MessageRole.USER;
        message.content = question;
        message.standaloneQuestion = standaloneQuestion;
        return message;
    }

    public static ChatMessage answer(UUID conversationId, String answer, boolean found, String sourcesJson) {
        ChatMessage message = new ChatMessage();
        message.conversationId = conversationId;
        message.role = MessageRole.ASSISTANT;
        message.content = answer;
        message.found = found;
        message.sourcesJson = sourcesJson;
        return message;
    }

    public Long getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public MessageRole getRole() { return role; }
    public String getContent() { return content; }
    public Boolean getFound() { return found; }
    public String getStandaloneQuestion() { return standaloneQuestion; }
    public String getSourcesJson() { return sourcesJson; }
    public Instant getCreatedAt() { return createdAt; }
}
