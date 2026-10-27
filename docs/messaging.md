# Stage 3 Messaging integration

Messaging connects signed WhatsApp Cloud API webhooks to Java Demand over generated gRPC, then sends immediate text replies. Real-provider acceptance requires an official configured number and public HTTPS callback; local acceptance uses a controlled provider double.

```text
WhatsApp → Meta → HTTPS /webhooks/whatsapp → Go Messaging → messaging_db
                                               ↓ authenticated generated gRPC
                                          Java Demand → demand_db
                                               ↓ exact STOCK command via gRPC
                                          Java Catalog → catalog_db
                                               ↓ provider-independent reply
                     WhatsApp ← Cloud API ← Go outbound adapter
```

## Ownership and authentication

Messaging owns Meta payloads, signatures, credentials, trusted channel mapping and delivery callbacks. It connects only to `messaging_db`. Demand owns conversation decisions and customer/consent changes. Catalog alone owns stock truth. No cross-service SQL, business RabbitMQ producers/consumers, notification workflow or AI has been added.

`SingleChannel` implements `ChannelResolver`: both configured WABA ID and phone-number ID must match before the configured merchant UUID is accepted. Customer text cannot select a merchant. A future Messaging-owned mapping repository can implement the same interface.

`MESSAGING_DEMAND_TOKEN` authenticates Messaging → Demand through `x-service-token` metadata and constant-time comparison. Missing/incorrect credentials are rejected before business logic. The enabled Java server requires at least 32 characters. Demand validates canonical lowercase merchant/correlation UUIDs, canonical international phone, required bounded text/ID/timestamp fields, and an existing active merchant.

`CATALOG_SERVICE_TOKEN` is an independent credential for Demand's read-only Catalog RPCs, with trusted `x-merchant-id`. Catalog REST still requires the existing browser JWT. Existing manual/API waitlists continue delegating their administrator JWT. The two service credentials are separate from `AUTH_JWT_SECRET` and are generated into ignored `.env` by `scripts/init-local-env.py`.

Local gRPC is plaintext on the private Compose network. Demand port 9092 has no host mapping; the live Go checker runs inside that network. Catalog retains its existing loopback port for Stage 1 validation. Authentication does not encrypt transport. Use appropriate TLS termination for deployment; the Go client supports TLS, while Java certificate/termination deployment is outside this local stage.

## Webhook contract

Enabled `GET /webhooks/whatsapp` accepts exactly one each of `hub.mode=subscribe`, `hub.verify_token`, and nonempty `hub.challenge`. Correct verification returns 200 with the exact unquoted challenge, without a newline. Wrong token returns 403; missing/malformed/duplicate parameters return 400. Tokens are never logged.

`POST /webhooks/whatsapp` caps the raw body at 1 MiB and validates `X-Hub-Signature-256`: HMAC-SHA256 of the unaltered bytes, keyed with `WHATSAPP_APP_SECRET`, compared in constant time. Invalid/missing signatures return 401 before parsing or persistence. The verify token and app secret have separate roles.

Supported envelopes contain `object=whatsapp_business_account`, entry WABA ID, `changes[].field=messages`, `messaging_product=whatsapp` and metadata phone-number ID. All entries/changes are examined; at most 100 recognized message/status events are accepted. Unknown fields are ignored for forward compatibility. Malformed supported events return 400, unknown channels 403, oversized bodies 413. Validation finishes before any side effects.

Normalization converts provider `from` digits to canonical `+digits`, matches display names by `wa_id`, trims outer whitespace while preserving SKU case, validates IDs/timestamps, and carries a correlation UUID. Text is limited to 4096 Unicode code points. Display names over 200 bytes are omitted. Raw payloads and message text are neither persisted nor logged. Images/audio/video/stickers/documents are recorded as `UNSUPPORTED`, with no Demand call/reply. Media processing and phone-less customer identity formats are not implemented.

