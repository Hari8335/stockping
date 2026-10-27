# Stage 3 implementation and validation report

Date: 27 October 2026 (Asia/Colombo). Branch: `feat/stage-3-whatsapp`. Work remains uncommitted.

**Status: implementation delivered; automated tests passed; live Compose validation in progress; real-provider acceptance pending.** The user confirmed that the official Meta number/tunnel is not configured and requested completion of local work. A real phone reply has not been tested. Stage 3 is not declared complete.

## Implementation inventory

| Required area | Result |
| --- | --- |
| Messaging database migration | Embedded numbered SQL migration 001, transactional advisory lock, checksum ledger; own messaging_db only |
| WebhookEvent | Provider identifier, merchant, type, receive/process times and explicit processing/failure states |
| MessageRecord | Inbound/outbound direction, purpose, phone, provider ID, correlation and timestamps; unique inbound ID and outbound intent |
| DeliveryEvent | Provider states/timestamps/error-code-only reference, duplicate protection, unknown-ID reconciliation and chronological state |
| Provider configuration | All requested values external; blank placeholders, explicit enablement/version and controlled fake endpoint |
| GET verification | Exact challenge, constant-time token match, invalid/missing/malformed parameter rejection |
| POST authentication | Raw-body HMAC-SHA256, constant-time comparison, invalid signatures rejected before side effects |
| Merchant mapping | Trusted WABA + phone-number mapping to canonical merchant UUID through replaceable ChannelResolver |
| Idempotency | PostgreSQL claim/MessageRecord transaction and authoritative unique constraint; one Demand call and outbound intent under concurrent delivery |
| Normalization | Provider-independent phone/name/ID/text/timestamp/correlation; unsupported media recorded without processing |
| Demand contract | Versioned demand/v1/demand.proto, generated Go/Java unary ProcessCustomerMessage |
| Reply instruction | SEND_TEXT/NO_REPLY, reply text, purpose and correlation; no provider HTTP details |
| Internal authentication | Dedicated Messaging/Demand service token, separate browser JWT; independent Catalog read credential |
| Demand gRPC server | Private configurable 9092, thin adapter, strict validation, safe status mapping and bounded client calls |
| Merchant context | Accepted only from authenticated service; merchant existence/status checked; no text-based tenant selection |
| Deterministic commands | HELP, STOP, STOP NOTIFICATIONS, STOCK exact-SKU and fallback |
| Exact SKU | Catalog gRPC resolution plus inventory validation; safe fallback on outage, no inferred stock or waitlist creation |
| STOP | Reuses existing consent/locking/waitlist suppression transaction; CUSTOMER_MESSAGE provenance; unknown customers are not created |
| Outbound adapter | Configured Graph path, Bearer header, JSON text, bounded HTTP, safe errors and returned provider ID persistence |
| Retry policy | No automatic retry of immediate sends or failed/uncertain claims; documented at-most-once/crash-recovery limitation |
| Correlation | Existing X-Request-ID convention across records, HTTP, both gRPC hops, Demand logs and outbound callback data |
| Status callbacks | Messaging tracking only; no Demand notification processing or broker publishing |
| Privacy | No raw webhook/text/token logs or storage; safe error categories; real evidence must mask identifiers |
| Documentation | Messaging guide, architecture update, README setup and this acceptance report |
| Scope | No RabbitMQ business events, notification workers, AI/LLM, Stage 4 or later-stage features |

## Automated evidence

