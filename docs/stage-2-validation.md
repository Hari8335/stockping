# Stage 2 implementation and validation report

Validation date: 26 October 2026 (Asia/Colombo). Branch: `feat/stage-2-demand`. Baseline: `4e7791f` (completed Stage 1). Work is uncommitted for review.

Status: **Stage 2 complete.** Implementation, automated tests, live Compose acceptance and the browser walkthrough passed. Stage 3 has not started.

## Delivered scope

Stage 2 adds Merchant/User authentication, customer persistence, append-only consent, exact-variant waitlists, duplicate protection, opt-out, merchant-scoped queries/counts, Demand → Catalog gRPC, and minimal MUI login/waitlist screens. The [Demand guide](demand.md) contains complete models, endpoints, configuration, business rules and architectural decisions.

| Area | Implementation |
| --- | --- |
| Migration | `demand-service/src/main/resources/db/migration/V1__demand.sql`; six tables, internal tenant-aware FKs, checks and indexes |
| Merchant | Tenant root; name, IANA timezone, status and timestamps |
| User | One merchant; normalized globally unique email, BCrypt cost 12 hash, MERCHANT_ADMIN and status |
| Customer | Phone unique per merchant; explicit international normalization, optional display name/language, status |
| ConsentRecord | RESTOCK_NOTIFICATIONS, OPT_IN/OPT_OUT, MANUAL_API source, capture time and ordered sequence; latest history determines effective consent |
| WaitlistRequest | External Catalog variant UUID; customer/merchant ownership; all SRS statuses stored, Stage 2 creates WAITING and suppresses active rows to OPTED_OUT |
| DemandAudit | Consent and creation actions; source/variant metadata, no phone/password/token |
| Authentication | Demand issues HS256 JWTs; shared validation checks signature, issuer, audience, identity, timestamps and role |
| Expiry/logout | Default 30 minutes; client drops memory-only token, 204 acknowledgement; issued tokens are valid until expiry, no refresh/revocation |
| Merchant context | Signed identity supplies REST ownership; Catalog interceptor validates delegated token, compares RPC merchant assertion and scopes all queries |
| Catalog change | Removes configured context; protects existing APIs; adds exact variant field 6 to existing RPC without renumbering fields |
| Creation | `POST /api/waitlists` is the Stage 2 manual/API entry point supplementing the SRS REST read table |
| Consent rules | Initial explicit consent required; opt-out blocks creation even with consent:true; explicit `/opt-in` required for renewed consent |
| Opt-out | One transaction appends history and updates all active rows; same customer lock as creation |
| Duplicate protection | Application check plus partial unique index for WAITING and NOTIFICATION_QUEUED; phone upsert + row lock handles concurrent customer creation |
| Queries | List/detail, status/variant/customer/date filters, pagination; simple grouped total/per-variant counts |
| gRPC | Generated Java blocking stub; ResolveProductVariant then GetInventory; exact identity and stock consistency checked; failures never guess stock |
| Timeout | Configured 2-second overall deadline across both calls; 50–10000ms bounds; no automatic retries |
| Frontend | Login, in-memory session, logout, masked waitlist rows, status/variant filters, pagination, count, confirmed opt-out, loading/error/empty states |
| CI/Compose | Six original services; auth/database/gRPC/proxy/demo configuration added; full test script and live validation retained/extended |

REST endpoints: `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/waitlists`, `GET /api/waitlists`, `GET /api/waitlists/{id}`, `POST /api/customers/{id}/opt-out`, `POST /api/customers/{id}/opt-in`, `GET /api/analytics/demand`. Existing Catalog REST paths remain, now authenticated. Public health remains available; configuration endpoints remain unexposed even to an authenticated administrator.

## Automated test inventory

