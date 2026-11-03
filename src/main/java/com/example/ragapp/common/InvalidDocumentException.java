package com.example.ragapp.common;

/** The uploaded file is not acceptable (wrong type, empty, too long ...). Returned as HTTP 400. */
public class InvalidDocumentException extends InvalidRequestException {

    public InvalidDocumentException(String message) {
        super(message);
    }
}
