package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * DTO for DiscoveredItem ingestion (BIAN Behavior Qualifier: {@code initiate}). Protective barrier to
 * the Domain Layer. organisationId travels via the {@code X-Tenant-Id} header, not the body.
 *
 * <p>Idempotent on {@code (organisationId, source, externalKey)}: re-posting the same triple updates
 * the existing item (lastSeenAt/rawAttributes) instead of creating a duplicate (ADR-031).
 */
@Serdeable
public record InitiateDiscoveredItemRequest(

        @NotBlank(message = "Source is required")
        @Size(max = 120, message = "Source must not exceed 120 characters")
        String source,

        @NotBlank(message = "External key is required")
        @Size(max = 200, message = "External key must not exceed 200 characters")
        String externalKey,

        @NotBlank(message = "Name is required")
        @Size(max = 160, message = "Name must not exceed 160 characters")
        String name,

        Map<String, String> rawAttributes
) {}
