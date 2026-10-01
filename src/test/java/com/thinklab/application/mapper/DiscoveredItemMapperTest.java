package com.thinklab.application.mapper;

import com.thinklab.application.dto.request.InitiateDiscoveredItemRequest;
import com.thinklab.application.dto.response.DiscoveredItemAuditEntryResponse;
import com.thinklab.application.dto.response.DiscoveredItemResponse;
import com.thinklab.domain.model.DiscoveredItem;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
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

class DiscoveredItemMapperTest {

    @Test
    @DisplayName("toDomain should build a DISCOVERED aggregate from the request, ID and tenant")
    void toDomain() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        InitiateDiscoveredItemRequest request = new InitiateDiscoveredItemRequest("manual", "AA:BB", "Mystery Box", Map.of("mac", "AA:BB"));

        DiscoveredItem item = DiscoveredItemMapper.toDomain(request, id, organisationId, "exec");

        assertEquals(id, item.getId());
        assertEquals(organisationId, item.getOrganisationId());
        assertEquals("manual", item.getSource());
        assertEquals("AA:BB", item.getExternalKey());
        assertEquals(DiscoveredItemStatus.DISCOVERED, item.getStatus());
        assertEquals("exec", item.getAuditTrail().get(0).executor());
    }

    @Test
    @DisplayName("toResponse should flatten enums to names, nulls staying null, and copy every field")
    void toResponseWithoutSuggestedCategory() {
        UUID id = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        DiscoveredItem item = DiscoveredItem.ingest(id, organisationId, "manual", "AA:BB", "Mystery Box", Map.of("mac", "AA:BB"), "exec");

        DiscoveredItemResponse response = DiscoveredItemMapper.toResponse(item);

        assertEquals(id, response.id());
        assertEquals(organisationId, response.organisationId());
        assertEquals("manual", response.source());
        assertEquals("AA:BB", response.externalKey());
        assertEquals("Mystery Box", response.name());
        assertNull(response.suggestedCategory());
        assertEquals(Map.of("mac", "AA:BB"), response.rawAttributes());
        assertNull(response.matchedAssetId());
        assertNull(response.promotedAssetId());
        assertEquals("DISCOVERED", response.status());
        assertEquals(item.getFirstSeenAt(), response.firstSeenAt());
        assertEquals(item.getLastSeenAt(), response.lastSeenAt());
        assertEquals(item.getCreatedAt(), response.createdAt());
        assertEquals(item.getUpdatedAt(), response.updatedAt());
    }

    @Test
    @DisplayName("toResponse should surface a recorded suggestedCategory by name")
    void toResponseWithSuggestedCategory() {
        UUID id = UUID.randomUUID();
        DiscoveredItem item = DiscoveredItem.ingest(id, UUID.randomUUID(), "manual", "AA:BB", "n", null, "exec");
        item.updateReview(AssetCategory.SERVER, UUID.randomUUID(), "reviewer-1");

        DiscoveredItemResponse response = DiscoveredItemMapper.toResponse(item);

        assertEquals("SERVER", response.suggestedCategory());
    }

    @Test
    @DisplayName("toResponse(audit entry) should tolerate a null fromStatus (the discovering entry)")
    void auditEntryToResponse() {
        Instant now = Instant.now();

        DiscoveredItemAuditEntryResponse discovering = DiscoveredItemMapper.toResponse(
                new DiscoveredItemAuditEntry(now, "DISCOVERED", "exec", null, DiscoveredItemStatus.DISCOVERED, "found"));
        DiscoveredItemAuditEntryResponse transition = DiscoveredItemMapper.toResponse(
                new DiscoveredItemAuditEntry(now, "CLAIMED", "exec", DiscoveredItemStatus.DISCOVERED, DiscoveredItemStatus.UNDER_REVIEW, "claimed"));

        assertNull(discovering.fromStatus());
        assertEquals("DISCOVERED", discovering.toStatus());
        assertEquals("DISCOVERED", discovering.action());
        assertEquals(now, discovering.occurredAt());
        assertEquals("DISCOVERED", transition.fromStatus());
        assertEquals("UNDER_REVIEW", transition.toStatus());
        assertEquals("claimed", transition.detail());
    }

    @Test
    @DisplayName("The mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<DiscoveredItemMapper> constructor = DiscoveredItemMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause());
    }
}
