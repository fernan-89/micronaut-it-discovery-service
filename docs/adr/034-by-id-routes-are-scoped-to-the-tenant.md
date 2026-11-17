# ADR-034: The By-Id Routes Are Scoped to the Tenant

## Status
Accepted (2026-11-18). Part of the tenant audit of the platform (thinklab-platform `docs/TENANT-AUDIT-2026-11.md`); same rule as workflow-approval ADR-037, change management ADR-038, asset registry ADR-029, hardware maintenance ADR-036 and operation window ADR-022.

## Context
Every route of a discovered item that takes the id found it by id alone. The cross-tenant probe (platform `scripts/e2e/tenant-probe.sh`) showed an item of tenant X answering 200 to tenant Y, retrieve and audit log alike.

## Decision
- Every by-id route takes `X-Tenant-Id` (required, 400 if blank) and finds the item only in that tenant: another tenant's item is the same as none (404 `DiscoveredItemNotFoundException`), nothing is read or written. The repository has no unscoped `findById` any more.
- The promotion of an item calls the asset registry with the item's own tenant (this service already sent it on creating an asset and now also on updating a matched one, discovery#5).

## Consequences
- Positive: a UUID is no longer enough to read, review or promote another tenant's discovered item.
- Negative: a client that sends no tenant on a by-id route now gets a 400.
