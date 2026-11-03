package com.example.ragapp.conversation;

public record FeedbackResponse(Long messageId, FeedbackRating rating, String comment, boolean reportedIncorrect) {
}