A canonical `X-Request-ID` is retained or a UUID generated. It follows the inbound/outbound database rows, Messaging logs/HTTP response, protobuf request/response, gRPC metadata, Demand logs/MDC, Catalog metadata and outbound HTTP `X-Request-ID`/`biz_opaque_callback_data`. No phone or text is embedded in it.

## Database and idempotency

`internal/store/migrations/001_messaging.sql` creates the Messaging tables. Startup applies this embedded numbered migration transactionally under a PostgreSQL advisory lock. `schema_migrations` records version, SHA-256 checksum and application time. Repeated startup is a no-op; checksum drift fails startup. No manual table creation or database recreation is required. Starting the executable/Compose applies migrations before HTTP starts.

| Table | Fields and constraints |
| --- | --- |
| `webhook_events` | UUID, provider key, merchant, INBOUND/DELIVERY type, received/processed times, status and safe error code. Unique merchant/provider key |
| `message_records` | UUID, merchant, webhook FK, canonical phone, INBOUND/OUTBOUND, purpose, optional provider ID, status/error, correlation, create/update times. Unique webhook/direction and merchant/direction/provider ID |
| `delivery_events` | UUID, merchant, webhook FK, nullable message FK, provider ID, sent/delivered/read/failed, provider timestamp and numeric error reference. Unique merchant/provider/status/time |

An atomic transaction inserts the webhook claim and inbound record with `ON CONFLICT DO NOTHING`. Only the successful claimant invokes Demand. Concurrent duplicates and retries after failure/restart do not call Demand or send another reply. Outbound intent is recorded `SENDING` before HTTP, with one outbound row per webhook enforced by PostgreSQL.

This is **at-most-once processing**, not exactly-once delivery. A crash after claiming can leave `PROCESSING`; a crash after sending can leave `SENDING`. Ambiguous sends become `UNKNOWN`. These IDs remain deduplicated. Operators must inspect them; there is no automatic replay, stale-claim lease, durable worker or outbox. Text is not retained for reconstruction. Customers can issue a new command with a new provider ID after an outage. This is the documented Stage 3 recovery limitation.

Delivery callbacks deduplicate by merchant/provider ID/status/timestamp. Unknown IDs remain unlinked until a later send result can reconcile them. A per-provider advisory transaction lock serializes callbacks and send-result persistence. Events retain chronology; message state follows the latest timestamp, with read/delivered/failed/sent precedence for equal times. No status event calls Demand or publishes RabbitMQ.

Demand's `V2__customer_message_consent.sql` extends the existing consent source check with `CUSTOMER_MESSAGE`, preserving `MANUAL_API` and V1 unchanged.

## Generated RPC and deterministic commands

`contracts/proto/stockping/demand/v1/demand.proto` defines unary `ProcessCustomerMessage`. Request fields: merchant, normalized phone, optional display name, provider ID, text, RFC3339 time and correlation. Response: `SEND_TEXT`/`NO_REPLY`, reply text, purpose and correlation. Provider URLs, bodies and tokens never cross this contract.

The thin adapter validates/maps requests and delegates to `CustomerMessageService`. Error mappings include UNAUTHENTICATED, INVALID_ARGUMENT, NOT_FOUND for missing/inactive merchant, and sanitized INTERNAL. Go validates response action, text, purpose and correlation before sending.

| Command | Behavior |
| --- | --- |
| `HELP` | Returns command guidance; no customer creation |
| `STOP`, `STOP NOTIFICATIONS` | Tenant-scoped phone lookup; reuses the existing locked consent transaction, appends OPT_OUT/CUSTOMER_MESSAGE audit and suppresses active waitlists. Unknown customer gets acknowledgement without new state |
| `STOCK <exact SKU>` | Case-insensitive command, literal case-sensitive SKU of 1–100 non-whitespace characters. ResolveProductVariant then GetInventory under one overall deadline; validates identity and stock consistency. Reply includes product name/SKU and verified availability |
| Other text/missing SKU | HELP-style fallback; no natural-language extraction |
| Catalog unavailable/timeout/ambiguous/inconsistent | Safe “cannot verify stock” reply; no guessed availability or new waitlist |

