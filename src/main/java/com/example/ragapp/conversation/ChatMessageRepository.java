package com.example.ragapp.conversation;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByIdAsc(UUID conversationId);

    /** The newest messages first. Used to build the short history for follow-up questions. */
    List<ChatMessage> findByConversationIdOrderByIdDesc(UUID conversationId, Limit limit);
}
