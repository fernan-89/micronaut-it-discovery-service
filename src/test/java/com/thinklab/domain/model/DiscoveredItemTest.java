package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidDiscoveredItemStatusException;
import com.thinklab.domain.model.DiscoveredItem.AssetCategory;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemAuditEntry;
import com.thinklab.domain.model.DiscoveredItem.DiscoveredItemStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredItemTest {

    private static final String EXECUTOR = "ops-admin";

    private UUID id;
    private UUID organisationId;
    private UUID matchedAssetId;
    private DiscoveredItem item;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID();
        organisationId = UUID.randomUUID();
        matchedAssetId = UUID.randomUUID();
        item = DiscoveredItem.ingest(id, organisationId, "manual", "AA:BB:CC", "Mystery Box",
                Map.of("mac", "AA:BB:CC"), EXECUTOR);
    }

    // ---------------------------------------------------------------- ingest

    @Test
    @DisplayName("Should ingest a new DiscoveredItem in DISCOVERED state with a DISCOVERED audit entry")
    void shouldIngestInDiscoveredState() {
        assertEquals(id, item.getId());
        assertEquals(organisationId, item.getOrganisationId());
        assertEquals("manual", item.getSource());
        assertEquals("AA:BB:CC", item.getExternalKey());
        assertEquals("Mystery Box", item.getName());
        assertEquals(DiscoveredItemStatus.DISCOVERED, item.getStatus());
        assertEquals("AA:BB:CC", item.getRawAttributes().get("mac"));
        assertNull(item.getSuggestedCategory());
        assertNull(item.getMatchedAssetId());
        assertNull(item.getPromotedAssetId());
        assertNotNull(item.getFirstSeenAt());
        assertEquals(item.getFirstSeenAt(), item.getLastSeenAt());
        assertEquals(item.getFirstSeenAt(), item.getCreatedAt());
        assertEquals(item.getCreatedAt(), item.getUpdatedAt());

        assertEquals(1, item.getAuditTrail().size());
        DiscoveredItemAuditEntry first = item.getAuditTrail().get(0);
        assertEquals("DISCOVERED", first.action());
        assertEquals(EXECUTOR, first.executor());
        assertNull(first.fromStatus());
        assertEquals(DiscoveredItemStatus.DISCOVERED, first.toStatus());
    }

    @Test
    @DisplayName("Should accept null rawAttributes and expose an empty map")
    void shouldAcceptNullRawAttributes() {
        DiscoveredItem noAttrs = DiscoveredItem.ingest(id, organisationId, "manual", "key-2", "n", null, EXECUTOR);

        assertTrue(noAttrs.getRawAttributes().isEmpty());
    }

    @Test
    @DisplayName("Should reject ingestion when any mandatory field is missing")
    void shouldRejectInvalidIngest() {
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(null, organisationId, "s", "k", "n", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, null, "s", "k", "n", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, organisationId, null, "k", "n", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, organisationId, " ", "k", "n", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, organisationId, "s", null, "n", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, organisationId, "s", " ", "n", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, organisationId, "s", "k", null, null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, organisationId, "s", "k", " ", null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, organisationId, "s", "k", "n", null, null));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.ingest(id, organisationId, "s", "k", "n", null, " "));
    }

    @Test
    @DisplayName("Should defensively copy rawAttributes and keep the exposed views read-only")
    void shouldProtectInternalState() {
        Map<String, String> source = new java.util.HashMap<>(Map.of("k", "v"));
        DiscoveredItem i = DiscoveredItem.ingest(id, organisationId, "s", "k", "n", source, EXECUTOR);
        source.put("mutated", "yes");

        assertFalse(i.getRawAttributes().containsKey("mutated"));
        assertThrows(UnsupportedOperationException.class, () -> i.getRawAttributes().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> i.getAuditTrail().add(null));
    }

    @Test
    @DisplayName("Should expose all ten Asset categories (own copy, ADR-030)")
    void shouldExposeCategories() {
        assertEquals(10, AssetCategory.values().length);
        assertEquals(AssetCategory.IOT_SENSOR, AssetCategory.valueOf("IOT_SENSOR"));
    }

    // ---------------------------------------------------------------- reconstitution

    @Test
    @DisplayName("Should reconstitute a DiscoveredItem from persisted state, defaulting missing status/timestamps/trail")
    void shouldReconstitute() {
        Instant firstSeen = Instant.parse("2026-01-01T00:00:00Z");
        Instant lastSeen = Instant.parse("2026-01-02T00:00:00Z");
        Instant updated = Instant.parse("2026-01-03T00:00:00Z");
        List<DiscoveredItemAuditEntry> trail = new ArrayList<>();
        trail.add(new DiscoveredItemAuditEntry(firstSeen, "DISCOVERED", "x", null, DiscoveredItemStatus.DISCOVERED, "d"));

        DiscoveredItem restored = DiscoveredItem.reconstitute(id, organisationId, "nmap", "key-1", "n",
                AssetCategory.SERVER, Map.of("a", "b"), matchedAssetId, null, DiscoveredItemStatus.UNDER_REVIEW,
                firstSeen, lastSeen, firstSeen, updated, trail);

        assertEquals(DiscoveredItemStatus.UNDER_REVIEW, restored.getStatus());
        assertEquals(AssetCategory.SERVER, restored.getSuggestedCategory());
        assertEquals(matchedAssetId, restored.getMatchedAssetId());
        assertEquals(firstSeen, restored.getFirstSeenAt());
        assertEquals(lastSeen, restored.getLastSeenAt());
        assertEquals(updated, restored.getUpdatedAt());
        assertEquals(1, restored.getAuditTrail().size());

        DiscoveredItem defaults = DiscoveredItem.reconstitute(id, organisationId, "nmap", "key-1", "n",
                null, null, null, null, null, null, null, null, null, null);
        assertEquals(DiscoveredItemStatus.DISCOVERED, defaults.getStatus());
        assertNotNull(defaults.getFirstSeenAt());
        assertEquals(defaults.getFirstSeenAt(), defaults.getLastSeenAt());
        assertEquals(defaults.getFirstSeenAt(), defaults.getCreatedAt());
        assertEquals(defaults.getCreatedAt(), defaults.getUpdatedAt());
        assertTrue(defaults.getAuditTrail().isEmpty());
        assertTrue(defaults.getRawAttributes().isEmpty());
    }

    @Test
    @DisplayName("Should reject reconstitution without mandatory persisted fields")
    void shouldRejectInvalidReconstitution() {
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.reconstitute(null, organisationId, "s", "k", "n", null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.reconstitute(id, null, "s", "k", "n", null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.reconstitute(id, organisationId, null, "k", "n", null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.reconstitute(id, organisationId, "s", null, "n", null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> DiscoveredItem.reconstitute(id, organisationId, "s", "k", null, null, null, null, null, null, null, null, null, null, null));
    }

    // ---------------------------------------------------------------- reseen (idempotent re-ingestion)

    @Test
    @DisplayName("Should refresh lastSeenAt/rawAttributes and append a RESEEN entry without changing status")
    void shouldReseenWithoutChangingStatus() throws InterruptedException {
        Instant originalLastSeen = item.getLastSeenAt();
        Thread.sleep(2);

        DiscoveredItemAuditEntry entry = item.reseen(Map.of("mac", "AA:BB:CC", "vendor", "Dell"), "collector-1");

        assertEquals(DiscoveredItemStatus.DISCOVERED, item.getStatus());
        assertEquals("Dell", item.getRawAttributes().get("vendor"));
        assertTrue(item.getLastSeenAt().isAfter(originalLastSeen));
        assertEquals("RESEEN", entry.action());
        assertEquals(DiscoveredItemStatus.DISCOVERED, entry.fromStatus());
        assertEquals(DiscoveredItemStatus.DISCOVERED, entry.toStatus());
        assertEquals(2, item.getAuditTrail().size());
        assertSame(entry, item.getAuditTrail().get(1));
    }

    @Test
    @DisplayName("Should reseen a terminal item (updates attributes) without ever reopening it")
    void shouldReseenTerminalItemWithoutReopening() {
        item.claim(EXECUTOR);
        item.ignore(EXECUTOR);

        DiscoveredItemAuditEntry entry = item.reseen(Map.of("mac", "AA:BB:CC"), "collector-1");

        assertEquals(DiscoveredItemStatus.IGNORED, item.getStatus());
        assertEquals(DiscoveredItemStatus.IGNORED, entry.fromStatus());
        assertEquals(DiscoveredItemStatus.IGNORED, entry.toStatus());
    }

    @Test
    @DisplayName("Should reject reseen without an executor")
    void shouldRejectReseenWithoutExecutor() {
        assertThrows(IllegalArgumentException.class, () -> item.reseen(Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> item.reseen(Map.of(), " "));
    }

    // ---------------------------------------------------------------- claim

    @Test
    @DisplayName("Should claim a DISCOVERED item for review and append a CLAIMED entry")
    void shouldClaim() {
        DiscoveredItemAuditEntry entry = item.claim("reviewer-1");

        assertEquals(DiscoveredItemStatus.UNDER_REVIEW, item.getStatus());
        assertEquals("CLAIMED", entry.action());
        assertEquals(DiscoveredItemStatus.DISCOVERED, entry.fromStatus());
        assertEquals(DiscoveredItemStatus.UNDER_REVIEW, entry.toStatus());
    }

    @Test
    @DisplayName("Should reject claiming an item that is not DISCOVERED")
    void shouldRejectClaimWhenNotDiscovered() {
        item.claim(EXECUTOR);
        InvalidDiscoveredItemStatusException ex = assertThrows(InvalidDiscoveredItemStatusException.class, () -> item.claim(EXECUTOR));
        assertEquals("ERR-DSC-00409", ex.getErrorCode());

        item.ignore(EXECUTOR);
        assertThrows(InvalidDiscoveredItemStatusException.class, () -> item.claim(EXECUTOR));
    }

    @Test
    @DisplayName("Should reject claim without an executor")
    void shouldRejectClaimWithoutExecutor() {
        assertThrows(IllegalArgumentException.class, () -> item.claim(null));
        assertThrows(IllegalArgumentException.class, () -> item.claim(" "));
    }

    // ---------------------------------------------------------------- review/update

    @Test
    @DisplayName("Should record review findings on a DISCOVERED or UNDER_REVIEW item")
    void shouldUpdateReview() {
        DiscoveredItemAuditEntry entry = item.updateReview(AssetCategory.LAPTOP, matchedAssetId, "reviewer-1");

        assertEquals(AssetCategory.LAPTOP, item.getSuggestedCategory());
        assertEquals(matchedAssetId, item.getMatchedAssetId());
        assertEquals("REVIEW_UPDATED", entry.action());
        assertEquals(DiscoveredItemStatus.DISCOVERED, entry.fromStatus());
        assertEquals(DiscoveredItemStatus.DISCOVERED, entry.toStatus());

        item.claim(EXECUTOR);
        item.updateReview(AssetCategory.DESKTOP, null, "reviewer-2");
        assertEquals(AssetCategory.DESKTOP, item.getSuggestedCategory());
        assertNull(item.getMatchedAssetId());
    }

    @Test
    @DisplayName("Should reject review/update on a terminal item")
    void shouldRejectReviewUpdateWhenTerminal() {
        item.claim(EXECUTOR);
        item.ignore(EXECUTOR);

        InvalidDiscoveredItemStatusException ex = assertThrows(InvalidDiscoveredItemStatusException.class,
                () -> item.updateReview(AssetCategory.LAPTOP, null, EXECUTOR));
        assertEquals("ERR-DSC-00409", ex.getErrorCode());
    }

    @Test
    @DisplayName("Should reject review/update without an executor")
    void shouldRejectReviewUpdateWithoutExecutor() {
        assertThrows(IllegalArgumentException.class, () -> item.updateReview(AssetCategory.LAPTOP, null, null));
        assertThrows(IllegalArgumentException.class, () -> item.updateReview(AssetCategory.LAPTOP, null, " "));
    }

    // ---------------------------------------------------------------- control/ignore

    @Test
    @DisplayName("Should ignore a DISCOVERED item directly (terminal)")
    void shouldIgnoreFromDiscovered() {
        DiscoveredItemAuditEntry entry = item.ignore(EXECUTOR);

        assertEquals(DiscoveredItemStatus.IGNORED, item.getStatus());
        assertEquals("IGNORED", entry.action());
        assertEquals(DiscoveredItemStatus.DISCOVERED, entry.fromStatus());
        assertEquals(DiscoveredItemStatus.IGNORED, entry.toStatus());
    }

    @Test
    @DisplayName("Should ignore an UNDER_REVIEW item (terminal)")
    void shouldIgnoreFromUnderReview() {
        item.claim(EXECUTOR);

        item.ignore(EXECUTOR);

        assertEquals(DiscoveredItemStatus.IGNORED, item.getStatus());
    }

    @Test
    @DisplayName("Should treat IGNORED and PROMOTED as terminal for ignore")
    void shouldRejectIgnoreWhenAlreadyTerminal() {
        item.ignore(EXECUTOR);
        InvalidDiscoveredItemStatusException ex = assertThrows(InvalidDiscoveredItemStatusException.class, () -> item.ignore(EXECUTOR));
        assertEquals("ERR-DSC-00409", ex.getErrorCode());

        DiscoveredItem promoted = DiscoveredItem.ingest(UUID.randomUUID(), organisationId, "s", "k2", "n", null, EXECUTOR);
        promoted.claim(EXECUTOR);
        promoted.updateReview(AssetCategory.SERVER, null, EXECUTOR);
        promoted.promote(UUID.randomUUID(), EXECUTOR);
        assertThrows(InvalidDiscoveredItemStatusException.class, () -> promoted.ignore(EXECUTOR));
    }

    @Test
    @DisplayName("Should reject ignore without an executor")
    void shouldRejectIgnoreWithoutExecutor() {
        assertThrows(IllegalArgumentException.class, () -> item.ignore(null));
        assertThrows(IllegalArgumentException.class, () -> item.ignore(" "));
    }

    // ---------------------------------------------------------------- control/promote

    @Test
    @DisplayName("Should promote an UNDER_REVIEW item with a suggestedCategory (terminal)")
    void shouldPromote() {
        item.claim(EXECUTOR);
        item.updateReview(AssetCategory.SERVER, null, EXECUTOR);
        UUID promotedAssetId = UUID.randomUUID();

        DiscoveredItemAuditEntry entry = item.promote(promotedAssetId, "reviewer-1");

        assertEquals(DiscoveredItemStatus.PROMOTED, item.getStatus());
        assertEquals(promotedAssetId, item.getPromotedAssetId());
        assertEquals("PROMOTED", entry.action());
        assertEquals(DiscoveredItemStatus.UNDER_REVIEW, entry.fromStatus());
        assertEquals(DiscoveredItemStatus.PROMOTED, entry.toStatus());
        assertTrue(entry.detail().contains(promotedAssetId.toString()));
    }

    @Test
    @DisplayName("Should reject promotion when not UNDER_REVIEW")
    void shouldRejectPromoteWhenNotUnderReview() {
        InvalidDiscoveredItemStatusException ex = assertThrows(InvalidDiscoveredItemStatusException.class,
                () -> item.promote(UUID.randomUUID(), EXECUTOR));
        assertEquals("ERR-DSC-00409", ex.getErrorCode());
    }

    @Test
    @DisplayName("Should reject promotion without a recorded suggestedCategory")
    void shouldRejectPromoteWithoutSuggestedCategory() {
        item.claim(EXECUTOR);

        InvalidDiscoveredItemStatusException ex = assertThrows(InvalidDiscoveredItemStatusException.class,
                () -> item.promote(UUID.randomUUID(), EXECUTOR));
        assertTrue(ex.getMessage().contains("suggestedCategory"));
    }

    @Test
    @DisplayName("Should reject promotion without an executor or with a null promotedAssetId")
    void shouldRejectPromoteWithInvalidArguments() {
        item.claim(EXECUTOR);
        item.updateReview(AssetCategory.SERVER, null, EXECUTOR);

        assertThrows(NullPointerException.class, () -> item.promote(null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> item.promote(UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () -> item.promote(UUID.randomUUID(), " "));
    }
}
