package com.thinklab.application.usecase;

import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Use Case for a human claiming a freshly discovered item for review (BIAN Behavior Qualifier:
 * {@code review/claim}).
 */
@Singleton
public class ClaimDiscoveredItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(ClaimDiscoveredItemUseCase.class);

    private final DiscoveredItemRepository discoveredItemRepository;

    public ClaimDiscoveredItemUseCase(DiscoveredItemRepository discoveredItemRepository) {
        this.discoveredItemRepository = discoveredItemRepository;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, String executor) {
        log.info("[USE CASE] Claiming discovered item for review, ID: {}", id);

        return discoveredItemRepository.findById(id, organisationId)
                .switchIfEmpty(Mono.error(new DiscoveredItemNotFoundException(id)))
                .flatMap(item -> {
                    DiscoveredItemStatus statusBefore = item.getStatus();
                    DiscoveredItemAuditEntry entry = item.claim(executor);
                    return discoveredItemRepository.updateStatus(id, statusBefore, DiscoveredItemStatus.UNDER_REVIEW, entry);
                });
    }
}
