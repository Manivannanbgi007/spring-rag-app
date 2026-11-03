package com.example.ragapp.conversation;

import java.time.Instant;
import java.util.UUID;

/** One line in the conversation list. "expired" means it can still be read but not continued. */
public record ConversationSummary(UUID id, String title, Instant createdAt, Instant lastActivityAt, boolean expired) {
}
