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
 * Use Case for marking a DiscoveredItem as not inventory-worthy (BIAN Behavior Qualifier:
 * {@code control/ignore}). Terminal.
 */
@Singleton
public class IgnoreDiscoveredItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(IgnoreDiscoveredItemUseCase.class);

    private final DiscoveredItemRepository discoveredItemRepository;

    public IgnoreDiscoveredItemUseCase(DiscoveredItemRepository discoveredItemRepository) {
        this.discoveredItemRepository = discoveredItemRepository;
    }

    public Mono<Void> execute(UUID id, String executor) {
        log.info("[USE CASE] Ignoring discovered item ID: {}", id);

        return discoveredItemRepository.findById(id)
                .switchIfEmpty(Mono.error(new DiscoveredItemNotFoundException(id)))
                .flatMap(item -> {
                    DiscoveredItemAuditEntry entry = item.ignore(executor);
                    return discoveredItemRepository.updateStatus(id, DiscoveredItemStatus.IGNORED, entry);
                });
    }
}
