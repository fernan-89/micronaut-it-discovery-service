package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.DiscoveredItemResponse;
import com.thinklab.application.mapper.DiscoveredItemMapper;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.exception.InvalidDiscoveredItemStatusException;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.domain.port.AssetRegistryServicePort;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for promoting a reviewed DiscoveredItem into a real Asset (BIAN Behavior Qualifier:
 * {@code control/promote}). Terminal. Only legal from {@code UNDER_REVIEW}, and only once a
 * {@code suggestedCategory} has been recorded via {@code review/update} — both are checked here,
 * before the external call, so a promotion that is going to be rejected never spends one on
 * {@code it-asset-registry-service} (ADR-032, mirroring the duplicate-serial check in
 * {@code InitiateAssetUseCase} of that same service).
 *
 * <p>No {@code matchedAssetId} recorded -> creates a brand-new Asset (serialNumber = externalKey,
 * decision documented in ADR-032); a {@code matchedAssetId} recorded -> updates that existing Asset
 * instead. A downstream failure leaves the item in {@code UNDER_REVIEW}, never a partial promotion.
 */
@Singleton
public class PromoteDiscoveredItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(PromoteDiscoveredItemUseCase.class);

    private final DiscoveredItemRepository discoveredItemRepository;
    private final AssetRegistryServicePort assetRegistryServicePort;

    public PromoteDiscoveredItemUseCase(DiscoveredItemRepository discoveredItemRepository, AssetRegistryServicePort assetRegistryServicePort) {
        this.discoveredItemRepository = discoveredItemRepository;
        this.assetRegistryServicePort = assetRegistryServicePort;
    }

    public Mono<DiscoveredItemResponse> execute(UUID id, String executor) {
        log.info("[USE CASE] Promoting discovered item ID: {}", id);

        return discoveredItemRepository.findById(id)
                .switchIfEmpty(Mono.error(new DiscoveredItemNotFoundException(id)))
                .flatMap(item -> {
                    requirePromotable(item);
                    return resolveAssetId(item, executor)
                            .flatMap(assetId -> {
                                DiscoveredItemAuditEntry entry = item.promote(assetId, executor);
                                return discoveredItemRepository.updatePromotion(id, DiscoveredItemStatus.PROMOTED, assetId, entry)
                                        .thenReturn(DiscoveredItemMapper.toResponse(item));
                            });
                });
    }

    private Mono<UUID> resolveAssetId(DiscoveredItem item, String executor) {
        if (item.getMatchedAssetId() == null) {
            return assetRegistryServicePort.initiateAsset(item.getOrganisationId(), item.getSuggestedCategory(),
                    item.getName(), item.getExternalKey(), item.getRawAttributes(), executor);
        }
        return assetRegistryServicePort.updateAsset(item.getMatchedAssetId(), item.getName(), item.getRawAttributes(), executor);
    }

    private static void requirePromotable(DiscoveredItem item) {
        if (item.getStatus() != DiscoveredItemStatus.UNDER_REVIEW) {
            throw new InvalidDiscoveredItemStatusException(String.format(
                    "Compliance Violation: only an UNDER_REVIEW item can be promoted; current status is [%s].", item.getStatus()));
        }
        if (item.getSuggestedCategory() == null) {
            throw new InvalidDiscoveredItemStatusException(
                    "Compliance Violation: a suggestedCategory must be recorded (review/update) before promotion.");
        }
    }
}
