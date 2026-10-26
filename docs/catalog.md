# Catalog and Inventory (Stage 2 authentication)

Catalog owns products, exact variants, inventory and stock audit events in `catalog_db`. REST and gRPC call the same transactional application service. Demand now consumes Catalog gRPC, and the frontend authenticates through Demand. Messaging retains its Stage 0 behavior. No RabbitMQ business event is published.

## Domain and persistence

`Product` contains a UUID, merchant UUID, name, brand, category, ACTIVE/INACTIVE status and UTC creation/update timestamps. `ProductVariant` contains a UUID, product and merchant ownership, SKU, JSONB attributes, optional nonnegative `numeric(19,2)` price, status and timestamps. Price has no currency/conversion/purchasing behavior in this stage; it is optional catalogue metadata. Up to 20 simple string attributes are accepted. Keys are ASCII identifiers of 1–40 characters; values are nonblank strings up to 100 characters. Keys are lowercased, `colour` aliases `color`, and conflicting normalized keys are rejected. Values retain display casing with surrounding whitespace removed.

`Inventory` is created atomically with each variant at quantity zero, with a UUID, merchant/variant ownership, quantity, derived availability and UTC update time. `StockEvent` records every actual quantity change with a UUID, merchant/variant ownership, previous/new quantities, source `MANUAL` and UTC creation time. There is no initial event for zero inventory and no public audit mutation endpoint.

Flyway applies [V1__catalog_inventory.sql](../catalog-service/src/main/resources/db/migration/V1__catalog_inventory.sql). Hibernate only validates the schema (`ddl-auto=validate`). All tables have primary keys and tenant ownership. Composite foreign keys enforce matching merchant ownership between products, variants, inventory and audit records. There are no foreign keys to another service's database. Unique constraints enforce `(merchant_id, sku)` and one inventory row per `(merchant_id, variant_id)`. SKU uniqueness and lookup are case-sensitive after trimming. Tenant/status, tenant/product and tenant/variant/audit-time indexes support the current queries. Product-name substring and JSONB attribute predicates use ordinary PostgreSQL queries; no semantic search or speculative indexing is introduced.

Products and variants deactivate through status updates; there is no DELETE endpoint. Inventory remains readable and manageable for inactive records. Active variant search and gRPC resolution exclude inactive variants and variants whose parent product is inactive.

## Authenticated merchant context

`MerchantContextProvider` derives ownership from validated JWT claims. Demand owns login and credentials; Catalog validates the same HS256 token with `AUTH_JWT_SECRET` and requires MERCHANT_ADMIN. No Catalog User table or separate login exists. The old configured demo provider was removed. Unknown JSON ownership fields are rejected; browser query/header IDs cannot override the principal. Inaccessible resources remain 404.

Every gRPC call requires `authorization: Bearer <token>` metadata. Catalog independently validates signature, expiry, issuer, audience and role, then checks that the request merchant UUID matches the signed identity. Repository operations still filter by merchant. Missing/invalid tokens return UNAUTHENTICATED, wrong roles PERMISSION_DENIED, and mismatched/inaccessible ownership NOT_FOUND. Local transport remains plaintext on the private development network and loopback ports; deployment requires TLS/HTTPS termination. See [Demand authentication and setup](demand.md).

## Inventory consistency

Quantity is an integer from 0 through 2147483647. Availability is always `AVAILABLE` for positive quantity and `UNAVAILABLE` for zero. Callers cannot supply availability. The database independently checks both nonnegative quantity and the quantity/availability relationship.

Each quantity update takes a tenant-scoped PostgreSQL row lock. The update and audit insertion share one transaction. A failed audit insertion rolls back inventory. Concurrent writes serialize and each event describes the preceding committed quantity. The response contains the inventory, `changed`, `restockTransition` and nullable `stockEventId`.

