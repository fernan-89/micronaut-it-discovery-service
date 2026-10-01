package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.DiscoveredItemResponse;
import com.thinklab.application.mapper.DiscoveredItemMapper;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates single-DiscoveredItem retrieval (BIAN Behavior Qualifier: {@code retrieve}).
 */
@Singleton
public class RetrieveDiscoveredItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveDiscoveredItemUseCase.class);

    private final DiscoveredItemRepository discoveredItemRepository;

    public RetrieveDiscoveredItemUseCase(DiscoveredItemRepository discoveredItemRepository) {
        this.discoveredItemRepository = discoveredItemRepository;
    }

    public Mono<DiscoveredItemResponse> execute(UUID id) {
        log.info("[USE CASE] Retrieving discovered item ID: {}", id);

        return discoveredItemRepository.findById(id)
                .switchIfEmpty(Mono.error(new DiscoveredItemNotFoundException(id)))
                .map(DiscoveredItemMapper::toResponse);
    }
}
