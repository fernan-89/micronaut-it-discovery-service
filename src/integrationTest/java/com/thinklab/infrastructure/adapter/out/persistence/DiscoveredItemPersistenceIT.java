package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.exception.DuplicateDiscoveredItemException;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DiscoveredItem aggregate through its repository against a real MongoDB: every granular update,
 * tenant-scoped filtering, not-found handling, the database taken from {@code mongodb.uri}, and the
 * unique index {@link com.thinklab.infrastructure.adapter.out.persistence.repository.DiscoveredItemIndexInitializer}
 * creates at startup - including the real concurrent-ingestion race it guards against.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiscoveredItemPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_discovery_it";
    private static final String EXECUTOR = "discovery-admin";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject
    DiscoveredItemRepository repository;

    @Inject
    MongoClient mongoClient;

    private static DiscoveredItem newItem(UUID organisationId, String source, String externalKey) {
        return DiscoveredItem.ingest(UUID.randomUUID(), organisationId, source, externalKey, "n", Map.of("k", "v"), EXECUTOR);
    }

    @Test
    @DisplayName("a created DiscoveredItem is read back as-is")
    void createAndFind() {
        UUID organisationId = UUID.randomUUID();
        DiscoveredItem created = repository.create(newItem(organisationId, "manual", "key-1")).block();

        DiscoveredItem found = repository.findById(created.getId()).block();

        assertEquals(DiscoveredItemStatus.DISCOVERED, found.getStatus());
        assertEquals("manual", found.getSource());
        assertEquals(1, found.getAuditTrail().size());
        assertNotNull(found.getCreatedAt());
    }

    @Test
    @DisplayName("findByOrganisationIdAndSourceAndExternalKey resolves the real ingest key, or empty when never seen")
    void findByIngestKey() {
        UUID organisationId = UUID.randomUUID();
        DiscoveredItem created = repository.create(newItem(organisationId, "manual", "key-2")).block();

        DiscoveredItem found = repository.findByOrganisationIdAndSourceAndExternalKey(organisationId, "manual", "key-2").block();
        assertEquals(created.getId(), found.getId());

        assertNull(repository.findByOrganisationIdAndSourceAndExternalKey(organisationId, "manual", "never-seen").block());
    }

    @Test
    @DisplayName("updateReseen persists the refreshed attributes/lastSeenAt and appends the audit entry")
    void updateReseen() {
        DiscoveredItem created = repository.create(newItem(UUID.randomUUID(), "manual", "key-3")).block();
        Instant lastSeen = Instant.now();
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(lastSeen, "RESEEN", EXECUTOR,
                DiscoveredItemStatus.DISCOVERED, DiscoveredItemStatus.DISCOVERED, "reseen");

        repository.updateReseen(created.getId(), Map.of("k", "v2"), lastSeen, entry).block();

        DiscoveredItem found = repository.findById(created.getId()).block();
        assertEquals("v2", found.getRawAttributes().get("k"));
        assertEquals(2, found.getAuditTrail().size());
    }

    @Test
    @DisplayName("updateReview persists the suggestedCategory/matchedAssetId and appends the audit entry")
    void updateReview() {
        DiscoveredItem created = repository.create(newItem(UUID.randomUUID(), "manual", "key-4")).block();
        UUID matchedAssetId = UUID.randomUUID();
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(Instant.now(), "REVIEW_UPDATED", EXECUTOR,
                DiscoveredItemStatus.DISCOVERED, DiscoveredItemStatus.DISCOVERED, "reviewed");

        repository.updateReview(created.getId(), AssetCategory.SERVER, matchedAssetId, entry).block();

        DiscoveredItem found = repository.findById(created.getId()).block();
        assertEquals(AssetCategory.SERVER, found.getSuggestedCategory());
        assertEquals(matchedAssetId, found.getMatchedAssetId());
    }

    @Test
    @DisplayName("updateStatus persists the transition and appends the audit entry")
    void updateStatus() {
        DiscoveredItem created = repository.create(newItem(UUID.randomUUID(), "manual", "key-5")).block();
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(Instant.now(), "CLAIMED", EXECUTOR,
                DiscoveredItemStatus.DISCOVERED, DiscoveredItemStatus.UNDER_REVIEW, "claimed");

        repository.updateStatus(created.getId(), DiscoveredItemStatus.UNDER_REVIEW, entry).block();

        DiscoveredItem found = repository.findById(created.getId()).block();
        assertEquals(DiscoveredItemStatus.UNDER_REVIEW, found.getStatus());
    }

    @Test
    @DisplayName("updatePromotion persists the terminal transition and the promotedAssetId")
    void updatePromotion() {
        DiscoveredItem created = repository.create(newItem(UUID.randomUUID(), "manual", "key-6")).block();
        UUID promotedAssetId = UUID.randomUUID();
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(Instant.now(), "PROMOTED", EXECUTOR,
                DiscoveredItemStatus.UNDER_REVIEW, DiscoveredItemStatus.PROMOTED, "promoted");

        repository.updatePromotion(created.getId(), DiscoveredItemStatus.PROMOTED, promotedAssetId, entry).block();

        DiscoveredItem found = repository.findById(created.getId()).block();
        assertEquals(DiscoveredItemStatus.PROMOTED, found.getStatus());
        assertEquals(promotedAssetId, found.getPromotedAssetId());
    }

    @Test
    @DisplayName("the unique (organisationId, source, externalKey) index rejects a second concurrent ingest of the same key")
    void duplicateIngestKeyIsRejectedByTheIndex() {
        UUID organisationId = UUID.randomUUID();
        repository.create(newItem(organisationId, "manual", "key-7")).block();

        assertThrows(DuplicateDiscoveredItemException.class,
                () -> repository.create(newItem(organisationId, "manual", "key-7")).block());
    }

    @Test
    @DisplayName("the same externalKey from a different source, or for a different tenant, never collides")
    void sameExternalKeyDoesNotCollideAcrossSourceOrTenant() {
        UUID organisationId = UUID.randomUUID();
        DiscoveredItem first = repository.create(newItem(organisationId, "manual", "key-8")).block();
        DiscoveredItem differentSource = repository.create(newItem(organisationId, "nmap-scanner-01", "key-8")).block();
        DiscoveredItem differentTenant = repository.create(newItem(UUID.randomUUID(), "manual", "key-8")).block();

        assertNotNull(repository.findById(first.getId()).block());
        assertNotNull(repository.findById(differentSource.getId()).block());
        assertNotNull(repository.findById(differentTenant.getId()).block());
    }

    @Test
    @DisplayName("listing is tenant-scoped and honours the optional status/source/category filters")
    void listingFilters() {
        UUID organisation = UUID.randomUUID();
        DiscoveredItem laptop = repository.create(newItem(organisation, "manual", "key-9")).block();
        DiscoveredItem server = repository.create(newItem(organisation, "nmap-scanner-01", "key-10")).block();
        repository.create(newItem(UUID.randomUUID(), "manual", "key-11")).block();
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(Instant.now(), "REVIEW_UPDATED", EXECUTOR,
                DiscoveredItemStatus.DISCOVERED, DiscoveredItemStatus.DISCOVERED, "reviewed");
        repository.updateReview(laptop.getId(), AssetCategory.LAPTOP, null, entry).block();
        repository.updateReview(server.getId(), AssetCategory.SERVER, null, entry).block();

        assertEquals(Set.of(laptop.getId(), server.getId()),
                ids(repository.findAllByOrganisationId(organisation, null, null, null).collectList().block()));
        assertEquals(Set.of(laptop.getId()),
                ids(repository.findAllByOrganisationId(organisation, null, "manual", null).collectList().block()));
        assertEquals(Set.of(server.getId()),
                ids(repository.findAllByOrganisationId(organisation, null, null, AssetCategory.SERVER).collectList().block()));
        assertEquals(Set.of(laptop.getId(), server.getId()),
                ids(repository.findAllByOrganisationId(organisation, DiscoveredItemStatus.DISCOVERED, null, null).collectList().block()));
    }

    @Test
    @DisplayName("an unknown DiscoveredItem is empty on read and DiscoveredItemNotFoundException on update")
    void notFound() {
        UUID unknown = UUID.randomUUID();
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(Instant.now(), "CLAIMED", EXECUTOR,
                DiscoveredItemStatus.DISCOVERED, DiscoveredItemStatus.UNDER_REVIEW, "x");

        assertNull(repository.findById(unknown).block());
        assertThrows(DiscoveredItemNotFoundException.class, () -> repository.updateStatus(unknown, DiscoveredItemStatus.UNDER_REVIEW, entry).block());
    }

    @Test
    @DisplayName("the unique (organisationId, source, externalKey) index exists on discovered_items")
    void uniqueIndexExists() {
        repository.create(newItem(UUID.randomUUID(), "manual", "key-12")).block();

        List<Document> indexes = Flux.from(mongoClient.getDatabase(DATABASE).getCollection("discovered_items").listIndexes()).collectList().block();

        assertTrue(indexes.stream().anyMatch(index ->
                        new Document("organisationId", 1).append("source", 1).append("externalKey", 1).equals(index.get("key", Document.class))
                                && Boolean.TRUE.equals(index.getBoolean("unique"))),
                () -> "discovered_items: " + indexes);
    }

    private static Set<UUID> ids(List<DiscoveredItem> list) {
        return list.stream().map(DiscoveredItem::getId).collect(Collectors.toSet());
    }
}
