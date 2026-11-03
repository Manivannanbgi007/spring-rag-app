package com.example.ragapp.document;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.ragapp.common.DocumentConflictException;
import com.example.ragapp.common.DocumentNotFoundException;
import com.example.ragapp.common.InvalidDocumentException;
import com.example.ragapp.common.ServiceBusyException;
import com.example.ragapp.ingestion.ChunkStore;
import com.example.ragapp.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Business logic for documents: upload, list, get and delete.
 * The slow work (reading and embedding the file) happens later in IngestionService.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final PolicyDocumentRepository repository;
    private final DocumentValidator validator;
    private final DocumentStorage storage;
    private final IngestionService ingestionService;
    private final ChunkStore chunkStore;

    public DocumentService(PolicyDocumentRepository repository, DocumentValidator validator,
                           DocumentStorage storage, IngestionService ingestionService,
                           ChunkStore chunkStore) {
        this.repository = repository;
        this.validator = validator;
        this.storage = storage;
        this.ingestionService = ingestionService;
        this.chunkStore = chunkStore;
    }

    /**
     * Steps: validate -> detect duplicate -> save file -> save database row (QUEUED)
     * -> start background processing -> return immediately.
     */
    public DocumentResponse upload(String tenantId, MultipartFile file, String title, String version) {
        validator.validateTenant(tenantId);
        DocumentType type = validator.validateFile(file);
        String fileName = validator.cleanFileName(file.getOriginalFilename());
        String resolvedTitle = validator.resolveTitle(title, fileName);
        String resolvedVersion = validator.validateVersion(version);

        String fileHash = sha256(file);
        PolicyDocument document = findOrCreate(tenantId, fileHash);

        document.setFileName(fileName);
        document.setTitle(resolvedTitle);
        document.setVersion(resolvedVersion);
        document.setContentType(type.getContentType());
        document.setFileSize(file.getSize());
        document.setFileHash(fileHash);
        document.setStoragePath(storage.save(file, tenantId, document.getId(), type));
        document.setStatus(DocumentStatus.QUEUED);
        document.setStatusMessage("Waiting to be processed");
        document.setPageCount(null);
        document.setChunkCount(null);

        PolicyDocument saved = saveOrReportDuplicate(document, tenantId, fileHash);
        startProcessing(saved);

        log.info("Document {} uploaded ({} bytes), queued for processing", saved.getId(), saved.getFileSize());
        return DocumentResponse.from(saved);
    }

    public List<DocumentResponse> list(String tenantId) {
        validator.validateTenant(tenantId);
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    public DocumentResponse get(String tenantId, UUID id) {
        validator.validateTenant(tenantId);
        return DocumentResponse.from(find(tenantId, id));
    }

    /** Deletes the database row, the chunks (vectors) and the saved file. */
    public void delete(String tenantId, UUID id) {
        validator.validateTenant(tenantId);
        PolicyDocument document = find(tenantId, id);

        if (document.getStatus().isInProgress()) {
            throw new DocumentConflictException(
                    "The document is still being processed. Try again when it is COMPLETED or FAILED.", id);
        }

        chunkStore.deleteByDocumentId(id);
        storage.delete(document.getStoragePath());
        repository.delete(document);
        log.info("Document {} deleted", id);
    }

    // ----- helpers -----

    private PolicyDocument find(String tenantId, UUID id) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    /**
     * Same file uploaded again?
     *  - a FAILED earlier attempt is retried (same row is reused)
     *  - anything else is a duplicate and is rejected, so we never store the same chunks twice
     */
    private PolicyDocument findOrCreate(String tenantId, String fileHash) {
        Optional<PolicyDocument> existing = repository.findByTenantIdAndFileHash(tenantId, fileHash);
        if (existing.isEmpty()) {
            return new PolicyDocument(UUID.randomUUID(), tenantId);
        }
        PolicyDocument found = existing.get();
        if (found.getStatus() == DocumentStatus.FAILED) {
            return found;
        }
        throw new DocumentConflictException("This file was already uploaded as '" + found.getTitle()
                + "' (status " + found.getStatus() + ").", found.getId());
    }

    // Two identical uploads at the same moment: the database unique constraint stops the second one
    private PolicyDocument saveOrReportDuplicate(PolicyDocument document, String tenantId, String fileHash) {
        try {
            return repository.saveAndFlush(document);
        } catch (DataIntegrityViolationException e) {
            storage.delete(document.getStoragePath());
            UUID existingId = repository.findByTenantIdAndFileHash(tenantId, fileHash)
                    .map(PolicyDocument::getId).orElse(null);
            throw new DocumentConflictException("This file was already uploaded.", existingId);
        }
    }

    private void startProcessing(PolicyDocument document) {
        try {
            ingestionService.ingestAsync(document.getId());
        } catch (TaskRejectedException e) {
            // The waiting line is full. Keep the row as FAILED so the user can simply upload again.
            document.setStatus(DocumentStatus.FAILED);
            document.setStatusMessage("The server is busy. Please upload the file again in a few minutes.");
            repository.save(document);
            throw new ServiceBusyException("Too many documents are being processed. Please try again shortly.");
        }
    }

    private String sha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new InvalidDocumentException("The uploaded file could not be read.");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
