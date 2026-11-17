package com.thinklab.infrastructure.adapter.out.persistence;

import com.thinklab.application.usecase.PromoteDiscoveredItemUseCase;
import com.thinklab.domain.exception.InvalidDiscoveredItemStatusException;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.domain.port.AssetRegistryServicePort;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import com.thinklab.infrastructure.adapter.out.integration.assetregistry.AssetRegistryServiceAdapter;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The promotion race against a real MongoDB (platform race audit, ACTION-PLAN 0.5): promotion creates an Asset in another service, so two
 * promotions of the same item at once must never create two Assets. The Asset Registry is replaced by one that counts the Assets it is
 * asked to create and takes its time, so the requests really overlap.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromotionRaceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_discovery_race_it";
    private static final String EXECUTOR = "discovery-admin";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    /** An Asset Registry that counts what it creates and fails on demand. */
    @Singleton
    @Replaces(AssetRegistryServiceAdapter.class)
    static class CountingAssetRegistry implements AssetRegistryServicePort {
        final AtomicInteger created = new AtomicInteger();
        volatile boolean failNext;

        @Override
        public Mono<UUID> initiateAsset(UUID organisationId, AssetCategory category, String name, String serialNumber, Map<String, String> specifications, String executor) {
            return Mono.defer(() -> {
                if (failNext) {
                    failNext = false;
                    return Mono.error(new IllegalStateException("the registry refused"));
                }
                created.incrementAndGet();
                return Mono.just(UUID.randomUUID()).delayElement(Duration.ofMillis(300));
            });
        }

        @Override
        public Mono<UUID> updateAsset(UUID organisationId, UUID assetId, String name, Map<String, String> specifications, String executor) {
            return Mono.just(assetId);
        }
    }

    @Inject DiscoveredItemRepository repository;
    @Inject PromoteDiscoveredItemUseCase promote;
    @Inject CountingAssetRegistry registry;

    private DiscoveredItem underReview(String key) {
        UUID organisation = UUID.randomUUID();
        DiscoveredItem item = DiscoveredItem.ingest(UUID.randomUUID(), organisation, "manual", key, "server-" + key, Map.of("k", "v"), EXECUTOR);
        repository.create(item).block();
        var claim = item.claim(EXECUTOR);
        repository.updateStatus(item.getId(), DiscoveredItemStatus.DISCOVERED, DiscoveredItemStatus.UNDER_REVIEW, claim).block();
        var review = item.updateReview(AssetCategory.SERVER, null, EXECUTOR);
        repository.updateReview(item.getId(), DiscoveredItemStatus.UNDER_REVIEW, AssetCategory.SERVER, null, review).block();
        return item;
    }

    @Test
    @DisplayName("ten promotions of one item at once create exactly one Asset: one wins, the others are refused with a conflict")
    void tenAtOnceCreateOneAsset() {
        DiscoveredItem item = underReview("race-1");
        int before = registry.created.get();

        List<String> outcomes = Flux.range(0, 10)
                .flatMap(i -> promote.execute(item.getId(), EXECUTOR).map(r -> "OK").onErrorResume(e -> Mono.just(e.getClass().getSimpleName())), 10)
                .collectList().block(Duration.ofSeconds(30));

        assertEquals(1, outcomes.stream().filter("OK"::equals).count(), outcomes.toString());
        assertEquals(9, outcomes.stream().filter(InvalidDiscoveredItemStatusException.class.getSimpleName()::equals).count(), outcomes.toString());
        assertEquals(1, registry.created.get() - before, "only one Asset may be created for one item");
        DiscoveredItem stored = repository.findById(item.getId()).block();
        assertEquals(DiscoveredItemStatus.PROMOTED, stored.getStatus());
        assertNotNull(stored.getPromotedAssetId());
    }

    @Test
    @DisplayName("when the Asset Registry refuses, the item goes back to UNDER_REVIEW and can be promoted again")
    void aRefusalReleasesTheReservation() {
        DiscoveredItem item = underReview("race-2");
        registry.failNext = true;

        assertTrue(Mono.from(promote.execute(item.getId(), EXECUTOR)).map(r -> false).onErrorReturn(true).block());
        DiscoveredItem back = repository.findById(item.getId()).block();
        assertEquals(DiscoveredItemStatus.UNDER_REVIEW, back.getStatus());
        assertNull(back.getPromotedAssetId());

        assertEquals("PROMOTED", promote.execute(item.getId(), EXECUTOR).block().status());
    }
}