STOP acknowledgement is an immediate customer response, not a restock notification. Arbitrary-text waitlists, opt-in conversations and final-production conversational behavior are deferred.

## Provider, timeout and failure policy

The adapter posts JSON text messages to `https://graph.facebook.com/{version}/{phone-number-id}/messages` with Bearer authorization, international digits, previews disabled and correlation data. Redirects are rejected. Responses are bounded; exactly one nonempty provider message ID is required for `ACCEPTED`, which is not proof of delivery.

| Boundary | Default/bound |
| --- | --- |
| Messaging → Demand | 3000 ms, configurable 50–5000 ms |
| Demand → Catalog | Existing 2000 ms overall deadline, capped by incoming gRPC deadline |
| WhatsApp HTTP | 3000 ms, configurable 50–5000 ms |
| Webhook downstream work | 8-second shared batch context |
| Final state persistence | Independent 2-second context after caller cancellation |
| HTTP server | Header/read/write/idle: 5/10/15/60 seconds |
| PostgreSQL | Connect/statement/lock: 5/3/2 seconds, additionally context bounded |

Messages already 24 hours old still reach Demand so delayed STOP commands update consent; their outbound replies are suppressed and recorded `EXPIRED`. Stage 3 only sends immediate replies; templates and later restock workflows are absent. Provider messaging-window enforcement remains authoritative.

Database failures return 503. Once a failure is durably recorded, Demand/provider failures return HTTP 200 acknowledging receipt while explicitly recording `DEMAND_FAILED`/`PROVIDER_FAILED`. Duplicate redelivery does not reprocess. Demand unavailable/timeout/authentication/rejection and provider HTTP authentication/validation/throttling/network/timeout/5xx/malformed responses have distinct safe codes. Permanent rejections become `FAILED`; ambiguous network/timeout/5xx/unparseable success becomes `UNKNOWN`, without fabricated IDs. Keep the outer Demand timeout longer than the Catalog deadline to allow a safe stock fallback to return. There are no automatic HTTP or application gRPC retries, since an uncertain send can otherwise duplicate replies.

## Configuration

Run `python3 scripts/init-local-env.py` to extend ignored mode-600 `.env`, without overwriting nonempty values or printing credentials. Provider integration is disabled by default; migrations and database-backed `/health` still work. Disabled webhook endpoints return 404.

| Variable | Purpose |
| --- | --- |
| `WHATSAPP_ENABLED` | Explicit provider enablement; false by default |
| `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_APP_SECRET`, `WHATSAPP_ACCESS_TOKEN` | Verification, payload authentication, outbound authentication respectively |
| `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_WABA_ID`, `WHATSAPP_MERCHANT_ID` | Trusted single-channel mapping to an existing active merchant |
| `WHATSAPP_GRAPH_API_VERSION` | Explicit required version when enabled; no production default |
| `WHATSAPP_HTTP_TIMEOUT_MS` | Provider deadline |
| `WHATSAPP_API_BASE_URL`, `WHATSAPP_ALLOW_TEST_ENDPOINT` | Official origin by default; custom fake endpoint requires explicit test mode |
| `MESSAGING_DEMAND_TOKEN`, `CATALOG_SERVICE_TOKEN` | Independent internal service credentials |
| `DEMAND_GRPC_TARGET`, `DEMAND_GRPC_TIMEOUT_MS`, `DEMAND_GRPC_TLS` | Go target/deadline/transport; Compose target demand-service:9092 |
| `DEMAND_GRPC_ENABLED`, `DEMAND_GRPC_PORT` | Java server; enabled privately on 9092 in Compose |
| `MESSAGING_DB_HOST/PORT/USER/PASSWORD/SSLMODE` | Own DB connection; name always messaging_db; local disable or verify-full SSL mode |

