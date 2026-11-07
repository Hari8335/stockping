# Stage 3 Implementation and Validation Report

**Project:** StockPing
**Implementation date:** 27 October 2026 (Asia/Colombo)
**Final acceptance date:** 8 November 2026 (Asia/Colombo)
**Branch:** `feat/stage-3-whatsapp`

**Status: COMPLETE — Stage 3 automated validation, local integration tests, and real WhatsApp HELP acceptance passed.**

A real WhatsApp `HELP` message was successfully received and processed by StockPing, and an automatic reply was delivered to the testing phone. PostgreSQL confirmed the inbound message as `PROCESSED` and the outbound message as `READ`.

The `STOCK` and `STOP` commands passed local integration tests. Separate real-provider acceptance was not performed for those commands.

The Stage 3 finalization changes have been committed locally on the feature branch. Remote push and merge remain to be verified.

---

## 1. Implementation Inventory

| Required area | Implementation result |
| --- | --- |
| Messaging database migration | Embedded numbered SQL migration `001`, transactional advisory lock, checksum ledger; service-owned `messaging_db` |
| WebhookEvent | Provider identifier, merchant, event type, receive/process timestamps, and explicit processing/failure states |
| MessageRecord | Inbound/outbound direction, purpose, phone, provider ID, correlation and timestamps; unique inbound ID and outbound intent |
| DeliveryEvent | Provider states, timestamps, error-code-only reference, duplicate protection, unknown-ID reconciliation and chronological state |
| Provider configuration | External configuration, blank placeholders, explicit enablement/version, and controlled fake endpoint for testing |
| GET webhook verification | Exact challenge, constant-time token comparison, and rejection of invalid/missing/malformed parameters |
| POST authentication | Raw-body HMAC-SHA256, constant-time comparison, invalid signatures rejected before side effects |
| Merchant mapping | Trusted WABA and phone-number mapping to canonical merchant UUID through replaceable `ChannelResolver` |
| Idempotency | PostgreSQL claim/MessageRecord transaction and unique constraints; one Demand call and outbound intent under concurrent delivery |
| Normalization | Provider-independent phone, name, ID, text, timestamp and correlation; unsupported media recorded without processing |
| Demand contract | Versioned `demand/v1/demand.proto`, generated Go/Java unary `ProcessCustomerMessage` |
| Reply instruction | `SEND_TEXT` / `NO_REPLY`, reply text, purpose and correlation; no provider HTTP details |
| Internal authentication | Dedicated Messaging/Demand service token, separate browser JWT, independent Catalog read credential |
| Demand gRPC server | Private configurable port `9092`, thin adapter, strict validation, safe status mapping and bounded client calls |
| Merchant context | Accepted only from authenticated service; merchant existence/status checked; no text-based tenant selection |
| Deterministic commands | `HELP`, `STOP`, `STOP NOTIFICATIONS`, `STOCK <SKU>` and fallback |
| Exact SKU | Catalog gRPC resolution plus inventory validation; safe fallback on outage, no inferred stock or waitlist creation |
| STOP | Reuses existing consent, locking and waitlist suppression transaction; `CUSTOMER_MESSAGE` provenance; unknown customers are not created |
| Outbound adapter | Configured Graph API path, Bearer header, JSON text, bounded HTTP, safe errors and provider ID persistence |
| Retry policy | No automatic retry of immediate sends or failed/uncertain claims; documented at-most-once/crash-recovery limitation |
| Correlation | Existing `X-Request-ID` convention across records, HTTP, both gRPC hops, Demand logs and outbound callback data |
| Status callbacks | Messaging tracking only; no Demand notification processing or broker publishing |
| Privacy | No raw webhook/text/token logging or storage in the specified messaging paths; safe error categories and masked real-provider evidence |
| Documentation | Messaging guide, architecture update, README setup and this acceptance report |
| Scope | No RabbitMQ business events, notification workers, AI/LLM or Stage 4 features |

### 1.1 Scope confirmation

Stage 3 delivers the WhatsApp communication foundation, including receiving incoming messages, processing deterministic customer commands, and sending immediate replies.

Event-driven inventory notifications, RabbitMQ workers and AI enhancements remain outside this stage.

---

## 2. Automated Validation Evidence

