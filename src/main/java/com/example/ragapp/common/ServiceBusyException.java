package com.example.ragapp.common;

/** Too many documents are being processed right now. Returned as HTTP 503. */
public class ServiceBusyException extends RuntimeException {

    public ServiceBusyException(String message) {
        super(message);
    }
}
