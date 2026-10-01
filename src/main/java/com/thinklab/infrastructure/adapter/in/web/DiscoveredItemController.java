package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateDiscoveredItemRequest;
import com.thinklab.application.dto.request.ReviewUpdateDiscoveredItemRequest;
import com.thinklab.application.dto.response.DiscoveredItemAuditEntryResponse;
import com.thinklab.application.dto.response.DiscoveredItemResponse;
import com.thinklab.application.usecase.ClaimDiscoveredItemUseCase;
import com.thinklab.application.usecase.IgnoreDiscoveredItemUseCase;
import com.thinklab.application.usecase.InitiateDiscoveredItemUseCase;
import com.thinklab.application.usecase.InitiateDiscoveredItemUseCase.IngestResult;
import com.thinklab.application.usecase.PromoteDiscoveredItemUseCase;
import com.thinklab.application.usecase.RetrieveDiscoveredItemAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveDiscoveredItemUseCase;
import com.thinklab.application.usecase.RetrieveDiscoveredItemsUseCase;
import com.thinklab.application.usecase.ReviewUpdateDiscoveredItemUseCase;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-discovery} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.DiscoveredItem} is
 * the Control Record. Every route follows {@code /it-discovery/v1/{control-record-id}/{behavior-qualifier}}.
 * There is no {@code DELETE}: {@code control/ignore} is a terminal, soft status transition.
 *
 * <p><b>Header-Sourced Forensics (ADR-013):</b> {@code X-Tenant-Id} (organisationId) is mandatory on
 * {@code initiate} and collection {@code retrieve}; {@code X-Executor} is mandatory on every mutation
 * and is recorded in the item's immutable audit ledger.
 */
@Controller("/it-discovery/v1")
public class DiscoveredItemController {

    private static final Logger log = LoggerFactory.getLogger(DiscoveredItemController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";

    private final InitiateDiscoveredItemUseCase initiateDiscoveredItemUseCase;
    private final RetrieveDiscoveredItemUseCase retrieveDiscoveredItemUseCase;
    private final RetrieveDiscoveredItemsUseCase retrieveDiscoveredItemsUseCase;
    private final ClaimDiscoveredItemUseCase claimDiscoveredItemUseCase;
    private final ReviewUpdateDiscoveredItemUseCase reviewUpdateDiscoveredItemUseCase;
    private final IgnoreDiscoveredItemUseCase ignoreDiscoveredItemUseCase;
    private final PromoteDiscoveredItemUseCase promoteDiscoveredItemUseCase;
    private final RetrieveDiscoveredItemAuditLogUseCase retrieveDiscoveredItemAuditLogUseCase;

    public DiscoveredItemController(
            InitiateDiscoveredItemUseCase initiateDiscoveredItemUseCase,
            RetrieveDiscoveredItemUseCase retrieveDiscoveredItemUseCase,
            RetrieveDiscoveredItemsUseCase retrieveDiscoveredItemsUseCase,
            ClaimDiscoveredItemUseCase claimDiscoveredItemUseCase,
            ReviewUpdateDiscoveredItemUseCase reviewUpdateDiscoveredItemUseCase,
            IgnoreDiscoveredItemUseCase ignoreDiscoveredItemUseCase,
            PromoteDiscoveredItemUseCase promoteDiscoveredItemUseCase,
            RetrieveDiscoveredItemAuditLogUseCase retrieveDiscoveredItemAuditLogUseCase
    ) {
        this.initiateDiscoveredItemUseCase = initiateDiscoveredItemUseCase;
        this.retrieveDiscoveredItemUseCase = retrieveDiscoveredItemUseCase;
        this.retrieveDiscoveredItemsUseCase = retrieveDiscoveredItemsUseCase;
        this.claimDiscoveredItemUseCase = claimDiscoveredItemUseCase;
        this.reviewUpdateDiscoveredItemUseCase = reviewUpdateDiscoveredItemUseCase;
        this.ignoreDiscoveredItemUseCase = ignoreDiscoveredItemUseCase;
        this.promoteDiscoveredItemUseCase = promoteDiscoveredItemUseCase;
        this.retrieveDiscoveredItemAuditLogUseCase = retrieveDiscoveredItemAuditLogUseCase;
    }

    /**
     * Behavior Qualifier: {@code initiate}. Ingests a candidate item. Idempotent on
     * {@code (organisationId, source, externalKey)}: a first sighting returns 201 Created, a
     * re-sighting returns 200 OK with the refreshed item (ADR-031).
     */
    @Post("/initiate")
    public Mono<HttpResponse<DiscoveredItemResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid InitiateDiscoveredItemRequest request
    ) {
        log.info("[ACTION: INITIATE_DISCOVERED_ITEM] [EXECUTOR: {}] Received ingest for organisation: {} source: {} externalKey: {}",
                executor, tenantId, request.source(), request.externalKey());

        return initiateDiscoveredItemUseCase.execute(UUID.fromString(tenantId), request, executor)
                .map(DiscoveredItemController::toIngestHttpResponse);
    }