| Check | Observed result |
| --- | --- |
| Java compile/package | Passed |
| Java full regression | **140 tests passed; 0 failures, 0 errors, 0 skips** |
| Java inventory | Contracts: 2; shared security: 12; Catalog: 41; Demand: 85 |
| New Java coverage | Demand RPC validation/auth/mapping: 11; commands: 12; PostgreSQL consent/RPC: 3; Catalog message client: 6; Catalog service authentication: 4 |
| Frontend | **12 tests passed**, production TypeScript/Vite build passed |
| Go unit/race/build | Formatting, vet, race tests and Go builds passed; 19 top-level tests with table-driven subtests |
| Go database/HTTP integration | Disposable PostgreSQL race run passed: 3 database tests and 7 end-to-end handler scenarios |
| Concurrent duplicate proof | 20 parallel database claims produce one claimant; 10 signed handler deliveries produce one Demand call and one provider send |
| Delivery tests | Read-before-send reconciliation, duplicate, out-of-order status callbacks, unknown IDs and persisted timestamps passed |
| Protobuf | Go generation passed; Java contract/stub generation passed during Maven |
| Full `./scripts/test.sh` | Passed on 27 October 2026 after Docker/disk recovery |
| Docker Compose | Builds completed and all six containers reported healthy |
| Stage 0/1/2 regression | Infrastructure, database ownership, broker, Catalog, authentication, tenant isolation, consent and waitlist checks passed |
| Real Go → Java TCP acceptance | Authentication, serialization, correlation, `INVALID_ARGUMENT` and real deadline behaviour passed |
| Stage 3 live validator | **All checks passed on 8 November 2026** |
| Source checks | Python compilation and Git whitespace checks passed |

### 2.1 Final Stage 3 local validation

The command executed was:

`python3 scripts/validate-messaging.py`

Final results:

- PASS — generated Go → Java TCP: credentials, request/response, correlation and `INVALID_ARGUMENT`
- PASS — real TCP deadline while Demand waited on a controlled database lock
- PASS — webhook verification, invalid signature isolation, signed `HELP`, persistence and correlation
- PASS — eight concurrent duplicates produced one Demand call and one outbound message
- PASS — sent/delivered/read/failed status history, duplicate callbacks, unknown IDs and chronological state
- PASS — exact SKU through production Catalog gRPC, authoritative zero/positive stock, and `STOP` waitlist suppression
- PASS — delayed `STOP` updated consent without sending an expired-window reply
- PASS — Catalog outage: no guessed stock and safe fallback
- PASS — Demand outage: bounded failure persisted, no fabricated reply and no duplicate replay
- PASS — provider failure: uncertain state, no fabricated provider ID and one send attempt
- PASS — complete local Stage 3 acceptance, with base Messaging configuration restored

**Final local validation result: PASSED.**

The validator uses synthetic credentials, signed webhooks, a fake WhatsApp provider and real Go/Java/PostgreSQL services. It does not require live Meta credentials.

The existing migration regression verifies both V1 and V2 migrations and the expected total of two successful migrations.

Successful Catalog test calls use the production two-second deadline. A separate nonresponding-server test uses a shorter deadline to verify expiration.

Delayed commands still reach Demand while replies outside the 24-hour WhatsApp response window are suppressed.

Testcontainers and Go integration tests use real PostgreSQL, not H2.

---

## 3. Validation Refinements and Fixes

### 3.1 Timeout and deadline refinements

During integration testing, timing assumptions were identified and corrected.

Successful Catalog unit calls now use the production deadline, while the dedicated timeout test retains a short deadline.

The Go → Java live deadline test uses a bounded three-second database lock to demonstrate that the client deadline expires while the Java service is waiting.

Messaging's default Demand timeout is three seconds, allowing time for Demand's existing two-second Catalog deadline and safe fallback within the shared eight-second webhook bound.

### 3.2 Docker Compose configuration fix

A validation failure occurred when the Messaging Service retained its normal configuration instead of the temporary synthetic provider configuration.

The Stage 3 validator was updated to recreate the Messaging Service when applying the test override.

The validated command includes:

`--force-recreate`

This ensures the container starts with the intended synthetic test environment, preventing stale configuration from affecting webhook verification.

### 3.3 Temporary debug code removal

Temporary diagnostic output was introduced while investigating a failed provider-state assertion.

The test confirmed the expected state:

`PROVIDER_FAILED`

The temporary debug output was then removed.

The final assertion is:

`assert event_state(identifier) == "PROVIDER_FAILED"`

The complete validator was rerun after cleanup and passed.

### 3.4 Restoration of normal configuration

After local validation, the test script restores the base Docker Compose configuration and removes its temporary fake provider.

The restored Messaging Service was confirmed to use:

- `WHATSAPP_ENABLED=true`
- `WHATSAPP_API_BASE_URL=https://graph.facebook.com`

---

## 4. Environment Interruption and Recovery

During the initial validation work, the host filesystem temporarily ran out of free space.

Docker reported `read-only file system` errors affecting container metadata and temporary build output.

