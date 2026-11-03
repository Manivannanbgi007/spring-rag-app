package com.example.ragapp.common;

/** The caller sent something we cannot accept (bad header, bad value ...). Returned as HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