| Suite | Tests | Coverage |
| --- | ---: | --- |
| Shared protobuf contracts | 2 | Preserved Java wire/descriptor checks |
| Shared token policy | 12 | Valid identity; invalid signature/issuer/audience/UUID/role/iat/exp; weak configuration; missing identity |
| Catalog | 37 | All Stage 1 repository/inventory/concurrency/REST/gRPC checks plus authentication, second signed tenant and exact resolution |
| Demand phone normalization | 13 | Equivalent formats, idempotence, malformed/local/oversized values |
| Demand real TCP gRPC client | 16 | Resolution, stock, tenant hiding, ambiguity, corrupt replies, failure mappings, stopped server and actual deadline |
| Demand PostgreSQL/REST | 22 | Auth, hashing, isolation, persistence, consent/history, availability, filters/counts, concurrent duplicates/opt-out, DB constraints, rollback and stock spoofing |
| Demand health/configuration | 2 | Public health and protected/unexposed configuration |
| **Java total** | **104** | No failures, errors or skipped tests in final host Maven run |
| Frontend | 12 | Login validation/pending/success/failure, protected screen, rows/masking, loading/error/empty, filters, logout, expired authorization and confirmed opt-out |

Integration tests use real disposable PostgreSQL 18 initialized empty by Testcontainers; no H2 and no silent no-Docker skips. The Demand client suite uses real TCP/generated stubs and a controlled Catalog server; Catalog's own suite uses its production server against PostgreSQL. Live Compose acceptance additionally exercises both production services together. The older Stage 0/1 test assertions remain, with authenticated callers added where Stage 2 requires them; the placeholder UI assertion was replaced with the implemented login UI assertion.

## Validation evidence

| Check | Executed result |
| --- | --- |
| Java | Final Maven run: **104 tests**, zero failures/errors/skips; Java 21 |
| Repository test script | Passed Maven verify, frontend tests/build, protobuf generation, Go formatting/vet/race tests and builds. This run contained 102 Java tests; after adding two rollback tests and tightening health-only public access, the final Maven run passed all 104 |
| Frontend | **12 tests passed**; TypeScript and production Vite build passed using Node 24.22.1; npm audit: zero vulnerabilities |
| Protobuf | Java messages/stubs generated by Maven; Go messages/stubs generated by `scripts/generate-proto.sh` within the full runner; existing wire/descriptor checks passed |
| Go | Existing config, HTTP and contract suites passed with race detector; formatting, vet, Messaging build and generated Catalog client build passed |
| Compose | Config validation and `down` followed by `up --build --wait --wait-timeout 180` passed; all six containers healthy; volumes retained |
| Stage 0 smoke | Four HTTP checks, all three database ownership checks and six denied cross-service connections, plus authenticated RabbitMQ management passed |
| Stage 1 live | Authenticated Catalog CRUD/inventory validation passed, including 0→5 restock, no-op and negative rejection; generated Go client resolved the same variant and returned quantity 5/AVAILABLE |
| Authentication | Live valid login, bad-password rejection, anonymous rejection, authenticated same-origin proxy and stateless logout semantics passed; automated tests additionally cover hashing, invalid/expired tokens, unknown/inactive users and role enforcement |
| Tenant/customer | Live two-tenant checks passed: independent customers for the same normalized phone; cross-tenant variant/detail/consent rejection; payload ownership override rejected; query ownership override ineffective. PostgreSQL tests cover equivalent phone formats and concurrent uniqueness |
| Consent/waitlists | Live creation produced WAITING with effective consent; duplicate returned 409; count incremented; available stock rejected; opt-out appended history, suppressed eligibility and decremented active count; renewed consent allowed a new row without reactivating old history |
| Catalog RPCs | Production Demand called production Catalog for exact resolution and authoritative inventory, using signed tenant identity; both RPCs are covered by real TCP integration tests |
| Catalog outage | Stopped the real Catalog container; Demand returned bounded 504 CATALOG_TIMEOUT, created no customer or waitlist, and successfully created WAITING after Catalog restarted. The validator also accepts the independently tested 503 CATALOG_UNAVAILABLE disconnect outcome |
| Empty database migration | Testcontainers created an empty PostgreSQL 18 database; Flyway applied V1 successfully and Hibernate validated it. Live demand_db independently reports version 1 / demand / success=true and all six business tables |
| Browser walkthrough | Safari at 127.0.0.1:5173: invalid password displayed the safe error; valid login showed an empty waitlist; refresh displayed loading and then the API-created WAITING row with masked phone, consent and active count 1; confirmed opt-out changed the row to OPTED OUT, consent to opted out and count to 0; logout returned to Sign in with empty fields and the signed-out message. Browser password saving was declined; the temporary synthetic account/merchant was disabled afterward |
| Git/secrets | Branch and HEAD preserved; diff check passed; nothing staged; generated auth values absent from reviewable files; local .env ignored with mode 600; Stage 0/1 validation files unchanged |