Generated StockPing Java archives were removed to recover approximately 155 MiB while preserving source files and test reports.

On 27 October 2026, the environment recovered with approximately 8.8 GiB of free space.

The full repository test script then passed.

Subsequent Docker Compose builds and health checks passed.

**No database volumes were removed during recovery.**

---

## 5. Real WhatsApp Cloud API Acceptance

**Acceptance date:** 8 November 2026
**Environment:** Local StockPing Docker Compose services, Cloudflare Quick Tunnel and Meta WhatsApp Cloud API test number
**Result:** PASSED for real `HELP` command

### 5.1 Real-provider acceptance checklist

| Required evidence | Result |
| --- | --- |
| Meta Developer app created | Passed |
| Official WhatsApp test number configured | Passed |
| Meta access token configured | Passed |
| App Secret and Verify Token configured | Passed |
| Public HTTPS webhook reachable | Passed |
| Webhook verification accepted by Meta | Passed |
| `messages` webhook field subscribed | Passed |
| Meta app published | Passed |
| WABA subscribed to StockPing app | Passed |
| Real incoming WhatsApp `HELP` message | Passed |
| Go Messaging and Demand processing | Passed |
| Real outbound WhatsApp reply received | Passed |
| Inbound message persisted in PostgreSQL | `PROCESSED` |
| Outbound message persisted in PostgreSQL | `READ` |
| Final local acceptance validator | Passed |

### 5.2 Public HTTPS webhook

Cloudflare Quick Tunnel was used to expose the local Go Messaging Service over HTTPS.

The local Messaging Service runs on port `8083`.

The public health endpoint returned:

`HTTP/2 200`

With the response:

`{"status":"UP"}`

The configured webhook path was:

`/webhooks/whatsapp`

Meta successfully verified the webhook using the configured Verify Token.

Meta's dashboard-generated `messages` webhook test also reported success.

Because the public endpoint uses a temporary Cloudflare Tunnel, the URL may change after the tunnel is stopped or restarted.

### 5.3 Meta app publication

The StockPing Meta Developer app was initially unpublished.

The required app settings were completed, including publicly accessible Privacy Policy and Data Deletion Instructions pages.

The app was then successfully published.

Public policy pages:

- Privacy Policy: https://hari8335.github.io/stockping/
- Data Deletion Instructions: https://hari8335.github.io/stockping/data-deletion.html

Publishing the Meta app did not by itself resolve the incoming-message issue.

Further subscription verification was required.

### 5.4 WABA subscription issue and resolution

During live testing, WhatsApp messages sent from the test phone did not initially reach StockPing's database.

The WhatsApp Business Account subscription was inspected using Meta's Graph API endpoint:

`GET /{waba-id}/subscribed_apps`

The initial response showed an app subscription, but not for the intended StockPing Meta app.

StockPing's app ID was confirmed through Meta's Access Token Debugger.

A subscription request was submitted using:

`POST /{waba-id}/subscribed_apps`

Meta returned:

`{"success":true}`

A subsequent subscription query confirmed that the StockPing app was subscribed successfully.

After correcting this subscription, a new real WhatsApp `HELP` message triggered an automatic StockPing reply.

**Resolution: the missing StockPing WABA app subscription was corrected.**

The pre-existing subscription for another app was left unchanged.

### 5.5 Access-token expiration

During troubleshooting, Meta returned HTTP `401` because the temporary access token had expired.

A fresh access token was generated through the Meta Developer Dashboard.

The new token was saved to the private, Git-ignored `.env` file.

The Go Messaging Service was recreated and its health was checked.

The corrected token enabled successful Graph API subscription checks.

Temporary Meta access tokens remain unsuitable for unattended long-running production operation.

### 5.6 API version configuration

The local real-provider configuration used Graph API `v25.0` during the later troubleshooting checks.

Meta's webhook dashboard displayed the `messages` field at `v27.0`.

The outgoing Graph API version and the webhook field version do not need to be identical.

The difference was not established as the cause of the delivery issue.

The relevant issue was the missing WABA app subscription.

The local automated fake-provider fixture continued using its configured `v26.0` test path.

### 5.7 Successful real WhatsApp HELP test

A real `HELP` message was sent from a personal WhatsApp account to Meta's test WhatsApp number.

After fixing the WABA subscription:

1. Meta delivered the incoming message to the configured StockPing webhook.
2. The Go Messaging Service accepted and processed the webhook.
3. StockPing generated the deterministic `HELP` response.
4. The reply was sent through the Meta WhatsApp Cloud API.
5. The automatic reply was received on the testing phone.
6. The corresponding database records were verified.

