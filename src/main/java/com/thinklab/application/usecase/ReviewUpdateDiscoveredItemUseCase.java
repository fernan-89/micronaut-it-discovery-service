package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.ReviewUpdateDiscoveredItemRequest;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for recording a reviewer's findings on a DiscoveredItem (BIAN Behavior Qualifier:
 * {@code review/update}) — a suggested Asset category and/or a matching existing Asset.
 */
@Singleton
public class ReviewUpdateDiscoveredItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReviewUpdateDiscoveredItemUseCase.class);

    private final DiscoveredItemRepository discoveredItemRepository;

    public ReviewUpdateDiscoveredItemUseCase(DiscoveredItemRepository discoveredItemRepository) {
        this.discoveredItemRepository = discoveredItemRepository;
    }

    public Mono<Void> execute(UUID id, ReviewUpdateDiscoveredItemRequest request, String executor) {
        log.info("[USE CASE] Updating review findings for discovered item ID: {}", id);

        return discoveredItemRepository.findById(id)
                .switchIfEmpty(Mono.error(new DiscoveredItemNotFoundException(id)))
                .flatMap(item -> {
                    DiscoveredItemAuditEntry entry = item.updateReview(request.suggestedCategory(), request.matchedAssetId(), executor);
                    return discoveredItemRepository.updateReview(id, request.suggestedCategory(), request.matchedAssetId(), entry);
                });
    }
}
