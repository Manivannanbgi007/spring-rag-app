package com.example.ragapp.common;

/** The language model, embedding model or vector database could not be reached. HTTP 503. */
public class AiServiceUnavailableException extends RuntimeException {

    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
