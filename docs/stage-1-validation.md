# Stage 1 validation

Validated locally on 16 October 2026 (Asia/Colombo), on macOS ARM64 with Docker Desktop / Linux ARM64 containers. Implementation is limited to Stage 1. The branch remains `feat/stage-1-catalog`; no commit, merge, tag, push or pull request was created.

**Stage 1 complete.** All required automated, migration, Docker Compose, REST, tenant-isolation, gRPC and Stage 0 regression validations passed. Work stops at Stage 1.

## Implemented scope

Catalog implements Product, ProductVariant, Inventory and StockEvent, plus ACTIVE/INACTIVE catalogue status and quantity-derived availability. Products/variants support creation, reading/search, updates and deactivation. There are nine REST endpoints and two real unary gRPC RPCs. No frontend business screens, Demand/Messaging business workflows, authentication or RabbitMQ business events were added.

The schema is [V1__catalog_inventory.sql](../catalog-service/src/main/resources/db/migration/V1__catalog_inventory.sql): products, product_variants, inventory and stock_events. Flyway applies migrations; Hibernate validates. Composite tenant foreign keys enforce matching ownership, `(merchant_id, sku)` is unique, and inventory is unique per merchant/variant with nonnegative quantity and derived-availability checks. There are no cross-service database accesses or foreign keys.

REST uses the replaceable `MerchantContextProvider`, currently backed by server-side `CATALOG_DEMO_MERCHANT_ID`. Request fields cannot override merchant ownership. Repository queries/updates constrain merchant identity and inaccessible IDs are hidden with 404. gRPC merchant UUID assertions must equal the provider's identity, and resource ownership is checked separately; mismatches are hidden with NOT_FOUND. This is a private, unauthenticated, single-demo-merchant context, ready for replacement when Stage 2 introduces authenticated identity.

Inventory updates lock the merchant-owned row and persist quantity plus StockEvent in one transaction. Each actual change creates one audit event; equal quantities leave both audit and update timestamp unchanged. Only zero-to-positive transitions report restock. Initial inventory is zero with no audit event. Negative/fractional/overflow quantities are rejected. `StockEvent.isRestockTransition()` exposes the future Stage 4 publication decision without implementing publication.

## REST and protobuf inventory

| Method | REST endpoint |
| --- | --- |
| POST | `/api/products` |
| GET | `/api/products` |
| GET | `/api/products/{id}` |
| PATCH | `/api/products/{id}` |
| POST | `/api/products/{id}/variants` |
| PATCH | `/api/variants/{id}` |
| GET | `/api/variants/search` |
| GET | `/api/variants/{id}/inventory` |
| PATCH | `/api/variants/{id}/inventory` |

REST defaults to port 8081; gRPC defaults to 9091. [stockping/catalog/v1/catalog.proto](../contracts/proto/stockping/catalog/v1/catalog.proto) adds `stockping.catalog.v1.CatalogService.ResolveProductVariant` and `GetInventory`. Java and Go generators include it; the Stage 0 probe and wire tests remain. Search is deterministic with MATCHED/NOT_FOUND/AMBIGUOUS outcomes, literal product-name substring matching and explicit brand/SKU/attribute filters. It never silently selects an ambiguous candidate. gRPC resolution includes only active variants under active products.

The [Catalog guide](catalog.md) contains domain fields, DTO/error details, filtering, PATCH semantics, security limits, configuration and copyable REST/gRPC examples.

## Completed checks

