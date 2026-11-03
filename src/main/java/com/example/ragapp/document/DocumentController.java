package com.example.ragapp.document;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.example.ragapp.common.Caller;
import com.example.ragapp.resilience.RateLimitService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST API for documents.
 * "Caller" comes from the X-Tenant-Id header (default "default"). Documents belong to a tenant.
 * There is no login yet; real authentication can be added later.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final RateLimitService rateLimitService;

    public DocumentController(DocumentService documentService, RateLimitService rateLimitService) {
        this.documentService = documentService;
        this.rateLimitService = rateLimitService;
    }

    /** Upload a PDF or DOCX. Returns 202 Accepted: processing continues in the background. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            Caller caller,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "version", defaultValue = "1.0") String version) {

        rateLimitService.checkUpload(caller);
        DocumentResponse response = documentService.upload(caller.tenantId(), file, title, version);
        return ResponseEntity.accepted()
                .location(URI.create("/api/documents/" + response.id()))
                .body(response);
    }

    @GetMapping
    public List<DocumentResponse> list(Caller caller) {
        return documentService.list(caller.tenantId());
    }

    /** Poll this to follow the processing status of one document. */
    @GetMapping("/{id}")
    public DocumentResponse get(Caller caller, @PathVariable UUID id) {
        return documentService.get(caller.tenantId(), id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Caller caller, @PathVariable UUID id) {
        documentService.delete(caller.tenantId(), id);
        return ResponseEntity.noContent().build();
    }
}
