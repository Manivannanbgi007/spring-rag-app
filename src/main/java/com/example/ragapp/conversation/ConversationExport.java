package com.example.ragapp.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A complete copy of one conversation, for the user to download. */
public record ConversationExport(
        UUID id,
        String title,
        Instant createdAt,
        Instant exportedAt,
        List<MessageResponse> messages) {
}
