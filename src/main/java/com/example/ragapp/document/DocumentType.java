package com.example.ragapp.document;

import java.util.Optional;

/**
 * File types we can read. To support a new type later (for example HTML), add it here
 * and write one new DocumentParser class. Nothing else needs to change.
 */
public enum DocumentType {

    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final String extension;
    private final String contentType;

    DocumentType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String getExtension() {
        return extension;
    }

    public String getContentType() {
        return contentType;
    }

    public static Optional<DocumentType> fromFileName(String fileName) {
        String lower = fileName.toLowerCase();
        for (DocumentType type : values()) {
            if (lower.endsWith("." + type.extension)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public static Optional<DocumentType> fromContentType(String contentType) {
        for (DocumentType type : values()) {
            if (type.contentType.equals(contentType)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
