package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.DiscoveredItemResponse;
import com.thinklab.application.mapper.DiscoveredItemMapper;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Orchestrates the tenant-scoped listing of DiscoveredItems (BIAN Behavior Qualifier: {@code retrieve}
 * — collection). Every query is strictly bound to the {@code organisationId} from {@code X-Tenant-Id}.
 */
@Singleton
public class RetrieveDiscoveredItemsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveDiscoveredItemsUseCase.class);

    private final DiscoveredItemRepository discoveredItemRepository;

    public RetrieveDiscoveredItemsUseCase(DiscoveredItemRepository discoveredItemRepository) {
        this.discoveredItemRepository = discoveredItemRepository;
    }

    public Flux<DiscoveredItemResponse> execute(UUID organisationId, DiscoveredItemStatus status, String source, AssetCategory category) {
        log.info("[USE CASE] Retrieving discovered items for organisation: {} status: {} source: {} category: {}",
                organisationId, status, source, category);

        return discoveredItemRepository.findAllByOrganisationId(organisationId, status, source, category)
                .map(DiscoveredItemMapper::toResponse);
    }
}
