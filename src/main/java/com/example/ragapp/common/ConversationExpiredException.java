package com.example.ragapp.common;

/** The conversation was idle for too long and cannot be continued. Returned as HTTP 410. */
public class ConversationExpiredException extends RuntimeException {

    public ConversationExpiredException() {
        super("This conversation has expired. Please start a new conversation.");
    }
}
