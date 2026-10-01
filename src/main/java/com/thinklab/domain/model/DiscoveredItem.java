package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidDiscoveredItemStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Core Domain Model representing the DiscoveredItem Aggregate Root.
 *
 * <p><b>BIAN Alignment (ADR-013):</b> This is the Control Record of the {@code it-discovery} Service
 * Domain — a staged, human-reviewed candidate for promotion into the IT Asset Registry. v1 is a
 * staging area with manual promotion: a future automated collector would call the same
 * {@code initiate} endpoint a human does today (ADR-030).
 *
 * <p><b>Lifecycle (ADR-031):</b> {@code DISCOVERED -> UNDER_REVIEW -> PROMOTED|IGNORED}. Promotion is
 * only legal from {@code UNDER_REVIEW} — a human must claim the item (optionally setting
 * {@code suggestedCategory}/{@code matchedAssetId}) before it can become a real Asset. Re-ingesting the
 * same {@code (organisationId, source, externalKey)} never resurrects a terminal item; it only updates
 * {@code lastSeenAt}/{@code rawAttributes} and records a {@code RESEEN} audit entry.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class DiscoveredItem {

    private final UUID id;
    private final UUID organisationId;
    private final String source;
    private final String externalKey;
    private String name;
    private AssetCategory suggestedCategory;
    private Map<String, String> rawAttributes;
    private UUID matchedAssetId;
    private UUID promotedAssetId;
    private DiscoveredItemStatus status;
    private final Instant firstSeenAt;
    private Instant lastSeenAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<DiscoveredItemAuditEntry> auditTrail;

    private DiscoveredItem(UUID id, UUID organisationId, String source, String externalKey, String name,
                           Map<String, String> rawAttributes, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.source = source;
        this.externalKey = externalKey;
        this.name = name;
        this.rawAttributes = copyAttributes(rawAttributes);
        this.status = DiscoveredItemStatus.DISCOVERED;
        this.firstSeenAt = Instant.now();
        this.lastSeenAt = this.firstSeenAt;
        this.createdAt = this.firstSeenAt;
        this.updatedAt = this.firstSeenAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new DiscoveredItemAuditEntry(this.createdAt, "DISCOVERED", executor, null,
                DiscoveredItemStatus.DISCOVERED, "Item discovered and staged for review."));
    }

    private DiscoveredItem(UUID id, UUID organisationId, String source, String externalKey, String name,
                           AssetCategory suggestedCategory, Map<String, String> rawAttributes, UUID matchedAssetId,
                           UUID promotedAssetId, DiscoveredItemStatus status, Instant firstSeenAt, Instant lastSeenAt,
                           Instant createdAt, Instant updatedAt, List<DiscoveredItemAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.source = source;
        this.externalKey = externalKey;
        this.name = name;
        this.suggestedCategory = suggestedCategory;
        this.rawAttributes = copyAttributes(rawAttributes);
        this.matchedAssetId = matchedAssetId;
        this.promotedAssetId = promotedAssetId;
        this.status = status != null ? status : DiscoveredItemStatus.DISCOVERED;
        this.firstSeenAt = firstSeenAt != null ? firstSeenAt : Instant.now();
        this.lastSeenAt = lastSeenAt != null ? lastSeenAt : this.firstSeenAt;
        this.createdAt = createdAt != null ? createdAt : this.firstSeenAt;
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    /**
     * Static factory for aggregate creation (BIAN Behavior Qualifier: {@code initiate}). The sovereign
     * UUID must be provided by the orchestration layer after calling the Hash Token Registry.
     */
    public static DiscoveredItem ingest(UUID id, UUID organisationId, String source, String externalKey, String name,
                                        Map<String, String> rawAttributes, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for DiscoveredItem ingestion.");
        }
        if (source == null || source.isBlank() || externalKey == null || externalKey.isBlank()) {
            throw new IllegalArgumentException("Source and External Key are mandatory for DiscoveredItem ingestion.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is mandatory for DiscoveredItem ingestion.");
        }
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable DiscoveredItem ingestion.");
        }
        return new DiscoveredItem(id, organisationId, source, externalKey, name, rawAttributes, executor);
    }

    /**
     * Reconstitutes an existing DiscoveredItem aggregate from the persistence layer.
     */
    public static DiscoveredItem reconstitute(UUID id, UUID organisationId, String source, String externalKey,
                                              String name, AssetCategory suggestedCategory, Map<String, String> rawAttributes,
                                              UUID matchedAssetId, UUID promotedAssetId, DiscoveredItemStatus status,
                                              Instant firstSeenAt, Instant lastSeenAt, Instant createdAt, Instant updatedAt,
                                              List<DiscoveredItemAuditEntry> auditTrail) {
        if (id == null || organisationId == null || source == null || externalKey == null || name == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Source, External Key, and Name are mandatory to reconstitute a DiscoveredItem.");
        }
        return new DiscoveredItem(id, organisationId, source, externalKey, name, suggestedCategory, rawAttributes,
                matchedAssetId, promotedAssetId, status, firstSeenAt, lastSeenAt, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors (State Mutations) ---

    /**
     * Idempotent re-ingestion of the same {@code (organisationId, source, externalKey)}. Always
     * updates {@code lastSeenAt}/{@code rawAttributes} and records a {@code RESEEN} entry — regardless
     * of the current status — but never changes {@code status}: a terminal item stays terminal.
     */
    public DiscoveredItemAuditEntry reseen(Map<String, String> newRawAttributes, String executor) {
        requireExecutor(executor);
        this.rawAttributes = copyAttributes(newRawAttributes);
        this.lastSeenAt = Instant.now();
        this.updatedAt = this.lastSeenAt;
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(this.updatedAt, "RESEEN", executor, this.status,
                this.status, "Item re-detected by source; attributes refreshed.");
        this.auditTrail.add(entry);
        return entry;
    }

    /**
     * Behavior Qualifier: {@code review/claim}. A human claims a freshly discovered item for review.
     */
    public DiscoveredItemAuditEntry claim(String executor) {
        requireExecutor(executor);
        if (this.status != DiscoveredItemStatus.DISCOVERED) {
            throw new InvalidDiscoveredItemStatusException(String.format(
                    "Compliance Violation: only a DISCOVERED item can be claimed for review; current status is [%s].", this.status));
        }
        return transition(DiscoveredItemStatus.UNDER_REVIEW, "CLAIMED", executor, "Item claimed for review.");
    }

    /**
     * Behavior Qualifier: {@code review/update}. Records the reviewer's findings — a suggested Asset
     * category and/or a matching existing Asset — without yet promoting the item. Legal for any
     * non-terminal item.
     */
    public DiscoveredItemAuditEntry updateReview(AssetCategory suggestedCategory, UUID matchedAssetId, String executor) {
        requireExecutor(executor);
        requireNotTerminal("review/update");
        this.suggestedCategory = suggestedCategory;
        this.matchedAssetId = matchedAssetId;
        return record("REVIEW_UPDATED", executor, String.format(
                "Review updated. suggestedCategory=[%s] matchedAssetId=[%s]", suggestedCategory, matchedAssetId));
    }

    /**
     * Behavior Qualifier: {@code control/ignore}. Terminal: a human determined this item is noise
     * (duplicate, decommissioned-elsewhere, not inventory-worthy).
     */
    public DiscoveredItemAuditEntry ignore(String executor) {
        requireExecutor(executor);
        requireNotTerminal("ignore");
        return transition(DiscoveredItemStatus.IGNORED, "IGNORED", executor, "Item marked as ignored (not inventory-worthy).");
    }

    /**
     * Behavior Qualifier: {@code control/promote}. Terminal: the item becomes (or updates) a real
     * Asset. Only legal from {@code UNDER_REVIEW}, and only once a {@code suggestedCategory} has been
     * recorded via {@code review/update} — the orchestration layer performs the actual call to the IT
     * Asset Registry and supplies the resulting {@code promotedAssetId} here.
     */
    public DiscoveredItemAuditEntry promote(UUID promotedAssetId, String executor) {
        requireExecutor(executor);
        Objects.requireNonNull(promotedAssetId, "promotedAssetId cannot be null when promoting.");
        if (this.status != DiscoveredItemStatus.UNDER_REVIEW) {
            throw new InvalidDiscoveredItemStatusException(String.format(
                    "Compliance Violation: only an UNDER_REVIEW item can be promoted; current status is [%s].", this.status));
        }
        if (this.suggestedCategory == null) {
            throw new InvalidDiscoveredItemStatusException(
                    "Compliance Violation: a suggestedCategory must be recorded (review/update) before promotion.");
        }
        this.promotedAssetId = promotedAssetId;
        return transition(DiscoveredItemStatus.PROMOTED, "PROMOTED", executor,
                String.format("Item promoted to Asset [%s].", promotedAssetId));
    }

    // --- Internal helpers ---

    private DiscoveredItemAuditEntry transition(DiscoveredItemStatus newStatus, String action, String executor, String detail) {
        DiscoveredItemStatus previous = this.status;
        this.status = newStatus;
        this.updatedAt = Instant.now();
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(this.updatedAt, action, executor, previous, newStatus, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private DiscoveredItemAuditEntry record(String action, String executor, String detail) {
        this.updatedAt = Instant.now();
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(this.updatedAt, action, executor, this.status, this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireNotTerminal(String operation) {
        if (this.status == DiscoveredItemStatus.PROMOTED || this.status == DiscoveredItemStatus.IGNORED) {
            throw new InvalidDiscoveredItemStatusException(String.format(
                    "Compliance Violation: cannot %s a terminal item (status [%s]); the lifecycle is closed.", operation, this.status));
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable DiscoveredItem mutations.");
        }
    }

    private static Map<String, String> copyAttributes(Map<String, String> attributes) {
        return attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getSource() { return source; }
    public String getExternalKey() { return externalKey; }
    public String getName() { return name; }
    public AssetCategory getSuggestedCategory() { return suggestedCategory; }
    public Map<String, String> getRawAttributes() { return Collections.unmodifiableMap(rawAttributes); }
    public UUID getMatchedAssetId() { return matchedAssetId; }
    public UUID getPromotedAssetId() { return promotedAssetId; }
    public DiscoveredItemStatus getStatus() { return status; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<DiscoveredItemAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    /**
     * This service's own copy of the Asset Registry's {@code Asset.AssetCategory} enum (ADR-030) — a
     * suggestion the reviewer records for a human to confirm on promotion, never a shared JAR
     * dependency between the two Service Domains.
     */
    public enum AssetCategory {
        LAPTOP, DESKTOP, SERVER, NETWORK_DEVICE, STORAGE_ARRAY, PERIPHERAL,
        MOBILE_DEVICE, IOT_SENSOR, VIRTUAL_MACHINE, SOFTWARE_LICENSE
    }

    public enum DiscoveredItemStatus {
        DISCOVERED, UNDER_REVIEW, PROMOTED, IGNORED
    }

    /**
     * Immutable forensic ledger entry.
     *
     * @param fromStatus status before the action ({@code null} for the initiating entry)
     * @param toStatus   status after the action (equal to {@code fromStatus} for non-transition actions)
     */
    public record DiscoveredItemAuditEntry(Instant occurredAt, String action, String executor,
                                           DiscoveredItemStatus fromStatus, DiscoveredItemStatus toStatus, String detail) {}
}