Exact principal validation commands executed from the repository root (temporary log redirections omitted):

```sh
python3 scripts/init-local-env.py
mvn -B -ntp -DskipTests package
DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}') mvn -B -ntp clean test
PATH="/tmp/stockping-node24/node-v24.22.1-darwin-arm64/bin:$PATH" NPM_CONFIG_CACHE=/tmp/stockping-npm-cache ./scripts/test.sh
DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}') mvn -B -ntp test
docker compose config --quiet
docker compose down
docker compose up --build --wait --wait-timeout 180
python3 scripts/smoke-test.py
python3 scripts/validate-catalog.py
python3 scripts/validate-demand.py
docker compose ps --format '{{.Service}} {{.State}} {{.Health}}'
git diff --check
git status --short --branch
git diff --cached --name-only
git diff -- docs/stage-0-validation.md docs/stage-1-validation.md
git check-ignore .env
```

The full runner executes `mvn -B -ntp verify`, `npm ci`, `npm test`, `npm run build`, `./scripts/generate-proto.sh`, `gofmt` verification, `go vet ./...`, `go test -race ./...`, and both Go builds. The dependency fix used `npm update undici --ignore-scripts` in frontend; the subsequent install/audit reported zero vulnerabilities.

Local evidence logs: `/tmp/stockping-java-final.log`, `/tmp/stockping-all-tests.log`, `/tmp/stockping-compose-final.log`, `/tmp/stockping-smoke.log`, `/tmp/stockping-catalog-live.log`, `/tmp/stockping-demand-live.log`. They are temporary validation artifacts, not committed deliverables. Live scripts leave synthetic records for inspection. The final Demand run created variant `5a696455-4ccd-4628-acf1-8b3ddc4fc2c3` and renewed waitlist `da263977-2194-4d88-939e-1bba57b260d8`.

The browser walkthrough used a separate synthetic tenant and fake test credentials, with no private demo password printed or saved. Temporary setup commands were `python3 /tmp/stockping-ui-validation.py setup`, `python3 /tmp/stockping-ui-validation.py seed`, and `python3 /tmp/stockping-ui-validation.py disable`. The seed used authenticated REST and production Demand → Catalog gRPC; browser UI controls performed login, refresh, opt-out and logout. Variant `db738ca5-995c-40bb-9184-f495877d1912` was visibly associated with the masked customer row. Automated frontend tests separately verify the waitlist-request error state and session expiry.

Validation issues resolved before final results: stale compiled output after replacing the development context was cleared by a clean build; two test assertions and MUI system-prop typing were corrected. Docker's Maven Central TLS download stalled, so the local BuildKit Maven repository cache was warmed from already validated host artifacts using `docker build --network=none --build-context artifacts=/Users/harikeshansundaramoorthy/.m2/repository -t stockping-maven-cache-seed /tmp/stockping-maven-cache-seed`; no Maven credential/settings files were copied and the subsequent normal Compose build passed. Node 24.22.1 was downloaded from the official distribution and SHA-256 verified in `/tmp`, leaving the host installation unchanged. The initial live outage assertion required 503 only; it was corrected to accept the documented disconnect/deadline outcomes while retaining elapsed-time, rollback and recovery assertions, and the full live acceptance script then passed.

## Decisions and intentional limits

