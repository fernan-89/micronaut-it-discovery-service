package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateDiscoveredItemRequest;
import com.thinklab.application.dto.response.DiscoveredItemResponse;
import com.thinklab.application.mapper.DiscoveredItemMapper;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates the business flow for DiscoveredItem ingestion (BIAN Behavior Qualifier:
 * {@code initiate}).
 *
 * <p><b>Idempotent on {@code (organisationId, source, externalKey)} (ADR-031):</b> a first sighting
 * creates a brand-new item (spending a Sovereign ID); re-posting the same triple — whether the item is
 * still open or already terminal — only refreshes {@code lastSeenAt}/{@code rawAttributes} and records
 * a {@code RESEEN} audit entry; it never reopens a terminal item and never spends a second Sovereign ID.
 * The controller uses {@link IngestResult#created()} to answer 201 vs 200.
 */
@Singleton
public class InitiateDiscoveredItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateDiscoveredItemUseCase.class);

    private final HashServicePort hashServicePort;
    private final DiscoveredItemRepository discoveredItemRepository;

    public InitiateDiscoveredItemUseCase(HashServicePort hashServicePort, DiscoveredItemRepository discoveredItemRepository) {
        this.hashServicePort = hashServicePort;
        this.discoveredItemRepository = discoveredItemRepository;
    }

    public Mono<IngestResult> execute(UUID organisationId, InitiateDiscoveredItemRequest request, String executor) {
        log.info("[USE CASE] Ingesting discovered item for organisation: {} source: {} externalKey: {}",
                organisationId, request.source(), request.externalKey());

        return discoveredItemRepository.findByOrganisationIdAndSourceAndExternalKey(organisationId, request.source(), request.externalKey())
                .flatMap(existing -> {
                    DiscoveredItemAuditEntry entry = existing.reseen(request.rawAttributes(), executor);
                    return discoveredItemRepository.updateReseen(existing.getId(), existing.getRawAttributes(), existing.getLastSeenAt(), entry)
                            .thenReturn(new IngestResult(DiscoveredItemMapper.toResponse(existing), false));
                })
                .switchIfEmpty(Mono.defer(() -> hashServicePort.generateSovereignId("discovery-ingest")
                        .map(sovereignId -> DiscoveredItemMapper.toDomain(request, sovereignId, organisationId, executor))
                        .flatMap(discoveredItemRepository::create)
                        .map(item -> new IngestResult(DiscoveredItemMapper.toResponse(item), true))));
    }

    public record IngestResult(DiscoveredItemResponse response, boolean created) {}
}
