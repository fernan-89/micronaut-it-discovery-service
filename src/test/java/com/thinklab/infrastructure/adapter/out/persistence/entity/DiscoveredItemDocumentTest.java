package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.DiscoveredItemDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.DiscoveredItemDocument.DiscoveredItemPersistenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredItemDocumentTest {

    @Test
    @DisplayName("toDocument / toDomain should round-trip the whole aggregate including the audit ledger")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        UUID matchedAssetId = UUID.randomUUID();
        DiscoveredItem item = DiscoveredItem.ingest(id, organisationId, "manual", "AA:BB", "Rack Server", Map.of("mac", "AA:BB"), "ops");
        item.claim("ops");
        item.updateReview(AssetCategory.SERVER, matchedAssetId, "ops");
        UUID promotedAssetId = UUID.randomUUID();
        item.promote(promotedAssetId, "ops");

        DiscoveredItemDocument doc = DiscoveredItemPersistenceMapper.toDocument(item);
        DiscoveredItem restored = DiscoveredItemPersistenceMapper.toDomain(doc);

        assertEquals(id, doc.getId());
        assertEquals("PROMOTED", doc.getStatus());
        assertEquals("SERVER", doc.getSuggestedCategory());
        assertEquals(4, doc.getAuditTrail().size());
        assertEquals(id, restored.getId());
        assertEquals(organisationId, restored.getOrganisationId());
        assertEquals("manual", restored.getSource());
        assertEquals("AA:BB", restored.getExternalKey());
        assertEquals("Rack Server", restored.getName());
        assertEquals(AssetCategory.SERVER, restored.getSuggestedCategory());
        assertEquals(Map.of("mac", "AA:BB"), restored.getRawAttributes());
        assertEquals(matchedAssetId, restored.getMatchedAssetId());
        assertEquals(promotedAssetId, restored.getPromotedAssetId());
        assertEquals(DiscoveredItemStatus.PROMOTED, restored.getStatus());
        assertEquals(item.getFirstSeenAt(), restored.getFirstSeenAt());
        assertEquals(item.getLastSeenAt(), restored.getLastSeenAt());
        assertEquals(item.getCreatedAt(), restored.getCreatedAt());
        assertEquals(item.getUpdatedAt(), restored.getUpdatedAt());
        assertEquals(item.getAuditTrail(), restored.getAuditTrail());
    }

    @Test
    @DisplayName("toDomain should default a missing status to DISCOVERED, a null suggestedCategory, and a missing trail to empty")
    void defaultsWhenFieldsMissing() {
        DiscoveredItemDocument doc = new DiscoveredItemDocument();
        doc.setId(UUID.randomUUID());
        doc.setOrganisationId(UUID.randomUUID());
        doc.setSource("manual");
        doc.setExternalKey("k");
        doc.setName("n");
        doc.setAuditTrail(null);

        DiscoveredItem restored = DiscoveredItemPersistenceMapper.toDomain(doc);

        assertEquals(DiscoveredItemStatus.DISCOVERED, restored.getStatus());
        assertNull(restored.getSuggestedCategory());
        assertTrue(restored.getAuditTrail().isEmpty());
    }

    @Test
    @DisplayName("AuditEntryDocument should convert null statuses both ways")
    void auditEntryNullStatuses() {
        DiscoveredItemAuditEntry entry = new DiscoveredItemAuditEntry(Instant.parse("2026-03-01T10:00:00Z"), "DISCOVERED", "ops",
                null, DiscoveredItemStatus.DISCOVERED, "d");

        AuditEntryDocument doc = AuditEntryDocument.fromDomain(entry);

        assertNull(doc.fromStatus());
        assertEquals("DISCOVERED", doc.toStatus());
        assertEquals(entry, doc.toDomain());
    }

    @Test
    @DisplayName("the persistence mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<DiscoveredItemPersistenceMapper> constructor = DiscoveredItemPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }

    @Test
    @DisplayName("plain accessors should expose what was set (POJO codec contract)")
    void accessors() {
        DiscoveredItemDocument doc = new DiscoveredItemDocument();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        doc.setId(id);
        doc.setStatus("UNDER_REVIEW");
        doc.setFirstSeenAt(now);
        doc.setLastSeenAt(now);
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        doc.setMatchedAssetId(id);
        doc.setPromotedAssetId(id);
        doc.setRawAttributes(Map.of("a", "b"));

        assertEquals(id, doc.getId());
        assertEquals("UNDER_REVIEW", doc.getStatus());
        assertEquals(now, doc.getFirstSeenAt());
        assertEquals(now, doc.getLastSeenAt());
        assertEquals(now, doc.getCreatedAt());
        assertEquals(now, doc.getUpdatedAt());
        assertEquals(id, doc.getMatchedAssetId());
        assertEquals(id, doc.getPromotedAssetId());
        assertEquals(Map.of("a", "b"), doc.getRawAttributes());
    }
}