| Check | Result |
| --- | --- |
| Full Maven reactor `mvn -B -ntp test` | Passed during implementation; final reactor also runs through the project script's `verify` |
| `./scripts/test.sh` / final `mvn -B -ntp verify` | Passed end to end |
| Java automated tests | 37 passed: 2 contracts, 33 Catalog, 2 Demand; zero failures/errors/skips |
| Frontend `npm ci`, `npm test`, `npm run build` | Passed: 1 test, strict TypeScript and Vite production build; npm reports zero vulnerabilities |
| `./scripts/generate-proto.sh` | Passed: both contracts generate Go messages/stubs; Java generation also passed through Maven |
| Go formatting, vet, race tests, builds | Passed: all 3 existing test packages, both generated packages, Messaging binary and standalone Catalog validation client compile |
| All 4 application image builds | Passed; Catalog image runs 13 unit tests plus 2 shared contract tests without requiring nested Docker |
| `docker compose config --quiet` | Passed |
| `docker compose down` | Passed; named volumes preserved |
| `docker compose up --build --wait --wait-timeout 180` | Passed: all 6 containers healthy; Catalog publishes REST 8081 and gRPC 9091 |
| `python3 scripts/smoke-test.py` | Passed: 4 HTTP boundaries, 3 owned database connections, 6 denied cross-service connections, authenticated RabbitMQ management API |
| `python3 scripts/validate-catalog.py` | Passed: live REST product/variant creation, inventory read/update/search and generated Go client resolution + authoritative stock retrieval |
| Tenant isolation | Passed via repository, HTTP and TCP gRPC tests; additional live Compose fixture denied six cross-merchant reads/writes with 404 and returned NOT_FOUND from search |
| Clean database migration | Passed in empty disposable PostgreSQL 18 and previously empty Compose catalog_db; Flyway V1 applied and Hibernate validation passed |
| Whitespace, shell/Python syntax, relative documentation links | Passed; final report rechecked before completion |

The final Java suite has 32 new test invocations in addition to all 5 Stage 0 tests. `CatalogDatabaseTest` has 4 tests, `CatalogIntegrationTest` 14, `InventoryTest` 6, `CatalogServiceTest` 6, `CatalogGrpcServiceTest` 1, `CatalogContractTest` 1; the existing probe and four health tests remain. Go has 4 top-level tests (plus 8 subtests) in 3 existing packages. With the frontend test, that is 42 test invocations counting Go at the top level; Go subtests are not added to that total.

The integration tests exercise all nine REST endpoints, validation, duplicate SKU including concurrent creation, missing and cross-tenant reads/writes, deterministic no/single/multiple matching, inventory no-ops and negative rejection, inventory/audit atomicity, concurrent quantity updates, generated Java gRPC resolution and inventory reads, invalid/hidden-resource status mapping, and an expired client deadline. An injected PostgreSQL trigger deliberately rejects an audit insert; the test verifies a sanitized 500 and unchanged inventory/audit state. Constraint violations and this injected error are expected test logs, not test failures.

`HealthEndpointTest` only gains the shared disposable PostgreSQL fixture; its two Stage 0 assertions are unchanged. PostgreSQL integration tests are tagged `postgres` and run in the full host/CI Maven suite. Catalog image builds run unit/contract tests with that tag excluded because a build container has no Docker daemon for nested Testcontainers. The host/CI gate never skips those integration tests when Docker is unavailable; it fails instead.

## Commands executed

From the repository root, successful final checks include the commands below (script internals are shown for reproducibility):

```sh
DOCKER_HOST="unix://$HOME/.docker/run/docker.sock" mvn -B -ntp test
./scripts/test.sh
# scripts/test.sh selects the active Docker context when DOCKER_HOST is absent:
# mvn -B -ntp verify
# (cd frontend && npm ci && npm test && npm run build)
# ./scripts/generate-proto.sh
# Docker pinned Go toolchain: gofmt check, go vet ./..., go test -race ./...
# go build -trimpath -o /tmp/messaging ./cmd/messaging
# go build -trimpath -o /tmp/catalog-grpc-check /workspace/scripts/catalog-grpc-check.go
./scripts/generate-proto.sh
docker compose config --quiet
docker compose down
docker compose up --build --wait --wait-timeout 180
python3 scripts/smoke-test.py
python3 scripts/validate-catalog.py
# Additional temporary live tenant fixture check (insert/check/remove):
python3 /tmp/stockping-tenant-check.py
docker compose ps
git diff --check
sh -n scripts/test.sh scripts/generate-proto.sh
python3 -m py_compile scripts/validate-catalog.py
```