| Check | Observed result |
| --- | --- |
| Java compile/package | Passed before tests |
| Java full regression | **140 tests, 0 failures/errors/skips** in Maven verify and the final explicit Maven test run |
| Java inventory | Contracts 2; shared security 12; Catalog 41; Demand 85 |
| New Java coverage | Demand RPC validation/auth/mapping 11; commands 12; real PostgreSQL consent/RPC 3; Catalog message client 6; Catalog service authentication 4 |
| Frontend | **12 tests passed**, production TypeScript/Vite build passed |
| Go unit/race/build | Complete retry passed formatting, vet, race tests and all three Go builds; 19 top-level Go tests total (15 unit/contract and 4 PostgreSQL/HTTP integration), with table-driven subtests |
| Go database/HTTP integration | Final standalone disposable PostgreSQL race run passed: three database tests plus seven end-to-end handler scenarios (success, timeout, unavailable, no reply, provider failure, unsupported, expired) |
| Concurrent duplicate proof | Twenty parallel database claims produce one claimant; ten signed handler deliveries produce one Demand call/one provider send |
| Delivery tests | Read-before-send reconciliation, duplicate, out-of-order sent/delivered/failed, unknown ID and persisted timestamps passed |
| Protobuf | Go generation passed; Java contract/stub generation passed during Maven |
| Full ./scripts/test.sh | Passed on 27 October after disk/Docker recovery: Maven verify, frontend tests/build, generation, Go checks/builds and PostgreSQL integration |
| Compose config | Passed |
| Compose | Final builds and all six health checks passed after recovery; final clean restart and live acceptance are running |
| Stage 0/1/2 live scripts | Passed: four HTTP checks, three database owners and six denied cross-database connections, broker health; Catalog REST/generated Go RPC; auth/tenant/consent/waitlist/browser-proxy regressions and Catalog outage/recovery |
| Stage 3 live validator | Implemented; execution pending healthy rebuilt Compose |
| Real Go → Java TCP acceptance | Passed generated Go → production Java TCP authentication, serialization, correlation, INVALID_ARGUMENT and real deadline while a bounded test-only database lock held the HELP read |
| Source checks | Python validator compilation and git diff --check passed |

The existing migration regression explicitly verifies both V1 and V2 and the total of two successful migrations; no existing business assertion was removed. Successful Catalog test calls use the production 2-second deadline; a separate nonresponding-server test uses 100 ms to verify expiration. Delayed commands still reach Demand, while replies outside the 24-hour window are suppressed. Testcontainers and Go tests use real PostgreSQL, never H2. Provider tests use controlled HTTP servers; CI requires no live Meta credentials.

## Validation refinements

Live acceptance exposed two timing assumptions and they were corrected. Successful Catalog unit calls now use the production deadline while the dedicated timeout test retains a short deadline. The Go → Java live deadline test holds a bounded three-second read-blocking database lock; stopping Catalog can legitimately fail immediately and is tested separately. Messaging's default Demand timeout is 3 seconds, leaving room for Demand's existing 2-second Catalog deadline and safe fallback, within the shared 8-second webhook bound. Delayed STOP processing remains active while expired-window replies are suppressed. The disposable PostgreSQL test runner removes only its own anonymous volume.

## Environment interruption

The host filesystem exhausted its free space during simultaneous build validation. Docker reported `read-only file system` for its container metadata and Go temporary output. Generated StockPing Java archives were removed to free approximately 155 MiB while preserving test reports and source. On 27 October, the retry found 8.8 GiB free and Docker operational. The full repository test script then passed. Final Compose rebuild and Stage 0–2 live checks passed; Stage 3 results are recorded below. Database volumes were not removed.

## Real provider acceptance

| Required evidence | Result |
| --- | --- |
| Real Graph API version | Not configured or used; automated fixture uses v26.0 only |
| Real webhook verification | Pending official Meta setup and HTTPS callback |
| Real inbound WhatsApp/provider ID | Pending |
| Real inbound → Go → Demand | Pending real-provider setup |
| Real outbound Cloud API/provider ID | Pending |
| Actual phone reply received | Pending |
| Real delivery callback | Pending |

The [Messaging guide](messaging.md) contains official references, configuration, signature rules, test procedure, timeout/retry decisions and limitations. Direct Meta documentation pages were rate-limited; official Meta Postman references were accessible. Current live version/account policy must be rechecked during real acceptance.

## Commands executed

