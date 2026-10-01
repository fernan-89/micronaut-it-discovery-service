# micronaut-it-discovery-service

BIAN-aligned Service Domain **it-discovery** (Control Record: `DiscoveredItem`), port `8091`.

Scaffolded by `scripts/new-service.ps1`. Add the aggregate, use cases, controller (`/it-discovery/v1/{id}/{behavior-qualifier}`),
persistence adapter, Postman suite and ADRs, keeping `gradlew check` at 100% line and branch coverage.

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-DSC-00404` | 404 | DiscoveredItem not found |
| `ERR-DSC-00409` | 409 | State conflict or duplicate (ADR-019) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## License

Proprietary - all rights reserved. See [LICENSE](LICENSE). This software is not open source.