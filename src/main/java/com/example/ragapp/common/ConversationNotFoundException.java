package com.example.ragapp.common;

/**
 * No such conversation, message or answer for this user. Returned as HTTP 404.
 * The same error is used when it exists but belongs to someone else,
 * so nobody can find out which ids exist.
 */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(String message) {
        super(message);
    }
}
