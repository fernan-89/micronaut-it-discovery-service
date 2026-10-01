# ADR-032: Synchronous Promotion Integration with it-asset-registry-service, and its Error-Relay Rule

## Status
Accepted

## Context
`control/promote` is the one point where this Service Domain reaches into another one:
`it-asset-registry-service` is the authoritative owner of the Asset record this item is becoming. The
call has to decide (a) how to reach that service, (b) whether to create or update an Asset, and (c) how
to translate whatever that service says back into something this service's own caller can act on —
including the 422 that service's own ADR-027 (tenant-configurable specification schema validation) can
now produce.

This is a different integration shape from `it-asset-registry-service`'s own call *to*
`ci-type-catalog-service`: that one is a background check on every write and fails open (a brief
catalog outage must never block an Asset write). Promotion here is the opposite: an explicit,
one-click, human-triggered mutation, not a background check — silently swallowing a downstream failure
would hide a real problem behind a false success.

## Decision
1. **Synchronous HTTP call, never fail-open.** `AssetRegistryServiceAdapter` (implementing
   `AssetRegistryServicePort`) calls `it-asset-registry-service` directly, the same declarative
   `@Client` pattern used by every other cross-service adapter on this platform
   (`WorkflowApprovalServiceAdapter`, `OperationWindowServiceAdapter`). Any failure — HTTP error or
   infrastructure unavailability — surfaces as a domain exception; nothing here ever resolves to a
   silent success the way `ci-type-catalog-service`'s own fail-open lookup does.
2. **No `matchedAssetId` recorded -> `POST /initiate`** on the Asset Registry, creating a brand-new
   Asset with `serialNumber = externalKey`. Accepted as a reasonable v1 default: only the `"manual"`
   source exists in this journey (no real collector yet), so the risk of a serial-number collision from
   reusing a collector's fingerprint as a serial number is theoretical for now. A real collector
   choosing a fingerprint scheme that collides with existing serial numbers is a risk to document for
   whoever builds that collector, not a problem this journey needs to solve pre-emptively.
3. **A `matchedAssetId` recorded -> `PUT /{id}/update`** instead, updating that existing Asset's
   `name`/`specifications` rather than creating a duplicate — the path a reviewer takes when they've
   identified the discovered item as an existing, already-registered Asset (for example, re-detecting a
   device that was already onboarded under a different serial).
4. **Error-relay rule**, translated by `AssetRegistryServiceAdapter.translate`:
   - **422** (the Asset Registry's own schema-validation rejection, ADR-027 of that service) ->
     `PromotionValidationException` / `ERR-DSC-00422`, relaying the downstream `violations` list
     verbatim — this service never re-implements that validation, only relays the verdict.
   - **404 or 409** (a stale `matchedAssetId`, or a serial-number collision on a brand-new Asset) ->
     `PromotionConflictException` / `ERR-DSC-00409` — the item is left in `UNDER_REVIEW` (the
     promotion use case only calls `DiscoveredItem.promote()`, which mutates in-memory state, *after*
     the downstream call succeeds; a failed call never reaches that line, so there is no partial
     mutation to roll back).
   - **Any other HTTP error or infrastructure failure** -> a generic `IllegalStateException` -> HTTP
     500, the same "dependency failure, not a clear domain error" fallback every other adapter on this
     platform uses.

## Consequences
- Positive: a promotion either fully succeeds (the item is `PROMOTED` and a real Asset exists or was
  updated) or fully fails (the item stays `UNDER_REVIEW`, ready to retry) — never a half-applied state.
- Positive: a schema-validation failure on the Asset Registry side is immediately actionable by the
  caller of `control/promote` (the exact violations are right there), not a generic 500.
- Negative: unlike every other integration this service has, this one has no fail-open fallback — a
  genuine `it-asset-registry-service` outage blocks every promotion until it recovers. Accepted: a
  promotion is a deliberate, low-frequency, human-triggered action, not a check on every request, so
  blocking it during a real outage is the correct, visible failure mode, not a silent one.
