package com.thinklab.domain.port;

import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound Port to {@code it-asset-registry-service}, called only from {@code control/promote}
 * (ADR-032). Unlike {@code ci-type-catalog-service}'s fail-open integration in the Asset Registry
 * itself, this call is never fail-open: promotion is an explicit, one-click mutation, not a background
 * check on every request, so a downstream failure must surface as a clear, retryable error rather than
 * being silently swallowed.
 */
public interface AssetRegistryServicePort {

    /** No {@code matchedAssetId} was recorded: promotion creates a brand-new Asset. */
    Mono<UUID> initiateAsset(UUID organisationId, AssetCategory category, String name, String serialNumber,
                              Map<String, String> specifications, String executor);

    /** A {@code matchedAssetId} was recorded: promotion updates the existing Asset instead. */
    Mono<UUID> updateAsset(UUID assetId, String name, Map<String, String> specifications, String executor);
}
