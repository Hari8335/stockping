# Stage 2 Demand slice

Demand owns merchant authentication, customers, auditable consent, and variant-specific waitlists in `demand_db`. Catalog alone verifies variant identity and inventory through generated gRPC contracts. The merchant UI provides login, waitlist filtering/pagination and confirmed administrative opt-out. Manual creation uses the API before WhatsApp exists.

## Local setup and demo account

```sh
python3 scripts/init-local-env.py
docker compose up --build --wait --wait-timeout 180
python3 scripts/smoke-test.py
python3 scripts/validate-catalog.py
python3 scripts/validate-demand.py
```

`init-local-env.py` creates/extends ignored `.env`, sets mode 600, and generates missing signing/demo-password values without printing them or replacing existing nonempty settings. Read `DEMO_ADMIN_EMAIL` and `DEMO_ADMIN_PASSWORD` in that private file to sign in at http://localhost:5173. Default demo email is `admin@demo.stockping.local`; the password is generated locally, not committed. Database/broker example passwords remain local examples and should be replaced before deployment.

Compose enables the explicitly configured `demo` profile. Its idempotent bootstrap creates Demo Electronics and a BCrypt-hashed administrator only when the configured email does not already exist. The merchant UUID defaults to Stage 1's existing demo merchant, preserving its catalogue. Existing users/passwords are never overwritten on startup; changing `.env` does not rotate an existing password. Do not enable this profile in a deployed environment. Provisioning a production user, public registration, password reset and password rotation UI are outside Stage 2.

Native Demand needs `DEMAND_DB_URL`, `DEMAND_DB_USER`, `DEMAND_DB_PASSWORD`, `AUTH_JWT_SECRET`, and the Catalog address. Root `.env` is consumed by Compose, not automatically by native Java. Both services must receive the same secret. See `.env.example` for Compose configuration.

## Authentication and merchant scope

Demand alone stores credentials and issues tokens. `POST /api/auth/login` accepts email and password. Emails are trimmed/lowercased with global uniqueness; each user belongs to exactly one merchant. Active users of active merchants can log in. BCrypt uses cost 12 and rejects passwords above its 72-byte input boundary. Unknown users perform a dummy hash comparison and receive the same safe error as incorrect passwords.

Tokens use HS256 with an externally configured secret of at least 32 bytes. The default expiry is 1,800 seconds (30 minutes); `AUTH_JWT_EXPIRY_SECONDS` permits 60–3,600 seconds. Claims are `sub` (user UUID), `merchant_id`, `role`, `iat`, `exp`, issuer `stockping-demand`, and audience `stockping-merchant-api`. Validation pins HS256 and checks signature, issuer, audience, canonical UUIDs, issued/expiry times, and required identity claims. Expiry has no grace window; clocks must be synchronized. All merchant APIs require `MERCHANT_ADMIN`. Both Spring services use the small `shared-security` library so their token-validation policy stays consistent. Catalog has no User table, password encoder, login, or token issuer.

`POST /api/auth/logout` requires authentication and returns 204. The client immediately deletes its in-memory token. Tokens already copied elsewhere remain valid until expiry; there is no revocation list or refresh token. Account/merchant deactivation blocks new logins but previously issued tokens also expire naturally. This is an explicit MVP limitation.

The React app keeps the token only in component memory, never in localStorage/sessionStorage or cookies. Reloading or closing the tab requires login. It clears the password after successful authentication and removes the session on expiry or a protected API's 401. Logout removes the UI session even if the acknowledgement cannot be reached. In-memory tokens reduce persistence but remain exposed to successful same-page script injection; deployed use needs TLS and normal browser security hardening. CSRF is disabled because credentials are explicit bearer headers, not automatically attached auth cookies. No permissive cross-origin policy is installed: the local Vite proxy serves browser API requests on the frontend origin.

