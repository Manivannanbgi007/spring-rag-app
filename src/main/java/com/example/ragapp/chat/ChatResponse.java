package com.example.ragapp.chat;

import java.util.List;
import java.util.UUID;

import com.example.ragapp.common.SourceReference;
import com.example.ragapp.usage.UsageInfo;

/**
 * The answer returned to the user.
 *
 * @param conversationId     send this back with the next question to continue the conversation
 * @param messageId          id of this answer; use it to send feedback
 * @param standaloneQuestion the question as it was searched, after follow-up rewriting
 *                           (the same as the question when it was not a follow-up)
 * @param found              false when the documents do not contain the answer (then sources is empty)
 * @param degraded           true when a part of the system was not available, so the answer is limited:
 *                           for example the language model was down and the answer is a list of passages
 *                           from the documents, or the meaning search was down and only keywords were used
 * @param sources            the document passages the answer is based on
 * @param usage              tokens used by this question and the estimated cost
 */
public record ChatResponse(
        UUID conversationId,
        long messageId,
        String standaloneQuestion,
        String answer,
        boolean found,
        boolean degraded,
        List<SourceReference> sources,
        UsageInfo usage) {
}
