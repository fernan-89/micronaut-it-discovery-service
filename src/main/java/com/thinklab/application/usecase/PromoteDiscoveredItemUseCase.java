package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.DiscoveredItemResponse;
import com.thinklab.application.mapper.DiscoveredItemMapper;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.domain.port.AssetRegistryServicePort;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
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
                    // Step 1: reserve. A guarded write on the status and updatedAt that were read: of simultaneous promotions only one passes, the rest
                    // are refused (409) before anything is created in the Asset Registry.
                    DiscoveredItemStatus statusRead = item.getStatus();
                    Instant updatedAtRead = item.getUpdatedAt();
                    DiscoveredItemAuditEntry reserved = item.reservePromotion(executor, Instant.now());
                    return discoveredItemRepository.reservePromotion(id, statusRead, updatedAtRead, reserved)
                            .then(Mono.defer(() -> createOrUpdateAsset(item, executor)))
                            // Step 2: close. The Asset exists; PROMOTING -> PROMOTED, guarded on PROMOTING.
                            .flatMap(assetId -> {
                                DiscoveredItemAuditEntry entry = item.completePromotion(assetId, executor);
                                return discoveredItemRepository.updatePromotion(id, DiscoveredItemStatus.PROMOTING, DiscoveredItemStatus.PROMOTED, assetId, entry)
                                        .thenReturn(DiscoveredItemMapper.toResponse(item));
                            });
                });
    }

    /**
     * Creates or updates the Asset; if the Asset Registry refuses (ADR-032: 422, 404, 409 come back to the caller), the reservation is released so the
     * item is UNDER_REVIEW again and the promotion can be tried again. A failure to release is logged and does not hide the real error: the reservation
     * then expires by itself after {@link DiscoveredItem#PROMOTION_LEASE}.
     */
    private Mono<UUID> createOrUpdateAsset(DiscoveredItem item, String executor) {
        return resolveAssetId(item, executor)
                .onErrorResume(failure -> {
                    DiscoveredItemAuditEntry released = item.releasePromotion(executor);
                    return discoveredItemRepository.updateStatus(item.getId(), DiscoveredItemStatus.PROMOTING, DiscoveredItemStatus.UNDER_REVIEW, released)
                            .onErrorResume(releaseFailure -> {
                                log.warn("[USE CASE] Could not release the promotion of item {}: {}", item.getId(), releaseFailure.getMessage());
                                return Mono.empty();
                            })
                            .then(Mono.<UUID>error(failure));
                });
    }

    private Mono<UUID> resolveAssetId(DiscoveredItem item, String executor) {
        if (item.getMatchedAssetId() == null) {
            return assetRegistryServicePort.initiateAsset(item.getOrganisationId(), item.getSuggestedCategory(),
                    item.getName(), item.getExternalKey(), item.getRawAttributes(), executor);
        }
        return assetRegistryServicePort.updateAsset(item.getOrganisationId(), item.getMatchedAssetId(), item.getName(), item.getRawAttributes(), executor);
    }
}
