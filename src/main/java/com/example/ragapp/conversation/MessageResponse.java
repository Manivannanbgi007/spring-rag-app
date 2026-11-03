package com.example.ragapp.conversation;

import java.time.Instant;
import java.util.List;

import com.example.ragapp.common.SourceReference;

/**
 * One message as returned by the API.
 * For answers: "found", "sources" and the user's "feedback" (if any) are filled in.
 * For questions: "standaloneQuestion" shows how a follow-up was rewritten (if it was).
 */
public record MessageResponse(
        Long id,
        MessageRole role,
        String content,
        Boolean found,
        List<SourceReference> sources,
        String standaloneQuestion,
        FeedbackRating feedback,
        Instant createdAt) {
}
