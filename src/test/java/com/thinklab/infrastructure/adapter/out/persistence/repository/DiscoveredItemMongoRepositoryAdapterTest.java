package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.exception.DuplicateDiscoveredItemException;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.DiscoveredItemDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.DiscoveredItemDocument.DiscoveredItemPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class DiscoveredItemMongoRepositoryAdapterTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.withUuidRepresentation(
            CodecRegistries.fromRegistries(
                    MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())),
            org.bson.UuidRepresentation.STANDARD);

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<DiscoveredItemDocument> mongoCollection;

    private DiscoveredItemMongoRepositoryAdapter adapter;
    private UUID organisationId;
    private UUID itemId;
    private DiscoveredItem item;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("thinklab_discovery_db")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("discovered_items", DiscoveredItemDocument.class)).thenReturn(mongoCollection);
        when(mongoCollection.withCodecRegistry(any())).thenReturn(mongoCollection);
        adapter = new DiscoveredItemMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017/thinklab_discovery_db");

        organisationId = UUID.randomUUID();
        itemId = UUID.randomUUID();
        item = DiscoveredItem.ingest(itemId, organisationId, "manual", "AA:BB:CC", "Mystery Box",
                Map.of("mac", "AA:BB:CC"), "ops-admin");
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, REGISTRY);
    }

    private static MongoWriteException writeError(int code, String message) {
        return new MongoWriteException(new WriteError(code, message, new BsonDocument()), new ServerAddress());
    }

    @Test
    @DisplayName("create should insert the mapped document and emit the aggregate")
    void createSuccess() {
        when(mongoCollection.insertOne(any(DiscoveredItemDocument.class)))
                .thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(adapter.create(item))
                .expectNextMatches(saved -> saved.getId().equals(itemId))
                .verifyComplete();

        ArgumentCaptor<DiscoveredItemDocument> captor = ArgumentCaptor.forClass(DiscoveredItemDocument.class);
        verify(mongoCollection).insertOne(captor.capture());
        assertEquals("DISCOVERED", captor.getValue().getStatus());
        assertEquals(1, captor.getValue().getAuditTrail().size());
    }

    @Test
    @DisplayName("create should propagate a driver failure")
    void createFailure() {
        when(mongoCollection.insertOne(any(DiscoveredItemDocument.class))).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(adapter.create(item)).expectErrorMessage("mongo down").verify();
    }

    @Test
    @DisplayName("create maps a duplicate ingest key to DuplicateDiscoveredItemException (the concurrent-ingest race)")
    void createDuplicateIngestKey() {
        when(mongoCollection.insertOne(any(DiscoveredItemDocument.class))).thenReturn(Mono.error(writeError(11000,
                "E11000 duplicate key error collection: thinklab_discovery_db.discovered_items index: organisationId_1_source_1_externalKey_1 dup key")));

        StepVerifier.create(adapter.create(item))
                .expectErrorSatisfies(e -> {
                    assertTrue(e instanceof DuplicateDiscoveredItemException, e.toString());
                    assertTrue(e.getMessage().contains("AA:BB:CC"), e.getMessage());
                })
                .verify();
    }

    @Test
    @DisplayName("create propagates other write errors, including a duplicate on another index")
    void createOtherWriteErrors() {
        MongoWriteException duplicateId = writeError(11000, "E11000 duplicate key error collection: thinklab_discovery_db.discovered_items index: _id_ dup key");
        MongoWriteException validation = writeError(121, "Document failed validation index: organisationId_1_source_1_externalKey_1");
        when(mongoCollection.insertOne(any(DiscoveredItemDocument.class))).thenReturn(Mono.error(duplicateId)).thenReturn(Mono.error(validation));

        StepVerifier.create(adapter.create(item)).expectErrorMatches(e -> e == duplicateId).verify();
        StepVerifier.create(adapter.create(item)).expectErrorMatches(e -> e == validation).verify();
    }

    @Test
    @DisplayName("findById should map the document back to the aggregate")
    void findByIdSuccess() {
        FindPublisher<DiscoveredItemDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(DiscoveredItemPersistenceMapper.toDocument(item)));

        StepVerifier.create(adapter.findById(itemId))
                .expectNextMatches(found -> found.getId().equals(itemId)
                        && found.getStatus() == DiscoveredItemStatus.DISCOVERED
                        && found.getAuditTrail().size() == 1)
                .verifyComplete();
    }

    @Test
    @DisplayName("findById should complete empty when the item does not exist")
    void findByIdEmpty() {
        FindPublisher<DiscoveredItemDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findById(itemId)).verifyComplete();
    }

    @Test
    @DisplayName("findByOrganisationIdAndSourceAndExternalKey should filter on all three fields")
    void findByIngestKeySuccess() {
        FindPublisher<DiscoveredItemDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(DiscoveredItemPersistenceMapper.toDocument(item)));

        StepVerifier.create(adapter.findByOrganisationIdAndSourceAndExternalKey(organisationId, "manual", "AA:BB:CC"))
                .expectNextMatches(found -> found.getId().equals(itemId))
                .verifyComplete();

        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(captor.capture());
        String rendered = render(captor.getValue()).toJson();
        assertTrue(rendered.contains("organisationId"));
        assertTrue(rendered.contains("manual"));
        assertTrue(rendered.contains("AA:BB:CC"));
    }

    @Test
    @DisplayName("findByOrganisationIdAndSourceAndExternalKey should complete empty when never seen before")
    void findByIngestKeyEmpty() {
        FindPublisher<DiscoveredItemDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findByOrganisationIdAndSourceAndExternalKey(organisationId, "manual", "never-seen")).verifyComplete();
    }

    @Test
    @DisplayName("findAllByOrganisationId should always filter by tenant and add status/source/category when given")
    void findAllFilters() {
        FindPublisher<DiscoveredItemDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<DiscoveredItemDocument> subscriber = invocation.getArgument(0);
            Flux.just(DiscoveredItemPersistenceMapper.toDocument(item)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(adapter.findAllByOrganisationId(organisationId, DiscoveredItemStatus.DISCOVERED, "manual", AssetCategory.LAPTOP))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(captor.capture());
        String rendered = render(captor.getValue()).toJson();
        assertTrue(rendered.contains("organisationId"));
        assertTrue(rendered.contains("DISCOVERED"));
        assertTrue(rendered.contains("manual"));
        assertTrue(rendered.contains("LAPTOP"));
    }

    @Test
    @DisplayName("findAllByOrganisationId without optional filters should only constrain the tenant")
    void findAllTenantOnly() {
        FindPublisher<DiscoveredItemDocument> publisher = mock(FindPublisher.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<DiscoveredItemDocument> subscriber = invocation.getArgument(0);
            Flux.<DiscoveredItemDocument>empty().subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(adapter.findAllByOrganisationId(organisationId, null, null, null)).verifyComplete();

        ArgumentCaptor<Bson> captor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).find(captor.capture());
        String rendered = render(captor.getValue()).toJson();
        assertTrue(rendered.contains("organisationId"));
        assertTrue(!rendered.contains("status") && !rendered.contains("source") && !rendered.contains("suggestedCategory"));
    }

    @Test
    @DisplayName("updateReseen should $set rawAttributes/lastSeenAt/updatedAt and $push a RESEEN audit entry")
    void updateReseen() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        DiscoveredItemAuditEntry auditEntry = item.reseen(Map.of("mac", "AA:BB:CC", "vendor", "Dell"), "collector-1");

        StepVerifier.create(adapter.updateReseen(itemId, item.getRawAttributes(), item.getLastSeenAt(), auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertTrue(doc.getDocument("$set").containsKey("rawAttributes"));
        assertTrue(doc.getDocument("$set").containsKey("lastSeenAt"));
        assertTrue(doc.getDocument("$set").containsKey("updatedAt"));
        assertEquals("RESEEN", doc.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    @Test
    @DisplayName("updateReview should $set suggestedCategory/matchedAssetId and $push a REVIEW_UPDATED audit entry")
    void updateReview() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        UUID matchedAssetId = UUID.randomUUID();
        DiscoveredItemAuditEntry auditEntry = item.updateReview(AssetCategory.SERVER, matchedAssetId, "reviewer-1");

        StepVerifier.create(adapter.updateReview(itemId, AssetCategory.SERVER, matchedAssetId, auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("SERVER", doc.getDocument("$set").getString("suggestedCategory").getValue());
        assertTrue(doc.getDocument("$set").containsKey("matchedAssetId"));
        assertEquals("REVIEW_UPDATED", doc.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    @Test
    @DisplayName("updateReview should $set a null suggestedCategory when clearing it")
    void updateReviewWithNullCategory() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        DiscoveredItemAuditEntry auditEntry = new DiscoveredItemAuditEntry(Instant.now(), "REVIEW_UPDATED", "reviewer-1",
                DiscoveredItemStatus.DISCOVERED, DiscoveredItemStatus.DISCOVERED, "d");

        StepVerifier.create(adapter.updateReview(itemId, null, null, auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        assertTrue(render(update.getValue()).getDocument("$set").get("suggestedCategory").isNull());
    }

    @Test
    @DisplayName("updateStatus should $set status and $push a CLAIMED audit entry")
    void updateStatus() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        DiscoveredItemAuditEntry auditEntry = item.claim("reviewer-1");

        StepVerifier.create(adapter.updateStatus(itemId, DiscoveredItemStatus.UNDER_REVIEW, auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("UNDER_REVIEW", doc.getDocument("$set").getString("status").getValue());
        BsonDocument pushed = doc.getDocument("$push").getDocument("auditTrail");
        assertEquals("CLAIMED", pushed.getString("action").getValue());
    }

    @Test
    @DisplayName("updatePromotion should $set status/promotedAssetId and $push a PROMOTED audit entry")
    void updatePromotion() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        item.claim("reviewer-1");
        item.updateReview(AssetCategory.SERVER, null, "reviewer-1");
        UUID promotedAssetId = UUID.randomUUID();
        DiscoveredItemAuditEntry auditEntry = item.promote(promotedAssetId, "reviewer-1");

        StepVerifier.create(adapter.updatePromotion(itemId, DiscoveredItemStatus.PROMOTED, promotedAssetId, auditEntry)).verifyComplete();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), update.capture());
        BsonDocument doc = render(update.getValue());
        assertEquals("PROMOTED", doc.getDocument("$set").getString("status").getValue());
        assertTrue(doc.getDocument("$set").containsKey("promotedAssetId"));
        assertEquals("PROMOTED", doc.getDocument("$push").getDocument("auditTrail").getString("action").getValue());
    }

    @Test
    @DisplayName("every partial update should fail with DiscoveredItemNotFoundException when no document matches")
    void updatesFailWhenNothingMatches() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        DiscoveredItemAuditEntry auditEntry = item.claim("reviewer-1");

        StepVerifier.create(adapter.updateStatus(itemId, DiscoveredItemStatus.UNDER_REVIEW, auditEntry))
                .expectError(DiscoveredItemNotFoundException.class).verify();
        StepVerifier.create(adapter.updateReseen(itemId, Map.of(), Instant.now(), auditEntry))
                .expectError(DiscoveredItemNotFoundException.class).verify();
        StepVerifier.create(adapter.updateReview(itemId, null, null, auditEntry))
                .expectError(DiscoveredItemNotFoundException.class).verify();
        StepVerifier.create(adapter.updatePromotion(itemId, DiscoveredItemStatus.PROMOTED, UUID.randomUUID(), auditEntry))
                .expectError(DiscoveredItemNotFoundException.class).verify();
    }
}