Early focused runs used `mvn -B -ntp -pl catalog-service -am test -Dtest=InventoryTest -Dsurefire.failIfNoSpecifiedTests=false`. Initial integration runs exposed and resolved an older transitive protobuf runtime, default REST exception-handler precedence, and the Jackson 3 location of the numeric-enum rejection setting. The root explicitly manages protobuf-java at the generator's existing pinned version. All final evidence above is rerun after the relevant fixes.

## Live validation evidence

Stage 0 smoke output is unchanged:

```text
PASS frontend HTTP health
PASS catalog-service HTTP health
PASS demand-service HTTP health
PASS messaging-service HTTP health
PASS catalog database ownership/isolation
PASS demand database ownership/isolation
PASS messaging database ownership/isolation
PASS RabbitMQ authenticated management API
```

Live REST and tenant results:

```text
PASS Catalog REST: product=1e42fa16-2be6-4bfb-8478-71910aeb0b32 variant=6fee79eb-5f1f-4b93-bb44-ea75b60c5041 SKU=VALIDATE-332844ca2e0b 0->5 restock; no-op and negative rejection
PASS live REST tenant isolation: merchant=9affb055-a8b1-4452-8340-583076348bae; six cross-merchant reads/writes=404; search=NOT_FOUND; fixture removed
```

The tenant check temporarily inserted an unrelated merchant's product/variant/zero inventory using the catalog owner role, then exercised the running merchant REST service. Get/edit product, create variant under that product, edit variant, get inventory and update inventory all returned 404; SKU search returned zero matches. The fixture was removed in a finally block and a follow-up SQL count was zero. The committed HTTP/repository/gRPC tenant tests reproduce the ownership checks without manually seeded data.

Startup evidence from Compose:

```text
Schema history table "public"."flyway_schema_history" does not exist yet
Current version of schema "public": << Empty Schema >>
Migrating schema "public" to version "1 - catalog inventory"
Successfully applied 1 migration to schema "public", now at version v1
```

A subsequent catalog-owner SQL read returned migration `1|t` (version 1, success). All six Compose containers are left running and healthy. The original Stage 0 validation document remains unchanged.

Generated Go gRPC live result (both RPCs use generated stubs and 10-second call deadlines):

```text
PASS Catalog gRPC: SKU=VALIDATE-332844ca2e0b variant=6fee79eb-5f1f-4b93-bb44-ea75b60c5041 quantity=5 availability=AVAILABLE
```

The Go client resolved the exact SKU to the same variant created by REST and observed the database-backed quantity 5 / AVAILABLE state. Its process and `scripts/validate-catalog.py` both exited successfully. The script allows up to 600 seconds for a cold Go toolchain/dependency compile; this does not change the 10-second RPC deadlines.

## Decisions and limits

- Preserve the existing architecture, branch and Stage 0 tests. Conventional service/repository separation and one shared application service keep REST/gRPC logic consistent.
- Keep attributes as simple JSONB strings and price as optional exact decimal metadata. No generic PIM, financial operations or semantic/AI search exists.
- Use row locking for the inventory audit chain and database constraints for tenant relationships/SKU races. No distributed coordination is introduced.
- Keep server-configured tenant identity explicit and replaceable. The unauthenticated REST/plaintext gRPC transport is private development scope; Stage 2 must replace it with authenticated/trusted context before public or multi-merchant use.
- RabbitMQ remains healthy infrastructure only. No PRODUCT_RESTOCKED publication, producer, consumer, queue workflow or outbox was implemented.
- Stage 2 was not implemented: no login/authentication, Customer, ConsentRecord, WaitlistRequest, Demand business logic or production Demand-to-Catalog client. No later WhatsApp/AI/notification work was started.
- GitHub Actions keeps existing checks and adds live REST/Go gRPC validation. Its commands were exercised locally; remote CI cannot be claimed until the owner commits/pushes.
- Mockito reports the standard JDK 21 dynamic-agent warning in tests. Tests pass; a future JDK upgrade may require explicit Mockito agent configuration.
- There is no public stock-event mutation endpoint, no hard deletion, and no currency/conversion workflow. PATCH null means unchanged, including price. These small Stage 1 choices are documented.
- The live validation script retains its uniquely named demo product/variant for inspection. It is not a migration seed and automated integration tests use their own fixtures.

