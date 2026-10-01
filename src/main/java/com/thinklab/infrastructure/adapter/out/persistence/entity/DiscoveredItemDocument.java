package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure-specific representation of the DiscoveredItem Aggregate for MongoDB. Ensures the
 * pure Domain Model remains untainted by persistence annotations. Uses native BSON annotations for
 * high-performance mapping without ORM overhead.
 */
@Introspected
public class DiscoveredItemDocument {

    @BsonId // Native MongoDB driver annotation for Sovereign Identity
    private UUID id;

    private UUID organisationId;
    private String source;
    private String externalKey;
    private String name;
    private String suggestedCategory;
    private Map<String, String> rawAttributes = new LinkedHashMap<>();
    private UUID matchedAssetId;
    private UUID promotedAssetId;
    private String status;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    // Getters and Setters required by framework POJO codec
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalKey() { return externalKey; }
    public void setExternalKey(String externalKey) { this.externalKey = externalKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSuggestedCategory() { return suggestedCategory; }
    public void setSuggestedCategory(String suggestedCategory) { this.suggestedCategory = suggestedCategory; }
    public Map<String, String> getRawAttributes() { return rawAttributes; }
    public void setRawAttributes(Map<String, String> rawAttributes) { this.rawAttributes = rawAttributes; }
    public UUID getMatchedAssetId() { return matchedAssetId; }
    public void setMatchedAssetId(UUID matchedAssetId) { this.matchedAssetId = matchedAssetId; }
    public UUID getPromotedAssetId() { return promotedAssetId; }
    public void setPromotedAssetId(UUID promotedAssetId) { this.promotedAssetId = promotedAssetId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor,
                                     String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(DiscoveredItemAuditEntry entry) {
            return new AuditEntryDocument(
                    entry.occurredAt(),
                    entry.action(),
                    entry.executor(),
                    entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(),
                    entry.detail()
            );
        }

        public DiscoveredItemAuditEntry toDomain() {
            return new DiscoveredItemAuditEntry(
                    occurredAt,
                    action,
                    executor,
                    fromStatus != null ? DiscoveredItemStatus.valueOf(fromStatus) : null,
                    DiscoveredItemStatus.valueOf(toStatus),
                    detail
            );
        }
    }

    /**
     * Internal Persistence Mapper ensuring strict isolation between Document and Domain.
     */
    public static final class DiscoveredItemPersistenceMapper {

        private DiscoveredItemPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static DiscoveredItemDocument toDocument(DiscoveredItem item) {
            DiscoveredItemDocument doc = new DiscoveredItemDocument();
            doc.setId(item.getId());
            doc.setOrganisationId(item.getOrganisationId());
            doc.setSource(item.getSource());
            doc.setExternalKey(item.getExternalKey());
            doc.setName(item.getName());
            doc.setSuggestedCategory(item.getSuggestedCategory() != null ? item.getSuggestedCategory().name() : null);
            doc.setRawAttributes(new LinkedHashMap<>(item.getRawAttributes()));
            doc.setMatchedAssetId(item.getMatchedAssetId());
            doc.setPromotedAssetId(item.getPromotedAssetId());
            doc.setStatus(item.getStatus().name());
            doc.setFirstSeenAt(item.getFirstSeenAt());
            doc.setLastSeenAt(item.getLastSeenAt());
            doc.setCreatedAt(item.getCreatedAt());
            doc.setUpdatedAt(item.getUpdatedAt());
            doc.setAuditTrail(item.getAuditTrail().stream()
                    .map(AuditEntryDocument::fromDomain)
                    .collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static DiscoveredItem toDomain(DiscoveredItemDocument doc) {
            DiscoveredItemStatus status = doc.getStatus() != null
                    ? DiscoveredItemStatus.valueOf(doc.getStatus()) : DiscoveredItemStatus.DISCOVERED;
            List<DiscoveredItemAuditEntry> trail = doc.getAuditTrail() != null
                    ? doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList())
                    : new ArrayList<>();

            return DiscoveredItem.reconstitute(
                    doc.getId(),
                    doc.getOrganisationId(),
                    doc.getSource(),
                    doc.getExternalKey(),
                    doc.getName(),
                    doc.getSuggestedCategory() != null ? AssetCategory.valueOf(doc.getSuggestedCategory()) : null,
                    doc.getRawAttributes(),
                    doc.getMatchedAssetId(),
                    doc.getPromotedAssetId(),
                    status,
                    doc.getFirstSeenAt(),
                    doc.getLastSeenAt(),
                    doc.getCreatedAt(),
                    doc.getUpdatedAt(),
                    trail
            );
        }
    }
}
