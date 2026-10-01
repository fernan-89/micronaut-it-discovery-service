package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates the unique {@code (organisationId, source, externalKey)} index on {@code discovered_items}
 * at startup.
 *
 * <p>The use case looks up this key before every ingest to decide new-vs-reseen, but check-then-insert
 * is not atomic: two concurrent ingestions of the same key could both find nothing. The unique index
 * makes the database the arbiter, and {@link DiscoveredItemMongoRepositoryAdapter} turns the losing
 * insert into the same {@code DuplicateDiscoveredItemException} (409) the lookup produces. This adapter
 * uses the driver directly, so the kit's {@code MongoIndexInitializer} (which reads Micronaut Data
 * {@code @Indexes}) does not see it.
 *
 * <p>Fail-open, like the kit's initializer: {@code createIndex} is idempotent; if it fails the error is
 * logged and the application still starts. Turn it off with {@code thinklab.mongo.create-indexes=false},
 * as unit-test contexts without MongoDB do.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class DiscoveredItemIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String INGEST_KEY_INDEX = "organisationId_1_source_1_externalKey_1";

    private static final Logger log = LoggerFactory.getLogger(DiscoveredItemIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public DiscoveredItemIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    /** Test seam: how long to wait for the server. */
    DiscoveredItemIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DiscoveredItemMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        Document keys = new Document("organisationId", 1).append("source", 1).append("externalKey", 1);
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(DiscoveredItemMongoRepositoryAdapter.COLLECTION_NAME)
                    .createIndex(keys, new IndexOptions().unique(true).name(INGEST_KEY_INDEX))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured unique index [{}] on [{}.{}]", INGEST_KEY_INDEX, database, DiscoveredItemMongoRepositoryAdapter.COLLECTION_NAME);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", INGEST_KEY_INDEX, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create unique index [{}] on [{}.{}] (existing duplicates?): {}",
                    INGEST_KEY_INDEX, database, DiscoveredItemMongoRepositoryAdapter.COLLECTION_NAME, e.getMessage());
        }
    }
}