`Identity.current()` reads the validated Spring Security principal. Catalog retains `MerchantContextProvider`, now implemented by `AuthenticatedMerchantContextProvider` for REST and validated gRPC context. REST payloads cannot set merchant ownership; unknown JSON fields are rejected. Query/header tenant claims cannot override the principal. Inaccessible IDs use the same 404 as missing resources. Health is public; other Actuator operations are not exposed.

## Persistence

Flyway migration `demand-service/src/main/resources/db/migration/V1__demand.sql` creates six tables. Hibernate validates and never creates/updates the schema. All business timestamps are UTC instants; Merchant stores an IANA timezone for future display needs.

| Model | Stored fields and rules |
| --- | --- |
| Merchant | UUID, name, timezone, ACTIVE/INACTIVE status, created/updated timestamps; tenant root |
| User | UUID, merchant UUID, normalized unique email, BCrypt hash, MERCHANT_ADMIN role, ACTIVE/INACTIVE status, timestamps |
| Customer | UUID, merchant UUID, normalized WhatsApp phone, optional display name/language, ACTIVE/INACTIVE status, timestamps; phone unique within merchant |
| ConsentRecord | UUID, ordered sequence, merchant/customer UUIDs, RESTOCK_NOTIFICATIONS type, OPT_IN/OPT_OUT status, MANUAL_API source, captured timestamp |
| WaitlistRequest | UUID, merchant/customer UUIDs, external variant UUID, status, created/updated timestamps, nullable notified timestamp |
| DemandAudit | UUID, merchant UUID, event type, entity UUID, JSONB metadata, timestamp; records consent actions and waitlist creation |

Internal composite foreign keys require customer and consent/waitlist merchant ownership to agree. User email is unique globally. Customer uniqueness is `(merchant_id, whatsapp_phone)`. `variant_id` is a logical Catalog reference with **no cross-database foreign key**. Demand has neither Catalog datasource configuration nor Catalog repository/entity dependencies. The shared libraries contain contracts and security only. Database roles still deny cross-service database connections.

Audit metadata includes only source or variant identity; it contains no password, token, customer name or phone. Application errors are sanitized. ECS logs include service, severity and a generated request ID; REST returns `X-Request-ID`, and Demand propagates it to Catalog gRPC. Request completion logging omits bodies, URLs, credentials and phone values.

## Customer and consent rules

Numbers must explicitly start with `+` and contain 8–15 digits, with a nonzero first digit. Spaces, parentheses and hyphens are removed deterministically; local numbers, extensions, letters, multiple plus signs and invalid lengths are rejected. This is format normalization, not carrier verification or full country numbering-plan validation. No Sri Lanka default is inferred. `+94 (77) 123-4567` becomes `+94771234567`. The same normalized number is independent across merchants.

Creation inserts a customer only if absent, then takes a tenant-scoped PostgreSQL row lock. Existing profile fields are not silently overwritten by another waitlist submission. Every consent change also takes that same lock. Consent history is append-only through the application; there is no public edit/delete endpoint. Effective consent comes from the latest sequence for that merchant/customer, rather than a mutable boolean. Sequence order disambiguates equal timestamps.

For a customer without history, `consent: true` in manual creation captures initial OPT_IN. `consent: false` is allowed only when an existing effective OPT_IN already exists. An OPT_OUT always blocks creation, even if a new create request says `consent: true`. A merchant must first capture explicit renewed consent with `POST /api/customers/{id}/opt-in`. This separate action appends history; it never reactivates old waitlists.

Opt-out appends OPT_OUT and changes all active waitlists for the customer to OPTED_OUT in the same transaction. History is retained. An inactive customer cannot opt in or create a waitlist. Customer identity is verified against the authenticated merchant before either consent operation.

## Waitlist rules and concurrency

Creation runs in one transaction: authenticated merchant → normalize/find/create and lock customer → validate/capture consent → resolve exact active Catalog variant → retrieve authoritative stock → require quantity zero/UNAVAILABLE → reject active duplicate → insert WAITING and audit.

