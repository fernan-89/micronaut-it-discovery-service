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
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.exception.InvalidDiscoveredItemStatusException;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveredItemControllerTest {

    private static final String EXECUTOR = "ops-admin";

    @Mock private InitiateDiscoveredItemUseCase initiateDiscoveredItemUseCase;
    @Mock private RetrieveDiscoveredItemUseCase retrieveDiscoveredItemUseCase;
    @Mock private RetrieveDiscoveredItemsUseCase retrieveDiscoveredItemsUseCase;
    @Mock private ClaimDiscoveredItemUseCase claimDiscoveredItemUseCase;
    @Mock private ReviewUpdateDiscoveredItemUseCase reviewUpdateDiscoveredItemUseCase;
    @Mock private IgnoreDiscoveredItemUseCase ignoreDiscoveredItemUseCase;
    @Mock private PromoteDiscoveredItemUseCase promoteDiscoveredItemUseCase;
    @Mock private RetrieveDiscoveredItemAuditLogUseCase retrieveDiscoveredItemAuditLogUseCase;

    @InjectMocks
    private DiscoveredItemController controller;

    private UUID organisationId;
    private UUID itemId;
    private DiscoveredItemResponse sample;

    @BeforeEach
    void setUp() {
        organisationId = UUID.randomUUID();
        itemId = UUID.randomUUID();
        sample = new DiscoveredItemResponse(itemId, organisationId, "manual", "AA:BB:CC", "Mystery Box", null,
                Map.of("mac", "AA:BB:CC"), null, null, "DISCOVERED", Instant.now(), Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("initiate should return 201 Created for a brand-new ingest")
    void initiateCreated() {
        InitiateDiscoveredItemRequest request = new InitiateDiscoveredItemRequest("manual", "AA:BB:CC", "Mystery Box", Map.of());
        when(initiateDiscoveredItemUseCase.execute(eq(organisationId), eq(request), eq(EXECUTOR)))
                .thenReturn(Mono.just(new IngestResult(sample, true)));

        StepVerifier.create(controller.initiate(organisationId.toString(), EXECUTOR, request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatus());
                    assertEquals(itemId, response.body().id());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("initiate should return 200 OK for a re-sighting (RESEEN)")
    void initiateReseen() {
        InitiateDiscoveredItemRequest request = new InitiateDiscoveredItemRequest("manual", "AA:BB:CC", "Mystery Box", Map.of());
        when(initiateDiscoveredItemUseCase.execute(eq(organisationId), eq(request), eq(EXECUTOR)))
                .thenReturn(Mono.just(new IngestResult(sample, false)));

        StepVerifier.create(controller.initiate(organisationId.toString(), EXECUTOR, request))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.getStatus()))
                .verifyComplete();
    }

    @Test
    @DisplayName("initiate should reject a malformed tenant header before reaching the use case")
    void initiateMalformedTenant() {
        InitiateDiscoveredItemRequest request = new InitiateDiscoveredItemRequest("manual", "k", "n", null);

        assertThrows(IllegalArgumentException.class, () -> controller.initiate("not-a-uuid", EXECUTOR, request));
    }

    @Test
    @DisplayName("initiate should propagate a domain error")
    void initiatePropagatesError() {
        InitiateDiscoveredItemRequest request = new InitiateDiscoveredItemRequest("manual", "k", "n", null);
        when(initiateDiscoveredItemUseCase.execute(any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("hash down")));

        StepVerifier.create(controller.initiate(organisationId.toString(), EXECUTOR, request))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    @DisplayName("retrieveById should return 200 OK")
    void retrieveById() {
        when(retrieveDiscoveredItemUseCase.execute(itemId)).thenReturn(Mono.just(sample));

        StepVerifier.create(controller.retrieveById(itemId))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatus());
                    assertEquals("Mystery Box", response.body().name());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("retrieveById should surface not-found from the use case")
    void retrieveByIdNotFound() {
        when(retrieveDiscoveredItemUseCase.execute(itemId)).thenReturn(Mono.error(new DiscoveredItemNotFoundException(itemId)));

        StepVerifier.create(controller.retrieveById(itemId)).expectError(DiscoveredItemNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieveAll should scope by tenant and forward status/source/category filters")
    void retrieveAll() {
        when(retrieveDiscoveredItemsUseCase.execute(organisationId, DiscoveredItemStatus.DISCOVERED, "manual", AssetCategory.SERVER))
                .thenReturn(Flux.just(sample));
        when(retrieveDiscoveredItemsUseCase.execute(organisationId, null, null, null)).thenReturn(Flux.just(sample, sample));

        StepVerifier.create(controller.retrieveAll(organisationId.toString(), DiscoveredItemStatus.DISCOVERED, "manual", AssetCategory.SERVER))
                .expectNext(List.of(sample)).verifyComplete();
        StepVerifier.create(controller.retrieveAll(organisationId.toString(), null, null, null))
                .assertNext(list -> assertEquals(2, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("reviewClaim should return 204 No Content")
    void reviewClaim() {
        when(claimDiscoveredItemUseCase.execute(itemId, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(controller.reviewClaim(itemId, EXECUTOR))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus()))
                .verifyComplete();
    }

    @Test
    @DisplayName("reviewClaim should surface an illegal transition (409) from the use case")
    void reviewClaimIllegal() {
        when(claimDiscoveredItemUseCase.execute(itemId, EXECUTOR)).thenReturn(Mono.error(new InvalidDiscoveredItemStatusException("illegal")));

        StepVerifier.create(controller.reviewClaim(itemId, EXECUTOR)).expectError(InvalidDiscoveredItemStatusException.class).verify();
    }

    @Test
    @DisplayName("reviewUpdate should return 204 No Content and pass the executor to the use case")
    void reviewUpdate() {
        ReviewUpdateDiscoveredItemRequest request = new ReviewUpdateDiscoveredItemRequest(AssetCategory.SERVER, UUID.randomUUID());
        when(reviewUpdateDiscoveredItemUseCase.execute(itemId, request, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(controller.reviewUpdate(itemId, EXECUTOR, request))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus()))
                .verifyComplete();
        verify(reviewUpdateDiscoveredItemUseCase).execute(itemId, request, EXECUTOR);
    }

    @Test
    @DisplayName("controlIgnore should return 204 No Content")
    void controlIgnore() {
        when(ignoreDiscoveredItemUseCase.execute(itemId, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(controller.controlIgnore(itemId, EXECUTOR))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatus()))
                .verifyComplete();
    }

    @Test
    @DisplayName("controlPromote should return 200 OK with the promoted item")
    void controlPromote() {
        DiscoveredItemResponse promoted = new DiscoveredItemResponse(itemId, organisationId, "manual", "AA:BB:CC", "Mystery Box",
                "SERVER", Map.of(), null, UUID.randomUUID(), "PROMOTED", Instant.now(), Instant.now(), Instant.now(), Instant.now());
        when(promoteDiscoveredItemUseCase.execute(itemId, EXECUTOR)).thenReturn(Mono.just(promoted));

        StepVerifier.create(controller.controlPromote(itemId, EXECUTOR))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatus());
                    assertEquals("PROMOTED", response.body().status());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("controlPromote should surface an illegal transition (409) from the use case")
    void controlPromoteIllegal() {
        when(promoteDiscoveredItemUseCase.execute(itemId, EXECUTOR)).thenReturn(Mono.error(new InvalidDiscoveredItemStatusException("illegal")));

        StepVerifier.create(controller.controlPromote(itemId, EXECUTOR)).expectError(InvalidDiscoveredItemStatusException.class).verify();
    }

    @Test
    @DisplayName("retrieveAuditLog should list the ledger entries")
    void retrieveAuditLog() {
        DiscoveredItemAuditEntryResponse entry = new DiscoveredItemAuditEntryResponse(Instant.now(), "DISCOVERED", EXECUTOR, null, "DISCOVERED", "d");
        when(retrieveDiscoveredItemAuditLogUseCase.execute(itemId)).thenReturn(Mono.just(List.of(entry)));

        StepVerifier.create(controller.retrieveAuditLog(itemId))
                .assertNext(list -> {
                    assertNotNull(list);
                    assertEquals(1, list.size());
                    assertEquals("DISCOVERED", list.get(0).action());
                })
                .verifyComplete();
    }
}
