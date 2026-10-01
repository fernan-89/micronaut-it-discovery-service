# ADR-031: Idempotent Re-Ingestion Never Resurrects a Terminal Item

## Status
Accepted

## Context
A real collector (even the "manual" source used in v1) will see the same device more than once —
every scan, every manual re-submission. `initiate` needs a stable identity for "the same candidate
item," and a predictable rule for what happens when it's seen again, independent of where that item
currently sits in its lifecycle.

Two designs were available: treat every `initiate` call as a brand-new `DiscoveredItem` (simplest to
implement, but floods the inventory with duplicates every time a collector re-runs), or make ingestion
idempotent on a stable key.

## Decision
1. **Idempotency key: `(organisationId, source, externalKey)`**, enforced by a unique index
   (`DiscoveredItemIndexInitializer`) — the same check-then-insert-is-not-atomic lesson already applied
   platform-wide for `it-asset-registry`'s `(organisationId, serialNumber)`. `externalKey` is the
   collector's own fingerprint for the physical/logical item (a MAC address, a hash of hostname+serial,
   whatever that source can reliably reproduce); `source` disambiguates multiple collectors reporting
   overlapping keys (`"manual"` today, future values like `"nmap-scanner-01"`).
2. **Re-posting the same key always succeeds** (`InitiateDiscoveredItemUseCase`): a first sighting
   creates a new item (HTTP 201); every subsequent sighting — whatever the item's current status —
   calls `DiscoveredItem.reseen()`, which refreshes `lastSeenAt`/`rawAttributes` and appends a
   `RESEEN` audit entry (HTTP 200). No Sovereign ID is spent on a re-sighting.
3. **`reseen()` never changes `status`.** A terminal item (`PROMOTED` or `IGNORED`) that is detected
   again stays exactly as terminal as it was — only its attributes and `lastSeenAt` move. Re-detecting
   something already promoted or explicitly dismissed must never silently reopen it; a human (or a
   deliberate new workflow) would have to act again through the normal lifecycle to change that.

## Consequences
- Positive: a real collector can run on a schedule without flooding the inventory — the same device
  reported a hundred times is still one `DiscoveredItem`, with a ledger of every sighting.
- Positive: "ignore this, I've already dealt with it" and "this was already promoted" both stay true
  forever, regardless of how many more times the same device is detected.
- Negative: there is no way, from re-ingestion alone, to signal "this got un-ignored" or "this needs
  re-review" — that is a deliberate `review/*`/`control/*` call a human makes explicitly, not a side
  effect of a collector re-scanning. If a future need for "re-open a terminal item" emerges, it would be
  a new, explicit Behavior Qualifier, not a change to `reseen()`'s own guarantee.
