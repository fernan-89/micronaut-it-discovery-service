# Thinklab IT Discovery Service

**Version:** v1.0.0-BIAN

**Status:** Reference implementation (ThinkLab portfolio project)

## Overview

The Thinklab IT Discovery Service is the platform's staging area for configuration items that have
been detected but not yet onboarded into the authoritative inventory (BIAN `it-discovery`). v1 is a
staging service with manual promotion, not a scanner: any collector — a human via Postman/curl today,
a future nmap/Proxmox/agent integration tomorrow — reports a candidate `DiscoveredItem` through the
same `initiate` endpoint; a human reviewer then claims it, records a suggested Asset category and
optionally a matching existing Asset, and promotes it into a real Asset on
`it-asset-registry-service` (ADR-030).

Re-ingesting the same `(organisationId, source, externalKey)` is always safe: a first sighting creates
the item, every later sighting only refreshes its attributes and `lastSeenAt` — a terminal item
(`PROMOTED`/`IGNORED`) is never silently resurrected by a collector re-scanning it (ADR-031).
Promotion is a synchronous, never-fail-open call to `it-asset-registry-service`: a downstream schema
rejection or conflict is relayed back as a clear, retryable error, leaving the item safely in
`UNDER_REVIEW` rather than half-promoted (ADR-032).

Built with Java 21 and Micronaut 4.4.2 on a strict Hexagonal Architecture and a fully reactive stack
(Project Reactor, reactive MongoDB driver).

## Technology Stack

* **Runtime:** Java 21 LTS
* **Framework:** Micronaut 4.4.2 (AOT optimized, reflection-free DI and Serde)
* **Reactive Engine:** Project Reactor (Mono / Flux)
* **Persistence:** Reactive MongoDB (`thinklab_discovery_db`, collection `discovered_items`), BSON UUID standard representation, unique `(organisationId, source, externalKey)` index
* **Integration:** synchronous `@Client` call to `it-asset-registry-service` on `control/promote` only — never fail-open (ADR-032)
* **Observability:** W3C Trace Context, SLF4J/Logback, Reactor MDC bridge
* **Containerization:** Google Distroless (nonroot), read-only root filesystem
* **Testing:** JUnit 5, Mockito, Reactor Test (domain, use cases, controller, adapters, mapper, index initializer)
* **Documentation:** OpenAPI 3.0 / Swagger generated at compile time

## Domain Model

```text
DiscoveredItem {
  id, organisationId, source, externalKey, name, suggestedCategory?, rawAttributes{},
  matchedAssetId?, promotedAssetId?, status, firstSeenAt, lastSeenAt, createdAt, updatedAt,
  auditTrail[ { occurredAt, action, executor, fromStatus?, toStatus, detail } ]
}
suggestedCategory: LAPTOP | DESKTOP | SERVER | NETWORK_DEVICE | STORAGE_ARRAY | PERIPHERAL |
                    MOBILE_DEVICE | IOT_SENSOR | VIRTUAL_MACHINE | SOFTWARE_LICENSE
status:   DISCOVERED | UNDER_REVIEW | PROMOTED | IGNORED
```

### Lifecycle (ADR-030)

```text
DISCOVERED -> UNDER_REVIEW (review/claim)
UNDER_REVIEW -> PROMOTED (control/promote, terminal; requires a recorded suggestedCategory)
DISCOVERED | UNDER_REVIEW -> IGNORED (control/ignore, terminal)
any status -> itself, refreshed (re-ingestion / RESEEN, ADR-031) - never reopens a terminal item
```

`review/update` records a `suggestedCategory` and/or a `matchedAssetId` on any non-terminal item.
`control/promote` is only legal from `UNDER_REVIEW` and only once a `suggestedCategory` is recorded —
with no `matchedAssetId`, promotion creates a brand-new Asset (`serialNumber = externalKey`); with one,
it updates that existing Asset instead.

## BIAN Behavior Qualifier Contract (`/it-discovery/v1`)

`X-Tenant-Id` (Organisation UUID) is mandatory on `initiate` and the collection `retrieve`;
`X-Executor` is mandatory on every mutation and is recorded in the audit ledger. There is no `DELETE`.

| Behavior Qualifier | Method & Path |
|---|---|
| initiate | `POST /it-discovery/v1/initiate` |
| retrieve (single) | `GET /it-discovery/v1/{id}/retrieve` |
| retrieve (collection, filters `status`, `source`, `category`) | `GET /it-discovery/v1/retrieve` |
| review/claim | `PUT /it-discovery/v1/{id}/review/claim` |
| review/update | `PUT /it-discovery/v1/{id}/review/update` |
| control/ignore | `PUT /it-discovery/v1/{id}/control/ignore` |
| control/promote | `PUT /it-discovery/v1/{id}/control/promote` |
| audit-log/retrieve | `GET /it-discovery/v1/{id}/audit-log/retrieve` |

`initiate` is idempotent on `(organisationId, source, externalKey)`: a first sighting returns
**201 Created**; a re-sighting of the same key returns **200 OK** with the refreshed item, whatever its
current status (ADR-031).

### Error catalog (RFC 7807, `error_code` field)

| error_code | HTTP | Meaning |
|---|---|---|
| `ERR-DSC-00404` | 404 | DiscoveredItem not found |
| `ERR-DSC-00409` | 409 | Illegal lifecycle transition, or a relayed 404/409 from `it-asset-registry-service` during promotion (ADR-032) |
| `ERR-DSC-00422` | 422 | A relayed schema-validation rejection from `it-asset-registry-service` during promotion (ADR-032); carries a `violations` array |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

Example:

```bash
curl -X POST http://localhost:8091/it-discovery/v1/initiate \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: 6f1c7a52-3d0b-4a44-9c3e-0a7d1f6e2b10" \
  -H "X-Executor: admin-user-01" \
  -d '{"source":"manual","externalKey":"AA:BB:CC:DD:EE:FF","name":"Mystery Box","rawAttributes":{"mac":"AA:BB:CC:DD:EE:FF"}}'
```

## Operational Procedures

```bash
# Build, run AOT optimizations and test
./gradlew clean build

# Start the service (default port 8091)
./gradlew run

# Container image
docker build -t thinklab-it-discovery-service:latest .
```

* **Health:** `http://localhost:8091/health`
* **Swagger UI:** `http://localhost:8091/swagger-ui`
* **Postman suite:** `docs/postman/` (lifecycle + promotion + negative scenarios)

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `MICRONAUT_SERVER_PORT` | `8091` | HTTP port |
| `MONGODB_URI` | `mongodb://localhost:27017/thinklab_discovery_db` | MongoDB connection |
| `HASH_SERVICE_URL` | `http://localhost:8080` | Hash Token Registry base URL |
| `ASSET_REGISTRY_SERVICE_URL` | `http://localhost:8083` | IT Asset Registry base URL (promotion only, ADR-032) |

## Architecture Decision Records

`docs/adr/`: 001 hexagonal reactive stack · 005 UUID identity sovereignty · 013 BIAN service domain
conventions · 019 HTTP 409 for state conflicts · 030 promotion requires UNDER_REVIEW first · 031
idempotent re-ingestion never resurrects terminal items · 032 synchronous promotion integration and
its error-relay rule.

### Automated Tests

```bash
./gradlew test                          # unit suite + 100% line/branch coverage gate (no Docker needed)
./gradlew integrationTest               # Testcontainers suite against a real MongoDB (needs Docker)
./gradlew check                         # both, as CI runs it
```

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
