package com.example.ragapp.conversation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    Optional<Feedback> findByMessageId(Long messageId);

    List<Feedback> findByMessageIdIn(Collection<Long> messageIds);
}
