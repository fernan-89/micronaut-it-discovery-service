package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO for DiscoveredItem output payload (IT Discovery Control Record). Enforces the DTO Isolation
 * Pattern by preventing the pure Domain Model from bleeding out to the HTTP boundary.
 */
@Serdeable
public record DiscoveredItemResponse(
        UUID id,
        UUID organisationId,
        String source,
        String externalKey,
        String name,
        String suggestedCategory,
        Map<String, String> rawAttributes,
        UUID matchedAssetId,
        UUID promotedAssetId,
        String status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant createdAt,
        Instant updatedAt
) {}
