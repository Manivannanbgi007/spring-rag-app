package com.example.ragapp.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Saves the original uploaded files on disk.
 * Files are named after the document id (never the user's file name), which prevents
 * path tricks and name clashes. To use S3 or similar later, only this class changes.
 */
@Component
public class DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorage.class);

    private final Path root;

    public DocumentStorage(DocumentProperties properties) {
        this.root = Path.of(properties.uploadDir()).toAbsolutePath().normalize();
    }

    /** Saves the file and returns its path relative to the upload folder. */
    public String save(MultipartFile file, String tenantId, UUID documentId, DocumentType type) {
        String relativePath = tenantId + "/" + documentId + "." + type.getExtension();
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return relativePath;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the uploaded file", e);
        }
    }

    public Path resolve(String relativePath) {
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage path");
        }
        return path;
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            // A leftover file is not worth failing the request for
            log.warn("Could not delete file {}", relativePath, e);
        }
    }
}
