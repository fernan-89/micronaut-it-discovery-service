package com.thinklab.domain.repository;

import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound Port for DiscoveredItem persistence operations (IT Discovery Service Domain).
 * Part of the pure Domain Layer.
 *
 * <p>ARCHITECTURAL RULE: Partial State Mutations (ADR-002). Monolithic save operations are reserved
 * for aggregate creation. Every state transition is a granular update that atomically appends its
 * forensic {@link DiscoveredItemAuditEntry} to the ledger, so the audit trail can never diverge from
 * state. There is no {@code deleteById} — items only move through the lifecycle (ADR-013).
 */
public interface DiscoveredItemRepository {

    Mono<DiscoveredItem> create(DiscoveredItem item);

    Mono<DiscoveredItem> findById(UUID id);

    /**
     * The idempotent-ingestion lookup key. Called by {@code InitiateDiscoveredItemUseCase} before
     * every ingest to decide whether this is a brand-new item or a re-sighting of an existing one.
     */
    Mono<DiscoveredItem> findByOrganisationIdAndSourceAndExternalKey(UUID organisationId, String source, String externalKey);

    /**
     * Tenant-scoped listing of DiscoveredItems belonging to a given Organisation.
     *
     * @param organisationId    the tenant boundary
     * @param status            optional status filter ({@code null} = any)
     * @param source            optional source filter ({@code null} = any)
     * @param suggestedCategory optional category filter ({@code null} = any)
     */
    Flux<DiscoveredItem> findAllByOrganisationId(UUID organisationId, DiscoveredItemStatus status, String source,
                                                  AssetCategory suggestedCategory);

    Mono<Void> updateReseen(UUID id, Map<String, String> rawAttributes, Instant lastSeenAt, DiscoveredItemAuditEntry auditEntry);

    /**
     * The writes below are guarded (platform race audit): each applies only if the item still has the status the caller read, and a miss is
     * {@link com.thinklab.domain.exception.InvalidDiscoveredItemStatusException} (409) when the item exists, so two people acting on one item at
     * once cannot both win; an item that does not exist is {@link com.thinklab.domain.exception.DiscoveredItemNotFoundException}.
     */
    Mono<Void> updateReview(UUID id, DiscoveredItemStatus expectedStatus, AssetCategory suggestedCategory, UUID matchedAssetId, DiscoveredItemAuditEntry auditEntry);

    Mono<Void> updateStatus(UUID id, DiscoveredItemStatus expectedStatus, DiscoveredItemStatus status, DiscoveredItemAuditEntry auditEntry);

    /** Takes the promotion reservation: only if the item still has the status <b>and</b> the {@code updatedAt} that were read (no one touched it since). */
    Mono<Void> reservePromotion(UUID id, DiscoveredItemStatus expectedStatus, Instant expectedUpdatedAt, DiscoveredItemAuditEntry auditEntry);

    Mono<Void> updatePromotion(UUID id, DiscoveredItemStatus expectedStatus, DiscoveredItemStatus status, UUID promotedAssetId, DiscoveredItemAuditEntry auditEntry);
}
