package com.thinklab.infrastructure.adapter.out.integration.assetregistry;

import com.thinklab.domain.exception.PromotionConflictException;
import com.thinklab.domain.exception.PromotionValidationException;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.port.AssetRegistryServicePort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound Adapter for the it-asset-registry Service Domain. Implements the Domain Port, ensuring
 * that Micronaut-specific HTTP client details do not leak into the Application or Domain layers.
 *
 * <p>Called only from {@code control/promote} (ADR-032): never fail-open, a downstream failure
 * surfaces as a clear, retryable domain error (see {@link #translate}).
 */
@Singleton
public class AssetRegistryServiceAdapter implements AssetRegistryServicePort {

    private static final Logger log = LoggerFactory.getLogger(AssetRegistryServiceAdapter.class);

    private final AssetRegistryApiClient apiClient;

    public AssetRegistryServiceAdapter(AssetRegistryApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public Mono<UUID> initiateAsset(UUID organisationId, AssetCategory category, String name, String serialNumber,
                                     Map<String, String> specifications, String executor) {
        log.debug("[INTEGRATION] Promoting via initiate on it-asset-registry-service: organisation {} category {}", organisationId, category);

        return apiClient.initiate(organisationId.toString(), executor,
                        new InitiateAssetApiRequest(name, category.name(), serialNumber, specifications))
                .map(AssetApiResponse::id)
                .doOnError(error -> log.error("[INTEGRATION FAILURE] Promotion-via-initiate failed for organisation {}: {}", organisationId, error.getMessage()))
                .onErrorMap(this::translate);
    }

    @Override
    public Mono<UUID> updateAsset(UUID assetId, String name, Map<String, String> specifications, String executor) {
        log.debug("[INTEGRATION] Promoting via update on it-asset-registry-service: asset {}", assetId);

        return apiClient.update(assetId, executor, new UpdateAssetApiRequest(name, specifications))
                .thenReturn(assetId)
                .doOnError(error -> log.error("[INTEGRATION FAILURE] Promotion-via-update failed for asset {}: {}", assetId, error.getMessage()))
                .onErrorMap(this::translate);
    }

    private Throwable translate(Throwable error) {
        if (error instanceof HttpClientResponseException httpError) {
            if (httpError.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY) {
                List<?> violations = httpError.getResponse().getBody(Map.class)
                        .map(body -> (List<?>) body.get("violations"))
                        .orElse(List.of());
                return new PromotionValidationException(
                        "it-asset-registry-service rejected the promoted specifications against the configured schema.",
                        violations.stream().map(String::valueOf).toList());
            }
            if (httpError.getStatus() == HttpStatus.NOT_FOUND || httpError.getStatus() == HttpStatus.CONFLICT) {
                return new PromotionConflictException(
                        "it-asset-registry-service rejected the promotion (" + httpError.getStatus() + "); re-review and re-link.", httpError);
            }
            return new IllegalStateException(
                    "Dependency Failure: IT Asset Registry Service rejected the request (" + httpError.getStatus() + ").", httpError);
        }
        return new IllegalStateException("Dependency Failure: IT Asset Registry Service is currently unavailable", error);
    }

    @Serdeable
    @Introspected
    record InitiateAssetApiRequest(String name, String category, String serialNumber, Map<String, String> specifications) {}

    @Serdeable
    @Introspected
    record UpdateAssetApiRequest(String name, @Nullable Map<String, String> specifications) {}

    @Serdeable
    @Introspected
    record AssetApiResponse(UUID id) {}
}

/**
 * Declarative Micronaut HTTP Client for the it-asset-registry Service Domain. Package-private
 * visibility strictly encapsulates this integration detail within the adapter. The 'id' maps to the
 * configuration in application.yml for dynamic resolution.
 */
@Client(id = "asset-registry-service", path = "/it-asset-registry/v1")
interface AssetRegistryApiClient {

    @Post("/initiate")
    Mono<AssetRegistryServiceAdapter.AssetApiResponse> initiate(
            @Header("X-Tenant-Id") String tenantId,
            @Header("X-Executor") String executor,
            @Body AssetRegistryServiceAdapter.InitiateAssetApiRequest request
    );

    @Put("/{id}/update")
    Mono<Void> update(
            @PathVariable UUID id,
            @Header("X-Executor") String executor,
            @Body AssetRegistryServiceAdapter.UpdateAssetApiRequest request
    );
}