- The small shared-security Java module centralizes validation only. Demand owns credential storage and token issuance; neither service imports the other's repositories/entities.
- Manual API creation and a separate re-consent endpoint make Stage 2 usable before WhatsApp without introducing generic Customer CRUD. Services hold business rules; controllers return DTOs.
- Active includes WAITING and the reserved NOTIFICATION_QUEUED state. Stage 2 never queues/sends notifications. OPTED_OUT records remain historical after re-consent.
- A local demo bootstrap is explicitly profile-gated, uses external generated credentials and never overwrites existing users. The existing demo merchant UUID preserves Stage 1 catalogue ownership.
- Local Compose retains Vite's development server and plaintext internal gRPC on a private network. HTTPS/gRPC TLS termination and deployment are future-stage work; the client can connect to a TLS endpoint. The shared HS256 key is an MVP trust tradeoff.
- Stateless logout/deactivation cannot revoke copied tokens. Refresh, revocation, public signup/password reset, login rate limiting and production provisioning are outside this slice.
- Stock verification is a Catalog snapshot, not a distributed reservation. Inventory can change after verification; event-driven reconciliation is deferred.
- Lists cap pages at 100 records and analytics uses simple grouped counts. This is a small demo, not a large-scale pagination/analytics design.
- Maven emits the existing Mockito/Byte Buddy dynamic-agent/CDS notices under Java 21. The frontend install reports its optional fsevents install-script policy notice. These do not fail checks.
- The test-only transitive dependency undici was patched from 7.30.0 to 7.30.1; npm reports zero vulnerabilities. The waitlist screen is lazy-loaded and the frontend build has no chunk-size warning after splitting.

## Scope and Git confirmation

Demand connects only to demand_db and verifies Catalog identity/stock through gRPC. Compose still denies every cross-service database connection. No cross-service FK, Catalog entity copy or direct Catalog query exists in Demand. The live validator's SQL is limited to Demand fixture setup and persisted-state assertions.

No WhatsApp webhook/Cloud API, Messaging → Demand business gRPC, ProcessCustomerMessage, AI, RabbitMQ business event, outbound notification, delivery update, WebSocket, advanced analytics or Stage 3 implementation was added. RabbitMQ remains healthy infrastructure only.

No commit, push, merge, history rewrite, milestone tag or PR was created. Signing material and demo passwords stay in ignored mode-600 .env; no secrets were committed. Generated protobuf/build output remains ignored. Stage 0/1 validation documents are unchanged.

## Changed file inventory

53 files created, 31 modified, 1 replaced/deleted. Paths are relative to the repository root.

### Created

