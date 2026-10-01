package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateDiscoveredItemRequest;
import com.thinklab.application.dto.request.ReviewUpdateDiscoveredItemRequest;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.exception.InvalidDiscoveredItemStatusException;
import com.thinklab.domain.exception.PromotionConflictException;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.domain.port.AssetRegistryServicePort;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveredItemUseCaseTest {

    private static final String EXECUTOR = "ops-admin";

    @Mock private DiscoveredItemRepository discoveredItemRepository;
    @Mock private HashServicePort hashServicePort;
    @Mock private AssetRegistryServicePort assetRegistryServicePort;

    private UUID organisationId;
    private UUID itemId;
    private DiscoveredItem item;

    @BeforeEach
    void setUp() {
        organisationId = UUID.randomUUID();
        itemId = UUID.randomUUID();
        item = DiscoveredItem.ingest(itemId, organisationId, "manual", "AA:BB:CC", "Mystery Box", Map.of("mac", "AA:BB:CC"), EXECUTOR);
    }

    // ---------------------------------------------------------------- initiate

    @Test
    @DisplayName("Initiate: should create a brand-new item when the ingest key has never been seen")
    void initiateCreatesNewItem() {
        UUID sovereignId = UUID.randomUUID();
        InitiateDiscoveredItemUseCase useCase = new InitiateDiscoveredItemUseCase(hashServicePort, discoveredItemRepository);
        InitiateDiscoveredItemRequest request = new InitiateDiscoveredItemRequest("manual", "AA:BB:CC", "Mystery Box", Map.of("mac", "AA:BB:CC"));

        when(discoveredItemRepository.findByOrganisationIdAndSourceAndExternalKey(organisationId, "manual", "AA:BB:CC")).thenReturn(Mono.empty());
        when(hashServicePort.generateSovereignId("discovery-ingest")).thenReturn(Mono.just(sovereignId));
        when(discoveredItemRepository.create(any(DiscoveredItem.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .assertNext(result -> {
                    assertTrue(result.created());
                    assertEquals(sovereignId, result.response().id());
                    assertEquals("DISCOVERED", result.response().status());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Initiate: should refresh an existing item (RESEEN) instead of creating a duplicate")
    void initiateReseesExistingItem() {
        InitiateDiscoveredItemUseCase useCase = new InitiateDiscoveredItemUseCase(hashServicePort, discoveredItemRepository);
        InitiateDiscoveredItemRequest request = new InitiateDiscoveredItemRequest("manual", "AA:BB:CC", "Mystery Box", Map.of("mac", "AA:BB:CC", "vendor", "Dell"));

        when(discoveredItemRepository.findByOrganisationIdAndSourceAndExternalKey(organisationId, "manual", "AA:BB:CC")).thenReturn(Mono.just(item));
        when(discoveredItemRepository.updateReseen(eq(itemId), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .assertNext(result -> {
                    assertFalse(result.created());
                    assertEquals(itemId, result.response().id());
                    assertEquals("Dell", result.response().rawAttributes().get("vendor"));
                })
                .verifyComplete();

        verifyNoInteractions(hashServicePort);
        verify(discoveredItemRepository, never()).create(any());
    }

    @Test
    @DisplayName("Initiate: should propagate a hash-service failure without persisting anything")
    void initiatePropagatesHashFailure() {
        InitiateDiscoveredItemUseCase useCase = new InitiateDiscoveredItemUseCase(hashServicePort, discoveredItemRepository);
        InitiateDiscoveredItemRequest request = new InitiateDiscoveredItemRequest("manual", "new-key", "n", null);

        when(discoveredItemRepository.findByOrganisationIdAndSourceAndExternalKey(organisationId, "manual", "new-key")).thenReturn(Mono.empty());
        when(hashServicePort.generateSovereignId("discovery-ingest")).thenReturn(Mono.error(new IllegalStateException("hash down")));

        StepVerifier.create(useCase.execute(organisationId, request, EXECUTOR))
                .expectErrorMessage("hash down")
                .verify();

        verify(discoveredItemRepository, never()).create(any());
    }

    // ---------------------------------------------------------------- retrieve

    @Test
    @DisplayName("Retrieve: should project the aggregate to a response")
    void retrieveSuccess() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));

        StepVerifier.create(new RetrieveDiscoveredItemUseCase(discoveredItemRepository).execute(itemId))
                .assertNext(response -> {
                    assertEquals(itemId, response.id());
                    assertEquals("Mystery Box", response.name());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Retrieve: should fail with DiscoveredItemNotFoundException (404) when absent")
    void retrieveNotFound() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.empty());

        StepVerifier.create(new RetrieveDiscoveredItemUseCase(discoveredItemRepository).execute(itemId))
                .expectErrorSatisfies(error -> {
                    assertEquals(DiscoveredItemNotFoundException.class, error.getClass());
                    assertEquals("ERR-DSC-00404", ((DiscoveredItemNotFoundException) error).getErrorCode());
                })
                .verify();
    }

    @Test
    @DisplayName("Retrieve collection: should forward tenant, status, source and category filters")
    void retrieveCollection() {
        when(discoveredItemRepository.findAllByOrganisationId(organisationId, DiscoveredItemStatus.DISCOVERED, "manual", AssetCategory.LAPTOP))
                .thenReturn(Flux.just(item));
        when(discoveredItemRepository.findAllByOrganisationId(organisationId, null, null, null)).thenReturn(Flux.empty());
        RetrieveDiscoveredItemsUseCase useCase = new RetrieveDiscoveredItemsUseCase(discoveredItemRepository);

        StepVerifier.create(useCase.execute(organisationId, DiscoveredItemStatus.DISCOVERED, "manual", AssetCategory.LAPTOP))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(useCase.execute(organisationId, null, null, null))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- review/claim

    @Test
    @DisplayName("Claim: should move a DISCOVERED item to UNDER_REVIEW")
    void claimSuccess() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));
        when(discoveredItemRepository.updateStatus(eq(itemId), eq(DiscoveredItemStatus.UNDER_REVIEW), any())).thenReturn(Mono.empty());

        StepVerifier.create(new ClaimDiscoveredItemUseCase(discoveredItemRepository).execute(itemId, "reviewer-1"))
                .verifyComplete();

        verify(discoveredItemRepository).updateStatus(eq(itemId), eq(DiscoveredItemStatus.UNDER_REVIEW), any());
    }

    @Test
    @DisplayName("Claim: should fail with 404 when the item does not exist")
    void claimNotFound() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.empty());

        StepVerifier.create(new ClaimDiscoveredItemUseCase(discoveredItemRepository).execute(itemId, EXECUTOR))
                .expectError(DiscoveredItemNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Claim: should reject claiming a non-DISCOVERED item and never write")
    void claimRejectedWhenNotDiscovered() {
        item.claim(EXECUTOR);
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));

        StepVerifier.create(new ClaimDiscoveredItemUseCase(discoveredItemRepository).execute(itemId, EXECUTOR))
                .expectError(InvalidDiscoveredItemStatusException.class)
                .verify();

        verify(discoveredItemRepository, never()).updateStatus(any(), any(), any());
    }

    // ---------------------------------------------------------------- review/update

    @Test
    @DisplayName("ReviewUpdate: should persist the reviewer's findings")
    void reviewUpdateSuccess() {
        UUID matchedAssetId = UUID.randomUUID();
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));
        when(discoveredItemRepository.updateReview(eq(itemId), eq(AssetCategory.SERVER), eq(matchedAssetId), any())).thenReturn(Mono.empty());

        StepVerifier.create(new ReviewUpdateDiscoveredItemUseCase(discoveredItemRepository)
                        .execute(itemId, new ReviewUpdateDiscoveredItemRequest(AssetCategory.SERVER, matchedAssetId), "reviewer-1"))
                .verifyComplete();

        verify(discoveredItemRepository).updateReview(eq(itemId), eq(AssetCategory.SERVER), eq(matchedAssetId), any());
    }

    @Test
    @DisplayName("ReviewUpdate: should fail with 404 when the item does not exist")
    void reviewUpdateNotFound() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.empty());

        StepVerifier.create(new ReviewUpdateDiscoveredItemUseCase(discoveredItemRepository)
                        .execute(itemId, new ReviewUpdateDiscoveredItemRequest(null, null), EXECUTOR))
                .expectError(DiscoveredItemNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("ReviewUpdate: should reject updating a terminal item and never write")
    void reviewUpdateRejectedWhenTerminal() {
        item.claim(EXECUTOR);
        item.ignore(EXECUTOR);
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));

        StepVerifier.create(new ReviewUpdateDiscoveredItemUseCase(discoveredItemRepository)
                        .execute(itemId, new ReviewUpdateDiscoveredItemRequest(AssetCategory.SERVER, null), EXECUTOR))
                .expectError(InvalidDiscoveredItemStatusException.class)
                .verify();

        verify(discoveredItemRepository, never()).updateReview(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- control/ignore

    @Test
    @DisplayName("Ignore: should mark a DISCOVERED item as IGNORED")
    void ignoreSuccess() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));
        when(discoveredItemRepository.updateStatus(eq(itemId), eq(DiscoveredItemStatus.IGNORED), any())).thenReturn(Mono.empty());

        StepVerifier.create(new IgnoreDiscoveredItemUseCase(discoveredItemRepository).execute(itemId, EXECUTOR))
                .verifyComplete();
    }

    @Test
    @DisplayName("Ignore: should fail with 404 when the item does not exist")
    void ignoreNotFound() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.empty());

        StepVerifier.create(new IgnoreDiscoveredItemUseCase(discoveredItemRepository).execute(itemId, EXECUTOR))
                .expectError(DiscoveredItemNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Ignore: should reject ignoring an already-terminal item and never write")
    void ignoreRejectedWhenAlreadyTerminal() {
        item.ignore(EXECUTOR);
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));

        StepVerifier.create(new IgnoreDiscoveredItemUseCase(discoveredItemRepository).execute(itemId, EXECUTOR))
                .expectError(InvalidDiscoveredItemStatusException.class)
                .verify();

        verify(discoveredItemRepository, never()).updateStatus(any(), any(), any());
    }

    // ---------------------------------------------------------------- control/promote

    @Test
    @DisplayName("Promote: without a matchedAssetId should create a brand-new Asset via initiate")
    void promoteSuccessWithoutMatchedAssetId() {
        item.claim(EXECUTOR);
        item.updateReview(AssetCategory.SERVER, null, EXECUTOR);
        UUID promotedAssetId = UUID.randomUUID();
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));
        when(assetRegistryServicePort.initiateAsset(organisationId, AssetCategory.SERVER, "Mystery Box", "AA:BB:CC", item.getRawAttributes(), "reviewer-1"))
                .thenReturn(Mono.just(promotedAssetId));
        when(discoveredItemRepository.updatePromotion(eq(itemId), eq(DiscoveredItemStatus.PROMOTED), eq(promotedAssetId), any())).thenReturn(Mono.empty());

        StepVerifier.create(new PromoteDiscoveredItemUseCase(discoveredItemRepository, assetRegistryServicePort).execute(itemId, "reviewer-1"))
                .assertNext(response -> {
                    assertEquals("PROMOTED", response.status());
                    assertEquals(promotedAssetId, response.promotedAssetId());
                })
                .verifyComplete();

        verify(assetRegistryServicePort, never()).updateAsset(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Promote: with a matchedAssetId should update the existing Asset instead")
    void promoteSuccessWithMatchedAssetId() {
        UUID matchedAssetId = UUID.randomUUID();
        item.claim(EXECUTOR);
        item.updateReview(AssetCategory.SERVER, matchedAssetId, EXECUTOR);
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));
        when(assetRegistryServicePort.updateAsset(matchedAssetId, "Mystery Box", item.getRawAttributes(), "reviewer-1"))
                .thenReturn(Mono.just(matchedAssetId));
        when(discoveredItemRepository.updatePromotion(eq(itemId), eq(DiscoveredItemStatus.PROMOTED), eq(matchedAssetId), any())).thenReturn(Mono.empty());

        StepVerifier.create(new PromoteDiscoveredItemUseCase(discoveredItemRepository, assetRegistryServicePort).execute(itemId, "reviewer-1"))
                .assertNext(response -> assertEquals(matchedAssetId, response.promotedAssetId()))
                .verifyComplete();

        verify(assetRegistryServicePort, never()).initiateAsset(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Promote: should fail with 404 when the item does not exist")
    void promoteNotFound() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.empty());

        StepVerifier.create(new PromoteDiscoveredItemUseCase(discoveredItemRepository, assetRegistryServicePort).execute(itemId, EXECUTOR))
                .expectError(DiscoveredItemNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("Promote: should reject when not UNDER_REVIEW without ever calling the Asset Registry")
    void promoteRejectedWhenNotUnderReview() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));

        StepVerifier.create(new PromoteDiscoveredItemUseCase(discoveredItemRepository, assetRegistryServicePort).execute(itemId, EXECUTOR))
                .expectError(InvalidDiscoveredItemStatusException.class)
                .verify();

        verifyNoInteractions(assetRegistryServicePort);
    }

    @Test
    @DisplayName("Promote: should reject without a recorded suggestedCategory without ever calling the Asset Registry")
    void promoteRejectedWithoutSuggestedCategory() {
        item.claim(EXECUTOR);
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));

        StepVerifier.create(new PromoteDiscoveredItemUseCase(discoveredItemRepository, assetRegistryServicePort).execute(itemId, EXECUTOR))
                .expectError(InvalidDiscoveredItemStatusException.class)
                .verify();

        verifyNoInteractions(assetRegistryServicePort);
    }

    @Test
    @DisplayName("Promote: should propagate a downstream Asset Registry failure and never write the promotion")
    void promotePropagatesDownstreamFailure() {
        item.claim(EXECUTOR);
        item.updateReview(AssetCategory.SERVER, null, EXECUTOR);
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));
        when(assetRegistryServicePort.initiateAsset(any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new PromotionConflictException("conflict", new RuntimeException())));

        StepVerifier.create(new PromoteDiscoveredItemUseCase(discoveredItemRepository, assetRegistryServicePort).execute(itemId, EXECUTOR))
                .expectError(PromotionConflictException.class)
                .verify();

        verify(discoveredItemRepository, never()).updatePromotion(any(), any(), any(), any());
        assertEquals(DiscoveredItemStatus.UNDER_REVIEW, item.getStatus());
    }

    // ---------------------------------------------------------------- audit log

    @Test
    @DisplayName("Audit log: should project every ledger entry in order")
    void auditLogSuccess() {
        item.claim(EXECUTOR);
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.just(item));

        StepVerifier.create(new RetrieveDiscoveredItemAuditLogUseCase(discoveredItemRepository).execute(itemId))
                .assertNext(entries -> {
                    assertNotNull(entries);
                    assertEquals(2, entries.size());
                    assertEquals("DISCOVERED", entries.get(0).action());
                    assertEquals("CLAIMED", entries.get(1).action());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Audit log: should fail with 404 when the item does not exist")
    void auditLogNotFound() {
        when(discoveredItemRepository.findById(itemId)).thenReturn(Mono.empty());

        StepVerifier.create(new RetrieveDiscoveredItemAuditLogUseCase(discoveredItemRepository).execute(itemId))
                .expectError(DiscoveredItemNotFoundException.class)
                .verify();
    }
}