| Previous | New | Event | Availability | Restock |
| ---: | ---: | --- | --- | --- |
| 0 | 5 | Created | AVAILABLE | true |
| 5 | 10 | Created | AVAILABLE | false |
| 5 | 0 | Created | UNAVAILABLE | false |
| 0 | 0 | None; timestamp unchanged | UNAVAILABLE | false |
| any | same | None; timestamp unchanged | Derived from quantity | false |
| any | negative | Rejected; no mutation/event | Unchanged | false |

`StockEvent.isRestockTransition()` owns the zero-to-positive rule, and the application result exposes it. Stage 4 can build publication around this result/audit fact. Stage 1 adds neither a publisher nor an outbox and makes no event-delivery guarantee.

## REST contract

REST defaults to `http://localhost:8081`; override `CATALOG_PORT` for Compose or `PORT` for native execution. All resource IDs are UUIDs. Responses are DTOs, never JPA entities.

| Method | Path | Request / result |
| --- | --- | --- |
| POST | `/api/products` | Required name, brand, category; optional status defaults ACTIVE. 201 + Location + product |
| GET | `/api/products` | Optional `name` substring, exact `brand`, `status`, `page`, `size`; page DTO |
| GET | `/api/products/{id}` | `{product, variants}` |
| PATCH | `/api/products/{id}` | Optional name, brand, category, status; product DTO |
| POST | `/api/products/{id}/variants` | Required sku, attributes object; optional price/status; 201 + parent Location + variant DTO |
| PATCH | `/api/variants/{id}` | Optional sku, attributes, price, status; variant DTO |
| GET | `/api/variants/search` | Search result with `resolutionStatus`, candidates, totalMatches, page, size |
| GET | `/api/variants/{id}/inventory` | variantId, quantity, availabilityStatus, updatedAt |
| PATCH | `/api/variants/{id}/inventory` | Required `{"quantity":5}`; transactional update result |

Names are nonblank, at most 200 characters. Brand/category/SKU are nonblank, at most 100 characters. PATCH null/omitted fields mean unchanged; blank strings fail validation. Attributes supplied in PATCH replace the entire attributes map. An empty map clears attributes. A null price on creation means unspecified; PATCH null does not clear price. Explicit statuses must be ACTIVE or INACTIVE. Price supports at most 17 integer digits and 2 fractional digits; negative values or excess precision are rejected. Unknown JSON fields, malformed UUIDs/JSON, fractional quantities and integer overflow are rejected.

Search accepts `productName`, `brand`, `sku`, `status`, `storage`, `color`/`colour`, and `attribute.<key>` for other simple attributes. Filters combine with AND. Product-name matching is a literal case-insensitive substring (SQL wildcards in input are escaped). Brand and attribute values match by case-insensitive equality; SKU uses case-sensitive equality. There is no synonym/abbreviation guessing or fuzzy match. REST searches with no status include both statuses; gRPC always resolves active records. No REST search filter is required, allowing catalogue browsing.

Pagination defaults to page 0, size 20; size is 1–100 and page is 0–1000000. Ordering uses resource UUID. Resolution is based on total matches across all pages, including when the requested page is empty: 0 = NOT_FOUND, 1 = MATCHED, >1 = AMBIGUOUS. A page of one candidate may still be ambiguous.

Errors use `application/problem+json` with standard Problem Details fields plus `code`. Validation/malformed requests return 400 (`INVALID_REQUEST`); hidden/missing resources 404 (`NOT_FOUND`); duplicate tenant SKU 409 (`DUPLICATE_SKU`, including database-enforced races); other integrity conflicts 409 (`DATA_CONFLICT`); unsupported methods 405; unsupported media 415; unexpected failures 500 (`INTERNAL_ERROR`) with a sanitized message. Internal exceptions are logged server-side using existing ECS structured logging.

```sh
# Set TOKEN to the accessToken returned by Demand login; use the returned IDs below.
curl -i -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"Galaxy S25 Ultra","brand":"Samsung","category":"Phones"}' http://localhost:8081/api/products
curl -i -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"sku":"SAM-S25U-512-BLK","attributes":{"storage":"512GB","color":"Black"},"price":100.50}' http://localhost:8081/api/products/PRODUCT_ID/variants
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/variants/VARIANT_ID/inventory
curl -X PATCH -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"quantity":5}' http://localhost:8081/api/variants/VARIANT_ID/inventory
curl -H "Authorization: Bearer $TOKEN" 'http://localhost:8081/api/variants/search?brand=Samsung&storage=512GB&color=Black&status=ACTIVE'
```

