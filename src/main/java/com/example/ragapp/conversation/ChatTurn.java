package com.example.ragapp.conversation;

/** One earlier message, used as memory to understand a follow-up question. */
public record ChatTurn(MessageRole role, String content) {
}
