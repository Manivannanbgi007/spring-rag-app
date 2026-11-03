package com.example.ragapp.chat;

import java.util.List;

import com.example.ragapp.common.SourceReference;
import com.example.ragapp.usage.TokenUsage;

/**
 * What the RAG step produces (internal). ChatService adds the conversation details
 * and returns a ChatResponse to the caller.
 *
 * @param found    false when the documents do not contain the answer (then sources is empty)
 * @param sources  the document passages the answer is based on
 * @param degraded true when a part of the system was not available and the answer is therefore
 *                 limited (for example: the language model is down, so the answer is a list of passages)
 * @param usage    tokens used by the search and the answer
 */
public record RagAnswer(String answer, boolean found, List<SourceReference> sources, boolean degraded,
                        TokenUsage usage) {
}