```text
catalog-service/src/main/java/dev/stockping/catalog/config/AuthenticatedMerchantContextProvider.java
catalog-service/src/main/java/dev/stockping/catalog/grpc/CatalogAuthenticationInterceptor.java
catalog-service/src/test/java/dev/stockping/catalog/TestTokens.java
demand-service/src/main/java/dev/stockping/demand/config/DemoBootstrap.java
demand-service/src/main/java/dev/stockping/demand/config/PasswordConfiguration.java
demand-service/src/main/java/dev/stockping/demand/controller/AuthController.java
demand-service/src/main/java/dev/stockping/demand/controller/ConsentController.java
demand-service/src/main/java/dev/stockping/demand/controller/WaitlistController.java
demand-service/src/main/java/dev/stockping/demand/domain/AccountStatus.java
demand-service/src/main/java/dev/stockping/demand/domain/ConsentRecord.java
demand-service/src/main/java/dev/stockping/demand/domain/ConsentStatus.java
demand-service/src/main/java/dev/stockping/demand/domain/Customer.java
demand-service/src/main/java/dev/stockping/demand/domain/DemandAudit.java
demand-service/src/main/java/dev/stockping/demand/domain/Merchant.java
demand-service/src/main/java/dev/stockping/demand/domain/User.java
demand-service/src/main/java/dev/stockping/demand/domain/WaitlistRequest.java
demand-service/src/main/java/dev/stockping/demand/domain/WaitlistStatus.java
demand-service/src/main/java/dev/stockping/demand/dto/DemandDtos.java
demand-service/src/main/java/dev/stockping/demand/exception/DemandException.java
demand-service/src/main/java/dev/stockping/demand/exception/RestExceptionHandler.java
demand-service/src/main/java/dev/stockping/demand/grpc/CatalogClient.java
demand-service/src/main/java/dev/stockping/demand/grpc/CatalogClientConfiguration.java
demand-service/src/main/java/dev/stockping/demand/repository/ConsentRecordRepository.java
demand-service/src/main/java/dev/stockping/demand/repository/CustomerRepository.java
demand-service/src/main/java/dev/stockping/demand/repository/DemandAuditRepository.java
demand-service/src/main/java/dev/stockping/demand/repository/MerchantRepository.java
demand-service/src/main/java/dev/stockping/demand/repository/UserRepository.java
demand-service/src/main/java/dev/stockping/demand/repository/WaitlistRepository.java
demand-service/src/main/java/dev/stockping/demand/service/AuthService.java
demand-service/src/main/java/dev/stockping/demand/service/ConsentService.java
demand-service/src/main/java/dev/stockping/demand/service/CustomerService.java
demand-service/src/main/java/dev/stockping/demand/service/PhoneNormalizer.java
demand-service/src/main/java/dev/stockping/demand/service/WaitlistService.java
demand-service/src/main/resources/db/migration/V1__demand.sql
demand-service/src/test/java/dev/stockping/demand/CatalogClientTest.java
demand-service/src/test/java/dev/stockping/demand/DemandIntegrationTest.java
demand-service/src/test/java/dev/stockping/demand/PhoneNormalizerTest.java
demand-service/src/test/java/dev/stockping/demand/PostgresTest.java
demand-service/src/test/java/dev/stockping/demand/TestCatalog.java
demand-service/src/test/java/dev/stockping/demand/TestTokens.java
docs/demand.md
docs/stage-2-validation.md
frontend/src/WaitlistScreen.tsx
frontend/src/api.ts
scripts/init-local-env.py
scripts/validate-demand.py
scripts/validation_support.py
shared-security/pom.xml
shared-security/src/main/java/dev/stockping/security/ApiSecurity.java
shared-security/src/main/java/dev/stockping/security/Identity.java
shared-security/src/main/java/dev/stockping/security/RequestCorrelationFilter.java
shared-security/src/main/java/dev/stockping/security/TokenConfiguration.java
shared-security/src/test/java/dev/stockping/security/TokenConfigurationTest.java
```

### Modified

```text
.env.example
.github/workflows/ci.yml
README.md
catalog-service/Dockerfile
catalog-service/pom.xml
catalog-service/src/main/java/dev/stockping/catalog/CatalogApplication.java
catalog-service/src/main/java/dev/stockping/catalog/grpc/CatalogGrpcServer.java
catalog-service/src/main/java/dev/stockping/catalog/grpc/CatalogGrpcService.java
catalog-service/src/main/java/dev/stockping/catalog/service/CatalogService.java
catalog-service/src/main/resources/application.yml
catalog-service/src/test/java/dev/stockping/catalog/CatalogIntegrationTest.java
catalog-service/src/test/java/dev/stockping/catalog/HealthEndpointTest.java
catalog-service/src/test/java/dev/stockping/catalog/PostgresTest.java
contracts/README.md
contracts/proto/stockping/catalog/v1/catalog.proto
demand-service/Dockerfile
demand-service/pom.xml
demand-service/src/main/java/dev/stockping/demand/DemandApplication.java
demand-service/src/main/resources/application.yml
demand-service/src/test/java/dev/stockping/demand/HealthEndpointTest.java
docker-compose.yml
docs/architecture.md
docs/catalog.md
frontend/package-lock.json
frontend/src/App.test.tsx
frontend/src/App.tsx
frontend/vite.config.ts
pom.xml
scripts/catalog-grpc-check.go
scripts/smoke-test.py
scripts/validate-catalog.py
```

### Replaced/deleted

```text
catalog-service/src/main/java/dev/stockping/catalog/config/ConfiguredMerchantContextProvider.java
```

The deleted configured merchant provider is replaced by the authenticated implementation listed above. No Stage 0/1 domain or validation document was deleted.