## Created files

Generated targets, node_modules, Go stubs and ignored local configuration are excluded.

```text
catalog-service/src/main/java/dev/stockping/catalog/config/ConfiguredMerchantContextProvider.java
catalog-service/src/main/java/dev/stockping/catalog/config/MerchantContextProvider.java
catalog-service/src/main/java/dev/stockping/catalog/controller/CatalogController.java
catalog-service/src/main/java/dev/stockping/catalog/domain/Availability.java
catalog-service/src/main/java/dev/stockping/catalog/domain/CatalogStatus.java
catalog-service/src/main/java/dev/stockping/catalog/domain/Inventory.java
catalog-service/src/main/java/dev/stockping/catalog/domain/Product.java
catalog-service/src/main/java/dev/stockping/catalog/domain/ProductVariant.java
catalog-service/src/main/java/dev/stockping/catalog/domain/StockEvent.java
catalog-service/src/main/java/dev/stockping/catalog/dto/CatalogDtos.java
catalog-service/src/main/java/dev/stockping/catalog/exception/DuplicateSkuException.java
catalog-service/src/main/java/dev/stockping/catalog/exception/ResourceNotFoundException.java
catalog-service/src/main/java/dev/stockping/catalog/exception/RestExceptionHandler.java
catalog-service/src/main/java/dev/stockping/catalog/grpc/CatalogGrpcServer.java
catalog-service/src/main/java/dev/stockping/catalog/grpc/CatalogGrpcService.java
catalog-service/src/main/java/dev/stockping/catalog/repository/InventoryRepository.java
catalog-service/src/main/java/dev/stockping/catalog/repository/ProductRepository.java
catalog-service/src/main/java/dev/stockping/catalog/repository/ProductVariantRepository.java
catalog-service/src/main/java/dev/stockping/catalog/repository/StockEventRepository.java
catalog-service/src/main/java/dev/stockping/catalog/service/CatalogService.java
catalog-service/src/main/java/dev/stockping/catalog/service/VariantCriteria.java
catalog-service/src/main/resources/db/migration/V1__catalog_inventory.sql
catalog-service/src/test/java/dev/stockping/catalog/CatalogDatabaseTest.java
catalog-service/src/test/java/dev/stockping/catalog/CatalogIntegrationTest.java
catalog-service/src/test/java/dev/stockping/catalog/PostgresTest.java
catalog-service/src/test/java/dev/stockping/catalog/domain/InventoryTest.java
catalog-service/src/test/java/dev/stockping/catalog/grpc/CatalogGrpcServiceTest.java
catalog-service/src/test/java/dev/stockping/catalog/service/CatalogServiceTest.java
contracts/java/src/test/java/dev/stockping/contracts/CatalogContractTest.java
contracts/proto/stockping/catalog/v1/catalog.proto
docs/catalog.md
docs/stage-1-validation.md
scripts/catalog-grpc-check.go
scripts/validate-catalog.py
```

## Modified files

```text
.env.example
.github/workflows/ci.yml
README.md
catalog-service/Dockerfile
catalog-service/pom.xml
catalog-service/src/main/resources/application.yml
catalog-service/src/test/java/dev/stockping/catalog/HealthEndpointTest.java
contracts/README.md
docker-compose.yml
docs/architecture.md
messaging-service/Dockerfile
pom.xml
scripts/generate-proto.sh
scripts/test.sh
```