Active statuses are **WAITING and NOTIFICATION_QUEUED**. The latter is reserved for later stages but participates in duplicate protection and opt-out so future queue states cannot introduce duplicate demand. A PostgreSQL partial unique index enforces `(merchant_id, customer_id, variant_id)` for both statuses. The application also checks duplicates for a clear 409. The unique index remains authoritative for writers that bypass the application check. Customer creation uses `INSERT ... ON CONFLICT DO NOTHING`, then a row lock; simultaneous normalized-phone requests cannot create two customers. The shared customer lock serializes creation with opt-out.

Stage 2 creates only WAITING and implements `WAITING → OPTED_OUT` (and suppression of an already queued row). Supported storage statuses also include NOTIFIED, FULFILLED, EXPIRED and CANCELLED. No public arbitrary status update, cancellation flow, notification processing or transition to NOTIFICATION_QUEUED/NOTIFIED is implemented. `notified_at` remains null for Stage 2 creations. After renewed consent a new WAITING row may be created; historical OPTED_OUT rows remain unchanged.

A failed Catalog lookup, unavailable item check, missing consent, duplicate, or audit insertion rolls back the transaction, including newly created customer/consent records. An existing customer/consent remains unchanged. A 20-second creation transaction, database lock timeout (5 seconds), statement timeout (15 seconds), and gRPC deadline bound waiting. Verification observes Catalog at RPC time; there is no distributed transaction/reservation preventing inventory changing immediately afterward. Restock reconciliation belongs to the later event stage.

## Demand → Catalog gRPC

Demand uses the generated `CatalogServiceBlockingStub` and a lifecycle-managed channel. `CATALOG_GRPC_HOST` (native default localhost), `CATALOG_GRPC_PORT` (9091) and `CATALOG_GRPC_TIMEOUT_MS` (2,000; allowed 50–10,000) are configuration. Both sequential calls share one deadline, so the overall lookup is bounded rather than allowing twice the timeout. There are no automatic retries or stock fallbacks.

`ResolveProductVariantRequest.variant_id = 6` is an additive protobuf field for exact identity. It is mutually exclusive with the existing search criteria; existing field numbers and SKU/search behavior are preserved. Catalog checks both active variant and active parent, then returns the exact candidate. Demand requires MATCHED, exactly one candidate and matching identity before `GetInventory`. It validates the returned identity and quantity/availability consistency; quantity >0 produces ITEM_AVAILABLE. Ambiguous results never select a candidate.

Demand sends the authenticated user's bearer JWT as `authorization` metadata and merchant UUID from that validated identity in the request. Catalog independently validates the token/role in an interceptor, puts identity in gRPC Context, checks the request's merchant assertion, and scopes database queries by the signed merchant. A user cannot forge another merchant by changing the RPC payload. HTTP and gRPC contexts are thread-safe and independent. This is delegated user authentication, not a service credential or a public Messaging business RPC.

Local Compose uses plaintext gRPC on its private network with loopback-published host ports. A deployed environment must provide authenticated TLS transport/termination for gRPC and HTTPS for REST. Demand supports TLS to a configured endpoint with `CATALOG_GRPC_TLS=true`; Catalog's local listener itself does not terminate TLS. Sharing an HS256 key means both validation services hold signing-capable material; an asymmetric verification key is a later hardening option.

| Condition | Demand HTTP / code |
| --- | --- |
| Missing/inaccessible variant | 404 VARIANT_NOT_FOUND |
| Ambiguous resolution | 409 AMBIGUOUS_VARIANT |
| Available stock | 409 ITEM_AVAILABLE |
| Duplicate active request | 409 DUPLICATE_ACTIVE_WAITLIST |
| No consent / previous opt-out | 409 CONSENT_REQUIRED / CUSTOMER_OPTED_OUT |
| Invalid Catalog request | 400 CATALOG_INVALID_REQUEST |
| Catalog unavailable | 503 CATALOG_UNAVAILABLE |
| Deadline exceeded | 504 CATALOG_TIMEOUT |
| Unexpected gRPC failure | 502 CATALOG_ERROR |
| Invalid/inconsistent response | 502 CATALOG_INVALID_RESPONSE |
| Rejected delegated token / role | 401 INVALID_TOKEN / 403 FORBIDDEN |

