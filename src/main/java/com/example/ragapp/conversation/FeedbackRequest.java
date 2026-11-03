package com.example.ragapp.conversation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param rating            UP (helpful) or DOWN (not helpful)
 * @param comment           optional free text
 * @param reportedIncorrect true when the user says the answer is wrong
 */
public record FeedbackRequest(
        @NotNull(message = "The rating is required (UP or DOWN).")
        FeedbackRating rating,
        @Size(max = 1000, message = "The comment is too long (maximum 1000 characters).")
        String comment,
        boolean reportedIncorrect) {
}
