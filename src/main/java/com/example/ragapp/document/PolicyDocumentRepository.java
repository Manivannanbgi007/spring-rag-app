package com.example.ragapp.document;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Database access for the documents table. Spring creates the implementation for us. */
public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {

    // Every lookup includes the tenant, so one tenant can never see another tenant's documents
    Optional<PolicyDocument> findByIdAndTenantId(UUID id, String tenantId);

    Optional<PolicyDocument> findByTenantIdAndFileHash(String tenantId, String fileHash);

    List<PolicyDocument> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    boolean existsByTenantIdAndStatus(String tenantId, DocumentStatus status);

    List<PolicyDocument> findByStatusIn(Collection<DocumentStatus> statuses);
}
