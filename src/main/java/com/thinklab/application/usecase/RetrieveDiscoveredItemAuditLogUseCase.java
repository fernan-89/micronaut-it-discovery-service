package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.DiscoveredItemAuditEntryResponse;
import com.thinklab.application.mapper.DiscoveredItemMapper;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Projects the immutable forensic ledger of a DiscoveredItem (BIAN Behavior Qualifier:
 * {@code audit-log/retrieve}).
 *
 * <p>Returns {@code Mono<List<T>>}, never a bare {@code Flux<T>} — a controller returning {@code Flux}
 * directly bypasses the RFC 7807 exception handlers and can reorder streamed JSON elements relative to
 * their true array order (found live in Journey 6's own {@code WorkOrder.audit-log/retrieve}).
 */
@Singleton
public class RetrieveDiscoveredItemAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveDiscoveredItemAuditLogUseCase.class);

    private final DiscoveredItemRepository discoveredItemRepository;

    public RetrieveDiscoveredItemAuditLogUseCase(DiscoveredItemRepository discoveredItemRepository) {
        this.discoveredItemRepository = discoveredItemRepository;
    }

    public Mono<List<DiscoveredItemAuditEntryResponse>> execute(UUID id) {
        log.info("[USE CASE] Retrieving audit ledger for discovered item ID: {}", id);

        return discoveredItemRepository.findById(id)
                .switchIfEmpty(Mono.error(new DiscoveredItemNotFoundException(id)))
                .flatMapMany(item -> Flux.fromIterable(item.getAuditTrail()))
                .map(DiscoveredItemMapper::toResponse)
                .collectList();
    }
}
