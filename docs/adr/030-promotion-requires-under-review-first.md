# ADR-030: Promotion Requires UNDER_REVIEW First, Never a Direct Auto-Fusion into Asset

## Status
Accepted

## Context
`it-discovery-service` v1 is a staging area, not a scanner: a `DiscoveredItem` is ingested via a plain
`initiate` call (today by a human via Postman/curl; a future automated collector — nmap, Proxmox, an
agent — would call the exact same endpoint), and only later does it become a real Asset in
`it-asset-registry-service`.

The design question was whether `control/promote` should be reachable directly from `DISCOVERED`
(auto-fuse: "ingest and immediately materialize an Asset") or must first pass through a human-claimed
`UNDER_REVIEW` state.

A `DiscoveredItem` carries almost no verified information at ingest time: `suggestedCategory` is
`null` and `matchedAssetId` is `null` — a collector reports raw facts (a MAC address, a hostname), not
a BIAN `AssetCategory` or a confirmed link to an existing Asset. Promoting straight from `DISCOVERED`
would mean promoting with no category at all, which `Asset.createNew` cannot accept, or inventing a
default category no collector actually asserted — silently wrong inventory data, exactly the failure
mode a staging area exists to prevent.

## Decision
1. **Lifecycle: `DISCOVERED -> UNDER_REVIEW -> PROMOTED|IGNORED`.** `review/claim` is the only way out
   of `DISCOVERED`, and `control/promote` is only legal from `UNDER_REVIEW` — enforced both by
   `DiscoveredItem.promote()` (throws `InvalidDiscoveredItemStatusException`, HTTP 409) and, before any
   downstream HTTP call, by `PromoteDiscoveredItemUseCase`'s own pre-check (so a promotion that is going
   to be rejected never spends a call on `it-asset-registry-service`, mirroring the duplicate-serial
   pre-check in that same service's own `InitiateAssetUseCase`).
2. **`control/promote` also requires a `suggestedCategory`** recorded via `review/update` — the one
   piece of human judgment this service cannot derive on its own. `matchedAssetId` stays optional: its
   presence or absence is what decides whether promotion creates a brand-new Asset or updates an
   existing one (ADR-032).
3. **No scanner, no automated promotion, in v1.** Every transition — claim, review/update, promote,
   ignore — is a deliberate call made by a human (or a future automation acting with the same
   authority); this journey does not add any background job that promotes on its own.

## Consequences
- Positive: no Asset is ever created with fabricated or missing category data; every promoted Asset's
  category reflects an actual human (or trusted automation) decision, not a guess.
- Positive: the same `initiate` endpoint already supports a future real collector — adding one is
  additive (a new `source` value), not a redesign of this lifecycle.
- Negative: promotion is at least two calls (`review/claim` or `review/update`, then
  `control/promote`), never a single "ingest and done" round trip — an accepted v1 trade-off; nothing
  here prevents a future client from chaining `claim` + `review/update` + `promote` transparently for
  the operator.