All REST errors use Problem Details with stable `code`; malformed payloads/filters return 400 INVALID_REQUEST, inaccessible Demand resources 404 NOT_FOUND, and unexpected failures 500 INTERNAL_ERROR. No exception details are returned.

## REST endpoints

All endpoints except login and health require `Authorization: Bearer <token>`.

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/api/auth/login` | `{email,password}` → accessToken, expiresAt, userId, merchantId, role; no-store |
| POST | `/api/auth/logout` | 204; client deletion, natural JWT expiry |
| POST | `/api/waitlists` | Manual/API entry point before Stage 3; 201 + Location + waitlist DTO |
| GET | `/api/waitlists` | Tenant-scoped page; optional status, variantId, customerId, from, to, page, size |
| GET | `/api/waitlists/{id}` | Tenant-scoped detail with effective consent and eligibility |
| POST | `/api/customers/{id}/opt-out` | Append opt-out and suppress all active waitlists |
| POST | `/api/customers/{id}/opt-in` | Explicit initial/renewed consent; no old-row reactivation |
| GET | `/api/analytics/demand` | totalActive, totalWaiting and per-variant request/waiting/active counts |

Example manual creation body:

```json
{
  "customerPhone": "+94771234567",
  "displayName": "Demo Customer",
  "language": "en",
  "variantId": "replace-with-a-real-catalog-variant-uuid",
  "consent": true
}
```

`POST /api/waitlists` supplements the SRS's original read-only REST table to satisfy the Stage 2 manual/API exit condition. Consent opt-in is a focused endpoint, not generic Customer CRUD. Business logic lives in reusable services rather than controllers.

List dates are ISO-8601 instants with inclusive boundaries. Default page 0 / size 25; size 1–100. Ordering is created time descending, then UUID ascending for stable ties. Customer filtering uses an ID, keeping phone numbers out of URLs. Analytics is scoped to the current merchant and uses grouped database counts; it has no forecast, recommendation or financial metric.

## Frontend and validation

The MUI login provides required fields, pending and invalid-login states. The authenticated screen shows masked phones, variant IDs, status, effective consent and creation time; filters by status/variant, paginates, refreshes, handles loading/error/empty states, and confirms opt-out. Total active requests is a simple count. No Products, Notifications or advanced dashboard screen is added. Catalogue setup and manual creation remain authenticated APIs. The waitlist screen is loaded on demand after login.

`./scripts/test.sh` runs Java/PostgreSQL, frontend tests/build, Java/Go generation and Go formatting/vet/race/build. `scripts/validate-catalog.py` now logs in before running the existing REST/Go gRPC regression. Its token passes to the child process through an environment variable, not a command-line value. `scripts/validate-demand.py` exercises two real services and tenants, consent/duplicate rules, availability, same-origin proxy, and a temporary Catalog stop/restart. These scripts leave synthetic demo records for inspection; the Demand script creates a separate fixture merchant using the demo password hash. This SQL fixture setup is test infrastructure, never a Demand-to-Catalog database path.

See [Stage 2 validation](stage-2-validation.md) for executed evidence and the change inventory. Stage 0/1 evidence is preserved. WhatsApp, Messaging→Demand business gRPC, AI, RabbitMQ business events and notification delivery remain unimplemented.

Implementation references: [Spring Security JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [gRPC metadata](https://grpc.io/docs/guides/metadata/), and [gRPC deadlines](https://grpc.io/docs/guides/deadlines/).
