package com.example.ragapp.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    // Tenant AND owner are part of every lookup: users can only reach their own conversations
    Optional<Conversation> findByIdAndTenantIdAndOwnerId(UUID id, String tenantId, String ownerId);

    List<Conversation> findByTenantIdAndOwnerIdOrderByLastActivityAtDesc(String tenantId, String ownerId);

    /** One SQL statement. The database deletes the messages and feedback too (ON DELETE CASCADE). */
    @Modifying
    @Transactional
    @Query("delete from Conversation c where c.lastActivityAt < :cutoff")
    int deleteIdleSince(@Param("cutoff") Instant cutoff);
}
