# ADR 033: Guarded writes, and a reservation before a promotion creates an Asset

## Status
Accepted (2026-11-17). Found by the platform race audit (ACTION-PLAN 0.5, `thinklab-platform/docs/race-audit.md`).

## Context
Every transition read the item, validated it in the domain and wrote it by `_id` only. Two requests at once both passed the validation and both wrote.
For `claim`, `ignore` and `review/update` that meant the last write won and the trail held both entries. For `control/promote` it was worse: promotion
creates an Asset in `it-asset-registry` (ADR-032), so ten simultaneous promotions of one item created **ten Assets** (measured by `PromotionRaceIT` on
the old code: ten `OK` answers, ten Assets) and kept only the last `promotedAssetId`; nine Assets were orphans.

## Decision
- Every status-changing write is a **guarded write**: it applies only if the item still has the status the caller read (`{_id, status: expected}`). No
  match is `409 ERR-DSC-00409` when the item exists and `404` when it does not (ADR-019). This is the guarded write of the newer registries.
- Promotion is two guarded steps around the call to the Asset Registry. **Reserve** (`UNDER_REVIEW -> PROMOTING`, guarded on the status **and** the
  `updatedAt` that were read) before anything is created; of simultaneous promotions exactly one reserves, the others get 409 and create nothing.
  Then **complete** (`PROMOTING -> PROMOTED`, with the `promotedAssetId`).
- If the Asset Registry refuses (ADR-032: 422, 404, 409, or an outage), the reservation is **released** (`PROMOTING -> UNDER_REVIEW`) and the original
  error goes back to the caller, so the item stays reviewable and the promotion can be tried again. If the release itself fails, the original error is
  still returned and the reservation expires by itself.
- A reservation older than `DiscoveredItem.PROMOTION_LEASE` (five minutes) is considered abandoned (the process that held it died) and can be taken
  over by the next promotion, again with the guard on `updatedAt`, so two takeovers cannot both win. A younger one cannot.
- While an item is `PROMOTING` it cannot be ignored or have its review changed (409).
- `updateReseen` stays unguarded: a re-sighting never changes the status.

## Consequences
- **Positive:** one Asset per promotion, no orphans, and the trail says what happened (`PROMOTION_RESERVED`, then `PROMOTED` or `PROMOTION_RELEASED`).
- **Negative:** a new state, `PROMOTING`, visible to clients (the list can filter by it); and a process that dies after the Asset Registry created the
  Asset but before completing leaves the item `PROMOTING` for up to the lease, after which a retry creates the Asset again (the Asset Registry's own
  serial-number uniqueness is the backstop for that rare case). Closing that gap needs an idempotent Asset creation, a separate change.
