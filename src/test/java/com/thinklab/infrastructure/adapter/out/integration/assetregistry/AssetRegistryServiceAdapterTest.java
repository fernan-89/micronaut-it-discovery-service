package com.thinklab.infrastructure.adapter.out.integration.assetregistry;

import com.thinklab.domain.exception.PromotionConflictException;
import com.thinklab.domain.exception.PromotionValidationException;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.infrastructure.adapter.out.integration.assetregistry.AssetRegistryServiceAdapter.AssetApiResponse;
import com.thinklab.infrastructure.adapter.out.integration.assetregistry.AssetRegistryServiceAdapter.InitiateAssetApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.assetregistry.AssetRegistryServiceAdapter.UpdateAssetApiRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetRegistryServiceAdapterTest {

    @Mock private AssetRegistryApiClient apiClient;

    private AssetRegistryServiceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AssetRegistryServiceAdapter(apiClient);
    }

    @Test
    @DisplayName("initiateAsset posts to the registry and returns the new Asset's id")
    void initiateAsset() {
        UUID organisationId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        when(apiClient.initiate(any(), any(), any())).thenReturn(Mono.just(new AssetApiResponse(assetId)));

        StepVerifier.create(adapter.initiateAsset(organisationId, AssetCategory.SERVER, "Rack Server", "AA:BB", Map.of("mac", "AA:BB"), "op-1"))
                .expectNext(assetId)
                .verifyComplete();

        ArgumentCaptor<InitiateAssetApiRequest> captor = ArgumentCaptor.forClass(InitiateAssetApiRequest.class);
        verify(apiClient).initiate(eq(organisationId.toString()), eq("op-1"), captor.capture());
        assertEquals("Rack Server", captor.getValue().name());
        assertEquals("SERVER", captor.getValue().category());
        assertEquals("AA:BB", captor.getValue().serialNumber());
    }

    @Test
    @DisplayName("updateAsset puts to the registry and returns the same assetId")
    void updateAsset() {
        UUID assetId = UUID.randomUUID();
        when(apiClient.update(eq(assetId), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(adapter.updateAsset(assetId, "Rack Server", Map.of("mac", "AA:BB"), "op-1"))
                .expectNext(assetId)
                .verifyComplete();

        ArgumentCaptor<UpdateAssetApiRequest> captor = ArgumentCaptor.forClass(UpdateAssetApiRequest.class);
        verify(apiClient).update(eq(assetId), eq("op-1"), captor.capture());
        assertEquals("Rack Server", captor.getValue().name());
    }

    @Test
    @DisplayName("a 422 from the registry translates into PromotionValidationException carrying the relayed violations")
    void promotionValidation() {
        Map<String, Object> body = Map.of("violations", List.of("$.cpu: is missing"));
        HttpClientResponseException unprocessable = new HttpClientResponseException("Unprocessable",
                HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body));
        when(apiClient.initiate(any(), any(), any())).thenReturn(Mono.error(unprocessable));

        StepVerifier.create(adapter.initiateAsset(UUID.randomUUID(), AssetCategory.SERVER, "n", "k", Map.of(), "op-1"))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(PromotionValidationException.class, error);
                    assertEquals(List.of("$.cpu: is missing"), ((PromotionValidationException) error).getViolations());
                })
                .verify();
    }

    @Test
    @DisplayName("a 404 or 409 from the registry translates into PromotionConflictException")
    void promotionConflict() {
        HttpClientResponseException notFound = new HttpClientResponseException("Not Found", HttpResponse.status(HttpStatus.NOT_FOUND));
        HttpClientResponseException conflict = new HttpClientResponseException("Conflict", HttpResponse.status(HttpStatus.CONFLICT));
        when(apiClient.initiate(any(), any(), any())).thenReturn(Mono.error(notFound)).thenReturn(Mono.error(conflict));

        StepVerifier.create(adapter.initiateAsset(UUID.randomUUID(), AssetCategory.SERVER, "n", "k", Map.of(), "op-1"))
                .expectError(PromotionConflictException.class)
                .verify();
        StepVerifier.create(adapter.initiateAsset(UUID.randomUUID(), AssetCategory.SERVER, "n", "k", Map.of(), "op-1"))
                .expectError(PromotionConflictException.class)
                .verify();
    }

    @Test
    @DisplayName("a non-422/404/409 HTTP error from the registry is a dependency failure")
    void otherHttpError() {
        HttpClientResponseException serverError = new HttpClientResponseException("Internal Server Error",
                HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR));
        when(apiClient.initiate(any(), any(), any())).thenReturn(Mono.error(serverError));

        StepVerifier.create(adapter.initiateAsset(UUID.randomUUID(), AssetCategory.SERVER, "n", "k", Map.of(), "op-1"))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(IllegalStateException.class, error);
                    assertTrue(error.getMessage().contains("rejected the request"));
                })
                .verify();
    }

    @Test
    @DisplayName("a non-HTTP failure (e.g. connection refused) is a generic dependency-unavailable error")
    void infrastructureFailure() {
        when(apiClient.initiate(any(), any(), any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        StepVerifier.create(adapter.initiateAsset(UUID.randomUUID(), AssetCategory.SERVER, "n", "k", Map.of(), "op-1"))
                .expectErrorSatisfies(error -> {
                    assertInstanceOf(IllegalStateException.class, error);
                    assertTrue(error.getMessage().contains("currently unavailable"));
                })
                .verify();
    }
}