## gRPC contract and validation

[Catalog v1 protobuf](../contracts/proto/stockping/catalog/v1/catalog.proto) defines `stockping.catalog.v1.CatalogService`. REST and gRPC have separate listeners. gRPC defaults to port 9091 (`CATALOG_GRPC_PORT`). Both generated Java and Go messages/stubs are build artifacts.

`ResolveProductVariant` takes merchant_id plus exact variant_id (new field 6), or at least one structured lookup field: product_name, brand, sku or attributes. Exact identity and search fields are mutually exclusive. It returns MATCHED, NOT_FOUND or AMBIGUOUS, up to 100 candidates in UUID order, and total_matches. No-match resolution is a successful response with NOT_FOUND; ambiguity is never converted into a selected variant. Candidate data includes variant/product IDs, SKU, product name, brand and attributes.

`GetInventory` takes merchant_id and exact variant_id, returning authoritative quantity, availability_status and RFC 3339 UTC updated_at. Missing/cross-merchant variants and mismatched merchant assertions return gRPC NOT_FOUND. Invalid UUIDs or lookup fields return INVALID_ARGUMENT. Unexpected exceptions return sanitized INTERNAL. Server message size is bounded to 64 KiB and shutdown has a bounded grace period.

The generated-code Java integration client and [Go validation client](../scripts/catalog-grpc-check.go) set deadlines. The Go client remains validation-only. Demand now uses both RPCs with one configured overall deadline; Messaging business RPCs remain deferred.

```sh
./scripts/generate-proto.sh
# Creates fresh demo data with REST, checks zero -> five, and runs both RPCs from Go.
python3 scripts/validate-catalog.py

# Set STOCKPING_TOKEN to a valid access token before using this validation client.
# Validate an existing exact SKU with the generated Go client on Docker Desktop.
docker run --rm -e STOCKPING_TOKEN -e GOCACHE=/tmp/go-build -e GOPATH=/tmp/go \
  -v "$PWD:/workspace:ro" -w /workspace/messaging-service stockping-proto-tools \
  go run /workspace/scripts/catalog-grpc-check.go \
  -target host.docker.internal:9091 \
  -merchant 11111111-1111-1111-1111-111111111111 \
  -sku SAM-S25U-512-BLK -quantity 5
```

On Linux, the automated script uses host networking and localhost; on Docker Desktop it uses `host.docker.internal`. `CATALOG_GRPC_VALIDATION_TARGET` overrides the validation address. The automated script leaves its uniquely named demo record for inspection and uses no manually seeded fixtures.

## Testing and build

```sh
# Requires Docker for the disposable PostgreSQL 18 test database.
./scripts/test.sh
# Or Java only, using the active Docker context's socket when required on macOS:
DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')" mvn -B -ntp test
```

The suite tests transitions, validation, event creation/no-op rules, deterministic resolution, all REST endpoints, tenant hiding for each read/write, generated-code TCP gRPC calls/statuses/deadlines, persistence, JSONB, tenant SKU uniqueness, composite foreign keys and inventory checks. It also exercises concurrent inventory updates and forces an audit insertion failure to prove rollback. PostgreSQL starts empty for each Maven test JVM; Flyway runs the real migration before schema validation. No H2, external developer data or skipped-on-no-Docker integration tests are used.

Catalog Docker image builds run unit and contract tests. PostgreSQL-backed tests carry the `postgres` tag and run in the full host/CI Maven suite, outside the image build; the Docker daemon is not mounted into a build container. Existing health test assertions are preserved and now run against the database-backed application. CI retains all Stage 0 checks and adds live Catalog REST/Go gRPC validation. [Stage 1 evidence](stage-1-validation.md) records executed results; [Stage 0 evidence](stage-0-validation.md) remains unchanged.