**Real WhatsApp HELP acceptance: PASSED.**

### 5.8 PostgreSQL verification

The following read-only query was executed against the Messaging database:

```sql
SELECT
    w.status AS webhook_status,
    m.direction,
    m.status AS message_status,
    COUNT(*) AS total
FROM webhook_events w
LEFT JOIN message_records m
    ON m.webhook_event_id = w.id
WHERE w.provider_event_id LIKE 'inbound:wamid.%'
GROUP BY w.status, m.direction, m.status
ORDER BY w.status, m.direction, m.status;
```

Observed output:

```text
PROCESSED|INBOUND|PROCESSED|1
PROCESSED|OUTBOUND|READ|1
```

This confirms:

- One real incoming message was successfully processed and persisted.
- One outgoing message was associated with the processed webhook.
- The outgoing message reached a recorded `READ` delivery state.

No personal phone numbers, access tokens, application secrets or complete provider message IDs are included in this report.

### 5.9 Real-provider acceptance limitations

The real-provider acceptance test demonstrated `HELP`.

The `STOCK` and `STOP` commands were tested through local integration tests but were not separately exercised through real WhatsApp during this acceptance session.

The report therefore declares Stage 3 complete against the agreed implementation and acceptance scope, without claiming separate real-provider coverage for every command.

---

## 6. Development and Security Limitations

The following limitations remain documented:

- Temporary Meta access tokens expire and require renewal.
- Cloudflare Quick Tunnel URLs may change after restarting the tunnel.
- The local development environment is not a durable production deployment.
- Meta webhook callback configuration must be updated if the public tunnel URL changes.
- Real-provider credentials must remain in private local environment configuration and must never be committed to Git.
- The provider-failure strategy avoids speculative retries when send outcomes are uncertain.
- The real WhatsApp `HELP` interaction is verified; separate real-provider validation of `STOCK` and `STOP` remains optional additional evidence.
- Automatic RabbitMQ stock notifications are reserved for Stage 4.
- AI/LLM enhancements are reserved for Stage 5.
- Long-lived credentials, durable hosting, operational monitoring and production hardening remain outside the Stage 3 scope.

Previously exposed development credentials were rotated during the acceptance process.

---

## 7. Commands Executed

### 7.1 Build and regression commands

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
```

### 7.2 Stage 3 acceptance and finalization commands

```sh
python3 -m py_compile scripts/validate-messaging.py scripts/fake-whatsapp.py

python3 scripts/validate-messaging.py

docker compose up -d --build --wait --wait-timeout 180

docker compose up -d --no-deps --force-recreate --wait messaging-service

docker compose ps messaging-service

docker compose ps

git branch --show-current

git status --short

git diff --check

git diff --cached --check

git commit -m "test(docs): finalize Stage 3 WhatsApp acceptance"
```

Additional direct Go toolchain containers ran:

- `go mod tidy`
- `gofmt`
- `go vet`
- `go test -race -v ./...`

Local validation used controlled HTTP servers and synthetic Meta credentials.

The real-provider acceptance was carried out separately with authorized testing credentials.

The final local acceptance validator passed after all temporary debugging code was removed.

---

## 8. Files Created and Modified

The inventory below records the Stage 3 implementation work.

### 8.1 Files created

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

### 8.2 Files modified

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

### 8.3 Final acceptance cleanup

The final Stage 3 cleanup specifically updated:

- `scripts/validate-messaging.py` — retained the Docker recreation fix and removed temporary diagnostic output.
- `docs/stage-3-validation.md` — documented complete local acceptance and successful real WhatsApp integration.

No Stage 4 or Stage 5 implementation was included in the cleanup.

---

## 9. Final Acceptance Decision

**Stage 3 Status: COMPLETE**

The Stage 3 implementation and validation objectives have been met.

The final evidence includes:

- Successful Java, Go and frontend regression testing.
- Healthy Docker Compose services.
- Authenticated Go-to-Java gRPC communication.
- Signed webhook verification and message processing.
- Idempotent inbound handling.
- Deterministic WhatsApp command handling in local integration tests.
- Outbound Meta WhatsApp Cloud API integration.
- Successful real incoming `HELP` message.
- Successful automatic WhatsApp reply.
- PostgreSQL verification of inbound `PROCESSED` and outbound `READ`.
- Documented provider failures, timeout handling and security constraints.

**Stage 3 is accepted and ready for final Git publication.**

### Next development phase

**Stage 4 — RabbitMQ Events and Automatic Stock Notifications**

The next stage will focus on stock-change business events, reliable event consumption and automatic notifications for eligible waitlisted customers.

AI enhancements remain reserved for Stage 5.