```sh
./scripts/generate-proto.sh
mvn -B -ntp -DskipTests package
DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}') mvn -B -ntp test
./scripts/test-messaging-db.sh
PATH="/tmp/stockping-node24/node-v24.22.1-darwin-arm64/bin:$PATH" NPM_CONFIG_CACHE=/tmp/stockping-npm-cache ./scripts/test.sh
python3 scripts/init-local-env.py
docker compose config --quiet
docker compose down
docker compose up --build --wait --wait-timeout 180
python3 -m py_compile scripts/validate-messaging.py scripts/fake-whatsapp.py
git diff --check
git status --short
```

Additional direct Go toolchain containers ran `go mod tidy`, `gofmt`, `go vet`, and `go test -race -v ./...`. Validation output is in temporary `/tmp/stockping-stage3-*.log` files and Maven reports. No live credentials were used or logged. The Git branch was preserved; no commit, push, merge, tag or PR was created.

## Files created and modified

Created:

- `catalog-service/src/test/java/dev/stockping/catalog/grpc/CatalogServiceAuthenticationTest.java`
- `contracts/proto/stockping/demand/v1/demand.proto`
- `demand-service/src/main/java/dev/stockping/demand/grpc/DemandAuthenticationInterceptor.java`
- `demand-service/src/main/java/dev/stockping/demand/grpc/DemandGrpcServer.java`
- `demand-service/src/main/java/dev/stockping/demand/grpc/DemandGrpcService.java`
- `demand-service/src/main/java/dev/stockping/demand/grpc/MessageCatalogClient.java`
- `demand-service/src/main/java/dev/stockping/demand/messaging/CustomerMessageService.java`
- `demand-service/src/main/resources/db/migration/V2__customer_message_consent.sql`
- `demand-service/src/test/java/dev/stockping/demand/CustomerMessageIntegrationTest.java`
- `demand-service/src/test/java/dev/stockping/demand/CustomerMessageServiceTest.java`
- `demand-service/src/test/java/dev/stockping/demand/grpc/DemandGrpcTest.java`
- `demand-service/src/test/java/dev/stockping/demand/grpc/MessageCatalogClientTest.java`
- `docs/messaging.md`
- `docs/stage-3-validation.md`
- `messaging-service/internal/grpc/demand.go`
- `messaging-service/internal/http/webhook.go`
- `messaging-service/internal/http/webhook_integration_test.go`
- `messaging-service/internal/http/webhook_test.go`
- `messaging-service/internal/model/message.go`
- `messaging-service/internal/processing/processor.go`
- `messaging-service/internal/store/migrations/001_messaging.sql`
- `messaging-service/internal/store/postgres.go`
- `messaging-service/internal/store/postgres_test.go`
- `messaging-service/internal/testsupport/database.go`
- `messaging-service/internal/whatsapp/client.go`
- `messaging-service/internal/whatsapp/client_test.go`
- `messaging-service/internal/whatsapp/webhook.go`
- `messaging-service/internal/whatsapp/webhook_test.go`
- `scripts/demand-grpc-check.go`
- `scripts/fake-whatsapp.py`
- `scripts/test-messaging-db.sh`
- `scripts/validate-messaging.py`

Modified:

- `.env.example`
- `.github/workflows/ci.yml`
- `README.md`
- `catalog-service/src/main/java/dev/stockping/catalog/grpc/CatalogAuthenticationInterceptor.java`
- `catalog-service/src/main/resources/application.yml`
- `contracts/proto/stockping/catalog/v1/catalog.proto`
- `demand-service/src/main/java/dev/stockping/demand/domain/ConsentRecord.java`
- `demand-service/src/main/java/dev/stockping/demand/service/ConsentService.java`
- `demand-service/src/main/resources/application.yml`
- `demand-service/src/test/java/dev/stockping/demand/DemandIntegrationTest.java`
- `docker-compose.yml`
- `docs/architecture.md`
- `messaging-service/Dockerfile`
- `messaging-service/cmd/messaging/main.go`
- `messaging-service/go.mod`
- `messaging-service/go.sum`
- `messaging-service/internal/config/config.go`
- `messaging-service/internal/config/config_test.go`
- `scripts/generate-proto.sh`
- `scripts/init-local-env.py`
- `scripts/test.sh`
