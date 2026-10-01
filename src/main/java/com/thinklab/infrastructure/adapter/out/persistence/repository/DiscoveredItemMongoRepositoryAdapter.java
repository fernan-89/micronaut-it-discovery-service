package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import io.micronaut.context.annotation.Property;
import com.thinklab.domain.exception.DiscoveredItemNotFoundException;
import com.thinklab.domain.exception.DuplicateDiscoveredItemException;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.domain.repository.DiscoveredItemRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.DiscoveredItemDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.DiscoveredItemDocument.DiscoveredItemPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.DiscoveredItemDocument.AuditEntryDocument;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter.
 * Implements the pure Domain Port using the low-level Reactive Streams MongoDB Driver and strictly
 * enforces Partial State Mutations: every transition is a single atomic {@code $set} + {@code $push}
 * that also appends the forensic audit entry, so state and ledger can never diverge.
 */
@Singleton
public class DiscoveredItemMongoRepositoryAdapter implements DiscoveredItemRepository {

    private static final Logger log = LoggerFactory.getLogger(DiscoveredItemMongoRepositoryAdapter.class);
    /** Used only when {@code mongodb.uri} names no database. */
    static final String DEFAULT_DATABASE = "thinklab_discovery_db";
    static final String COLLECTION_NAME = "discovered_items";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_AUDIT_TRAIL = "auditTrail";

    /**
     * The MongoDB driver's default codec registry has no codec for arbitrary POJOs such as
     * {@link DiscoveredItemDocument}. Without a {@link PojoCodecProvider} every read/write fails with
     * {@code CodecConfigurationException} (lesson learned from the Party Reference Data Directory rollout).
     */
    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;

    private final String database;

    /**
     * The database comes from {@code mongodb.uri}, the same property the MongoDB client and the kit's
     * warm-up use.
     */
    public DiscoveredItemMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<DiscoveredItemDocument> getCollection() {
        return mongoClient.getDatabase(database)
                .getCollection(COLLECTION_NAME, DiscoveredItemDocument.class)
                .withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<DiscoveredItem> create(DiscoveredItem item) {
        log.debug("[PERSISTENCE] Monolithic create for DiscoveredItem Aggregate: {}", item.getId());

        DiscoveredItemDocument document = DiscoveredItemPersistenceMapper.toDocument(item);

        return Mono.from(getCollection().insertOne(document))
                .doOnSuccess(result -> log.debug("[PERSISTENCE] Aggregate successfully created in MongoDB"))
                .map(result -> item)
                .onErrorMap(DiscoveredItemMongoRepositoryAdapter::isDuplicateIngestKey, e -> new DuplicateDiscoveredItemException(String.format(
                        "A DiscoveredItem already exists for organisation [%s], source [%s] and externalKey [%s].",
                        item.getOrganisationId(), item.getSource(), item.getExternalKey())));
    }

    /**
     * The losing insert of two concurrent ingestions of the same {@code (organisationId, source,
     * externalKey)}: the use case's lookup found nothing for both, and the unique index
     * ({@link DiscoveredItemIndexInitializer}) rejected the second.
     */
    private static boolean isDuplicateIngestKey(Throwable error) {
        return error instanceof MongoWriteException write
                && write.getError().getCategory() == ErrorCategory.DUPLICATE_KEY
                && write.getError().getMessage().contains(DiscoveredItemIndexInitializer.INGEST_KEY_INDEX);
    }

    @Override
    public Mono<DiscoveredItem> findById(UUID id) {
        log.debug("[PERSISTENCE] Fetching DiscoveredItem Aggregate by ID: {}", id);

        return Mono.from(getCollection().find(Filters.eq(FIELD_ID, id)).first())
                .map(DiscoveredItemPersistenceMapper::toDomain);
    }

    @Override
    public Mono<DiscoveredItem> findByOrganisationIdAndSourceAndExternalKey(UUID organisationId, String source, String externalKey) {
        log.debug("[PERSISTENCE] Fetching DiscoveredItem by organisation {} source {} externalKey {}", organisationId, source, externalKey);

        Bson filter = Filters.and(
                Filters.eq("organisationId", organisationId),
                Filters.eq("source", source),
                Filters.eq("externalKey", externalKey)
        );

        return Mono.from(getCollection().find(filter).first())
                .map(DiscoveredItemPersistenceMapper::toDomain);
    }

    @Override
    public Flux<DiscoveredItem> findAllByOrganisationId(UUID organisationId, DiscoveredItemStatus status, String source,
                                                         AssetCategory suggestedCategory) {
        log.debug("[PERSISTENCE] Fetching DiscoveredItems for organisation {} status {} source {} category {}",
                organisationId, status, source, suggestedCategory);

        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("organisationId", organisationId));
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (source != null) {
            filters.add(Filters.eq("source", source));
        }
        if (suggestedCategory != null) {
            filters.add(Filters.eq("suggestedCategory", suggestedCategory.name()));
        }

        return Flux.from(getCollection().find(Filters.and(filters)))
                .map(DiscoveredItemPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> updateReseen(UUID id, Map<String, String> rawAttributes, Instant lastSeenAt, DiscoveredItemAuditEntry auditEntry) {
        log.debug("[PERSISTENCE] Partial Mutation: updateReseen for ID: {}", id);

        Bson update = Updates.combine(
                Updates.set("rawAttributes", new LinkedHashMap<>(rawAttributes)),
                Updates.set("lastSeenAt", lastSeenAt),
                Updates.set(FIELD_UPDATED_AT, lastSeenAt),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update);
    }

    @Override
    public Mono<Void> updateReview(UUID id, AssetCategory suggestedCategory, UUID matchedAssetId, DiscoveredItemAuditEntry auditEntry) {
        log.debug("[PERSISTENCE] Partial Mutation: updateReview for ID: {}", id);

        Bson update = Updates.combine(
                Updates.set("suggestedCategory", suggestedCategory != null ? suggestedCategory.name() : null),
                Updates.set("matchedAssetId", matchedAssetId),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update);
    }

    @Override
    public Mono<Void> updateStatus(UUID id, DiscoveredItemStatus status, DiscoveredItemAuditEntry auditEntry) {
        log.debug("[PERSISTENCE] Partial Mutation: updateStatus for ID: {}", id);

        Bson update = Updates.combine(
                Updates.set("status", status.name()),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update);
    }

    @Override
    public Mono<Void> updatePromotion(UUID id, DiscoveredItemStatus status, UUID promotedAssetId, DiscoveredItemAuditEntry auditEntry) {
        log.debug("[PERSISTENCE] Partial Mutation: updatePromotion for ID: {}", id);

        Bson update = Updates.combine(
                Updates.set("status", status.name()),
                Updates.set("promotedAssetId", promotedAssetId),
                Updates.set(FIELD_UPDATED_AT, Instant.now()),
                Updates.push(FIELD_AUDIT_TRAIL, AuditEntryDocument.fromDomain(auditEntry))
        );

        return executeUpdate(id, update);
    }

    /**
     * Helper that executes a partial update and translates a zero-match result into a domain error.
     */
    private Mono<Void> executeUpdate(UUID id, Bson update) {
        return Mono.from(getCollection().updateOne(Filters.eq(FIELD_ID, id), update))
                .flatMap(result -> {
                    if (result.getMatchedCount() == 0) {
                        return Mono.error(new DiscoveredItemNotFoundException(id));
                    }
                    return Mono.empty();
                });
    }
}
