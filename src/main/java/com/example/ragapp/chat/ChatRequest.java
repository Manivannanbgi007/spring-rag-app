package com.example.ragapp.chat;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param question       the question
 * @param conversationId leave empty to start a new conversation, or send the id from an earlier
 *                       answer to ask a follow-up question
 */
public record ChatRequest(
        @NotBlank(message = "The question must not be empty.")
        @Size(max = 1000, message = "The question is too long (maximum 1000 characters).")
        String question,
        UUID conversationId) {
}