    private static HttpResponse<DiscoveredItemResponse> toIngestHttpResponse(IngestResult result) {
        return result.created() ? HttpResponse.created(result.response()) : HttpResponse.ok(result.response());
    }

    /** Behavior Qualifier: {@code retrieve}. Fetches a single DiscoveredItem by UUID. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<DiscoveredItemResponse>> retrieveById(@PathVariable UUID id) {
        log.info("[ACTION: RETRIEVE_DISCOVERED_ITEM] Received request to get discovered item by ID: {}", id);

        return retrieveDiscoveredItemUseCase.execute(id).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). Lists DiscoveredItems scoped to a tenant. */
    @Get("/retrieve")
    public Mono<List<DiscoveredItemResponse>> retrieveAll(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @QueryValue @Nullable DiscoveredItemStatus status,
            @QueryValue @Nullable String source,
            @QueryValue @Nullable AssetCategory category
    ) {
        log.info("[ACTION: RETRIEVE_DISCOVERED_ITEMS] Received request to list items for organisation: {} status: {} source: {} category: {}",
                tenantId, status, source, category);

        return Mono.defer(() -> retrieveDiscoveredItemsUseCase.execute(UUID.fromString(tenantId), status, source, category).collectList());
    }

    /** Behavior Qualifier: {@code review/claim}. Claims a DISCOVERED item for review. */
    @Put("/{id}/review/claim")
    public Mono<HttpResponse<Void>> reviewClaim(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        log.info("[ACTION: CLAIM_DISCOVERED_ITEM] [EXECUTOR: {}] Received request to claim item ID: {}", executor, id);

        return claimDiscoveredItemUseCase.execute(id, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code review/update}. Records the reviewer's findings. */
    @Put("/{id}/review/update")
    public Mono<HttpResponse<Void>> reviewUpdate(
            @PathVariable UUID id,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid ReviewUpdateDiscoveredItemRequest request
    ) {
        log.info("[ACTION: REVIEW_UPDATE_DISCOVERED_ITEM] [EXECUTOR: {}] Received review update for item ID: {}", executor, id);

        return reviewUpdateDiscoveredItemUseCase.execute(id, request, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/ignore}. Terminal: marks the item as not inventory-worthy. */
    @Put("/{id}/control/ignore")
    public Mono<HttpResponse<Void>> controlIgnore(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        log.info("[ACTION: IGNORE_DISCOVERED_ITEM] [EXECUTOR: {}] Received request to ignore item ID: {}", executor, id);

        return ignoreDiscoveredItemUseCase.execute(id, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/promote}. Terminal: promotes the item into a real Asset. */
    @Put("/{id}/control/promote")
    public Mono<HttpResponse<DiscoveredItemResponse>> controlPromote(@PathVariable UUID id, @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        log.info("[ACTION: PROMOTE_DISCOVERED_ITEM] [EXECUTOR: {}] Received request to promote item ID: {}", executor, id);

        return promoteDiscoveredItemUseCase.execute(id, executor).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. Immutable forensic ledger of the item. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<DiscoveredItemAuditEntryResponse>> retrieveAuditLog(@PathVariable UUID id) {
        log.info("[ACTION: RETRIEVE_DISCOVERED_ITEM_AUDIT_LOG] Received request for audit ledger of item ID: {}", id);

        return retrieveDiscoveredItemAuditLogUseCase.execute(id);
    }
}