Only placeholders appear in `.env.example`. Do not log resolved Compose config, tokens, full phone numbers, message text or raw webhook/provider bodies. Real test evidence must mask phone/provider identifiers. Production retention duration remains an operator decision; no deletion worker is added.

```sh
./scripts/generate-proto.sh
./scripts/test.sh                     # includes real disposable PostgreSQL tests
./scripts/test-messaging-db.sh         # standalone database/webhook integration
python3 scripts/validate-messaging.py  # running Compose, fake provider, real Go/Java
```

The validator requires demo login, temporarily overrides Messaging, creates a fake-provider container, exercises real generated Go → Java RPCs and service outages, and restores base Compose configuration in `finally`. Synthetic evidence rows remain. CI needs no live Meta credentials. There are no test-only RPC branches in Java.

## Real WhatsApp acceptance procedure

1. Configure an official Meta app, WABA and registered test/business number. Add the consenting recipient where required. Obtain credentials privately from Meta's dashboard.
2. Confirm a currently supported Graph version in current Meta documentation/dashboard. Configure it, the channel IDs and existing active StockPing merchant UUID in ignored `.env`; enable WhatsApp with the official base URL and test mode false.
3. Run `docker compose up -d --no-deps --wait messaging-service`; verify backend health.
4. Use a developer-controlled temporary HTTPS tunnel/reverse proxy to `http://127.0.0.1:8083/webhooks/whatsapp`. Register its public URL ending `/webhooks/whatsapp` and the same verify token in Meta. Expose only this webhook path, not databases, broker or internal APIs. No tunnel vendor is an architectural dependency.
5. Verify the callback, subscribe to `messages`, and ensure the app is subscribed to the WABA. A dashboard sample is not a real customer message.
6. Send `HELP` from the real consenting phone. Confirm inbound ID/correlation, one Demand log, outbound provider ID and the actual phone reply. Optionally send `STOCK <known-test-SKU>`.
7. Observe statuses and record local date/time, actual Graph version and masked evidence in `docs/stage-3-validation.md`. An accepted API response alone does not prove receipt.
8. Stop the temporary tunnel. No Stage 4 or permanent deployment is implied.

## Official references and verification boundary

Checked on 26 October 2026: Meta's [Cloud API collection](https://www.postman.com/meta/whatsapp-business-platform/documentation/wlk6lh4/whatsapp-cloud-api?entity=folder-13382743-ba8d099d-007e-4b52-b9f2-3cf3c60e4fbc), [messages/permissions](https://www.postman.com/meta/whatsapp-business-platform/folder/o48mro7/messages), [text endpoint](https://www.postman.com/meta/whatsapp-business-platform/request/8gvd47s/send-text-message), [WABA subscription](https://www.postman.com/meta/whatsapp-business-platform/documentation/wlk6lh4/whatsapp-cloud-api?entity=request-13382743-f4211a46-9301-4735-a23c-7359f1a7f849), and [Meta webhook signature sample](https://github.com/fbsamples/whatsapp-api-examples/blob/main/signature-validation-with-webhooks-payloads/app.py). StockPing uses separate app/verify secrets and constant-time comparison.

Direct Meta [webhook guidance](https://developers.facebook.com/docs/graph-api/webhooks/getting-started), [WhatsApp overview](https://developers.facebook.com/documentation/business-messaging/whatsapp/overview), [sending guide](https://developers.facebook.com/docs/whatsapp/cloud-api/guides/send-messages) and [Graph changelog](https://developers.facebook.com/docs/graph-api/changelog/) returned HTTP 429 during this run. The accessible official Meta Postman references were the fallback. No live API version/account-specific window/template behavior has been validated; recheck those pages and the app dashboard before real acceptance. `v26.0` in automated tests is a fake-provider fixture, not a live compatibility result.
