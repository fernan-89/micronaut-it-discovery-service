package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.InitiateDiscoveredItemRequest;
import com.thinklab.application.dto.response.DiscoveredItemAuditEntryResponse;
import com.thinklab.application.dto.response.DiscoveredItemResponse;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;

import java.util.UUID;

/**
 * Static factory mapper for DiscoveredItem DTOs and Domain Entities. Enforces the DTO Isolation Pattern.
 */
public final class DiscoveredItemMapper {

    private DiscoveredItemMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static DiscoveredItem toDomain(InitiateDiscoveredItemRequest request, UUID sovereignId, UUID organisationId, String executor) {
        return DiscoveredItem.ingest(sovereignId, organisationId, request.source(), request.externalKey(),
                request.name(), request.rawAttributes(), executor);
    }

    public static DiscoveredItemResponse toResponse(DiscoveredItem item) {
        return new DiscoveredItemResponse(
                item.getId(),
                item.getOrganisationId(),
                item.getSource(),
                item.getExternalKey(),
                item.getName(),
                item.getSuggestedCategory() != null ? item.getSuggestedCategory().name() : null,
                item.getRawAttributes(),
                item.getMatchedAssetId(),
                item.getPromotedAssetId(),
                item.getStatus().name(),
                item.getFirstSeenAt(),
                item.getLastSeenAt(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }

    public static DiscoveredItemAuditEntryResponse toResponse(DiscoveredItemAuditEntry entry) {
        return new DiscoveredItemAuditEntryResponse(
                entry.occurredAt(),
                entry.action(),
                entry.executor(),
                entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(),
                entry.detail()
        );
    }
}
