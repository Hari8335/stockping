# StockPing

StockPing is a full-stack, microservices-based retail demand and stock notification platform that captures customer intent through WhatsApp and automatically alerts subscribed, consenting shoppers the moment an out-of-stock product variant is replenished.

**Current implementation: Stage 5 — AI Enhancements & Final System Integration.** Catalog detects restock transitions via row-level locks and transactional outbox events; Demand matches customer waitlists, enforces consent, and processes natural-language inquiries with AI; Go Messaging revalidates eligibility over authenticated gRPC and dispatches approved WhatsApp templates via Meta Cloud API or safe test providers. See [Stage 5 validation](docs/stage-5-validation.md), [Stage 4 notification pipeline](docs/stage-4-validation.md), [Architecture](docs/architecture.md), [Demand](docs/demand.md), [Catalog](docs/catalog.md), and [Messaging](docs/messaging.md).

## Core Capabilities

- **Microservices Architecture**: Independently deployable Spring Boot services (`catalog-service`, `demand-service`) and a high-performance Go service (`messaging-service`).
- **Database-per-Service**: Strong data ownership using three isolated PostgreSQL databases (`catalog_db`, `demand_db`, `messaging_db`) with dedicated roles.
- **Reliable Event-Driven Notifications**: Transactional outbox pattern with RabbitMQ quorum queues, dead-letter routing, exponential retry backoff, and idempotent consumers.
- **WhatsApp Cloud API Integration**: Signed webhook verification, deduplication, at-most-once durable sending claims, and bidirectional message delivery tracking.
- **AI Inquiry Understanding**: Constrained natural-language stock inquiry parsing powered by Google Gemini with a zero-credential, mixed-language (English + Singlish / colloquial slang) rule-based fallback.
- **Conversational Waitlist Registration**: Context-aware follow-up opt-in (`YES` confirmation or `WAITLIST <SKU>`) directly from WhatsApp chat.
- **Merchant Web Dashboard & Landing Page**: React + TypeScript + Material UI application with a contemporary retail operations aesthetic. Features a public landing page (`/`), merchant sign-in (`/login`), inventory and variant management (`/app/products`), customer waitlist tracking with consent enforcement (`/app/waitlists`), notification history audit (`/app/notifications`), and an AI inquiry preview console (`/app/ai`). See [Frontend Validation](docs/frontend-completion-validation.md).

## Architecture and technologies

The system has three backend services. Catalog owns products and authoritative inventory; Demand owns merchant authentication, customers, consent and waitlists; Messaging owns WhatsApp transport and delivery state. React + TypeScript + Material UI provides the merchant interface.

Browser/external boundaries use HTTP/REST. Messaging → Demand and Demand → Catalog use generated gRPC/Protocol Buffers with authenticated internal calls. One PostgreSQL server hosts three service-owned databases: catalog_db, demand_db and messaging_db. RabbitMQ carries versioned stock-available and notification-requested business events through durable quorum queues with bounded retry and dead-letter handling.

The skeleton uses Java 21, Spring Boot 4.1.1, Maven, Go 1.26.8, Node.js 24, Vite, PostgreSQL 18 and RabbitMQ 4 management. [Architecture decisions](docs/architecture.md) explain the shared contracts, service boundaries and database isolation.

```text
stockping/
├── frontend/                  # Vite, React, TypeScript, MUI, page test
├── catalog-service/           # Spring Boot app and HTTP integration tests
├── demand-service/            # Spring Boot app and HTTP integration tests
├── messaging-service/         # Go cmd/, internal/, generated gen/ (ignored)
├── shared-security/           # Shared JWT validation; no persistence or credentials
├── contracts/
│   ├── proto/                 # Versioned protobuf/gRPC source of truth
│   ├── java/                  # Generated Java contract library and wire test
│   └── events/                # Versioned Stage 4 JSON schemas
├── infra/postgres/            # Empty service databases and separate roles
├── scripts/                   # Generation, tests, Compose smoke verification
├── docs/                      # Architecture, quality rules, validation
├── .github/workflows/ci.yml
├── docker-compose.yml
├── pom.xml                    # Java reactor build
├── .env.example
└── README.md
```

The pre-existing empty `stockping/.git` nested directory is not part of the application and was preserved. Run every command below from this README's directory.

## Prerequisites

- Docker Desktop (or Docker Engine) with Docker Compose v2.20+; keep the daemon running. Allow roughly 4 GB of memory for builds and the local stack.
- Internet access for initial dependency and image downloads.
- For native Java/frontend development and `scripts/test.sh`: JDK 21, Maven 3.9+, Node.js 24 and npm.
- For native Go development: Go 1.26.8+. A host Go or `protoc` installation is **not required** for the Docker workflow; the generator image includes pinned tools.
- Python 3.10+ for the dependency-free Compose smoke test.

## Start locally

```sh
python3 scripts/init-local-env.py
docker compose config --quiet
docker compose up --build --wait --wait-timeout 180
python3 scripts/smoke-test.py
```

The demo login email/password are in the private ignored `.env`; open http://localhost:5173 to sign in. The initializer generates a signing key and demo password without printing them. Other example passwords are local development examples. Set private values in the ignored `.env`. Do not publish that file or resolved Compose configuration. All published ports bind to loopback. Images run application processes as unprivileged users. Initial builds run unit/contract tests and can take several minutes. Run `./scripts/test.sh` for the full PostgreSQL-backed integration suite.

To start infrastructure alone:

```sh
docker compose up -d --wait postgres rabbitmq
```

To inspect or stop the environment:

```sh
docker compose ps
docker compose logs --tail 100
docker compose down
```

Named volumes preserve PostgreSQL and RabbitMQ data across restarts. Database creation scripts run only on the first initialization of an empty PostgreSQL volume. Changing passwords in `.env` does not change existing database roles; use SQL to rotate them or deliberately reset a disposable local volume. Catalog and Demand run Flyway migrations on startup and Hibernate validates both schemas. Messaging applies embedded numbered, checksum-verified SQL migrations to its own database. The migration and tenant constraints are documented in the [Catalog guide](docs/catalog.md).

## Ports and health checks

| Component | Default host port | Health / local URL |
| --- | ---: | --- |
| Frontend | 5173 | http://localhost:5173/ |
| Catalog REST | 8081 | http://localhost:8081/actuator/health |
| Catalog gRPC | 9091 | `stockping.catalog.v1.CatalogService` |
| Demand gRPC | Private container port 9092 | `stockping.demand.v1.DemandService` |
| Demand REST | 8082 | http://localhost:8082/actuator/health |
| Messaging | 8083 | http://localhost:8083/health |
| PostgreSQL | 5434 | `docker compose exec postgres pg_isready` with configured user/database |
| RabbitMQ AMQP | 5673 | Container `rabbitmq-diagnostics -q ping` |
| RabbitMQ management | 15673 | http://localhost:15673/ (credentials in `.env`) |

Override host ports in `.env` if occupied. Container ports remain fixed. PostgreSQL databases/users are `catalog_db`/`catalog_user`, `demand_db`/`demand_user`, and `messaging_db`/`messaging_user`. The bootstrap administrator is for infrastructure administration only. Each service role can connect to its own database and is denied connections to the other two service databases.

## Run applications independently

Build Java from the repository root, then run either jar in its own terminal:

```sh
mvn -B -ntp verify
# Export AUTH_JWT_SECRET for both services and each service database password.
# Native Java does not load root .env automatically; see docs/demand.md.
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
java -jar demand-service/target/demand-service-0.0.1-SNAPSHOT.jar
```

To build just one service: `mvn -B -ntp -pl catalog-service -am verify` (or `demand-service`). Override its HTTP port with `PORT=9001 java -jar ...`. Catalog also serves the Stage 1 REST/gRPC contracts. Only Actuator health is exposed for operations; environment/configuration endpoints remain hidden. Native Catalog accepts `CATALOG_DB_URL`, `CATALOG_DB_USER`, `CATALOG_DB_PASSWORD`, `AUTH_JWT_SECRET` and `CATALOG_GRPC_PORT`. Merchant identity comes from a validated token. Demand configuration and the explicit demo profile are documented in the [Demand guide](docs/demand.md).

```sh
cd frontend
npm ci
npm run dev
```

```sh
cd messaging-service
go run ./cmd/messaging
```

Messaging defaults to port 8083; `PORT` accepts integers from 1 to 65535. It uses structured JSON logs, HTTP timeouts and graceful shutdown. A native Go build/test including contract tests requires generating Go stubs first.

## Protobuf generation

```sh
# Java protobuf messages AND gRPC stubs; generated files go under contracts/java/target/.
mvn -B -ntp -pl contracts/java -am test

# Go protobuf messages AND gRPC stubs; generated files go under messaging-service/gen/.
./scripts/generate-proto.sh
```

The Go command builds a tool image with protoc 4.36.2, protoc-gen-go 1.36.12 and protoc-gen-go-grpc 1.6.2. The Java plugin uses the same protoc version and grpc-java 1.85.0. Generated sources are build artifacts; edit `.proto` files rather than generated code. The Stage 0 wire fixture/descriptor checks remain, with a new versioned Catalog contract and real generated-code client validation. See [contract details](contracts/README.md).

## Tests and CI

```sh
# All Java, frontend and Go tests/builds (Docker is required for PostgreSQL tests and Go).
./scripts/test.sh

# Read-only infrastructure/HTTP checks against the running developer Compose environment.
python3 scripts/smoke-test.py

# Acceptance validators. Each runs in its own disposable Compose project (see below) and never
# touches the developer databases, RabbitMQ queues, images or root .env.
python3 scripts/validate-catalog.py
python3 scripts/validate-demand.py
python3 scripts/validate-messaging.py
python3 scripts/validate-stage4.py
# Stage 5 AI query extraction and end-to-end WhatsApp Scenario A acceptance.
python3 scripts/validate-stage5.py

# Remove leftovers of an interrupted validation run. Only harness-generated stockping-validation-<name>-<run>
# projects match; projects with running containers are skipped unless --include-running is given.
python3 scripts/validation_support.py --cleanup
```

Validation isolation (`scripts/validation_support.py`, `IsolatedStack`):

- A unique `stockping-validation-<name>-<run>` Compose project with per-run synthetic credentials in a private `--env-file`; the root `.env` is never read and the process environment is scrubbed, so exported shell variables cannot leak in. The resolved configuration is asserted before anything starts.
- Images are built from the working tree as `stockping-validation-*:local` (cached layers are reused; set `STOCKPING_VALIDATION_SKIP_BUILD=1` to reuse existing ones). Developer `stockping-*` images are untouched.
- WhatsApp always targets the fake provider on the private network with real sending disabled and an empty allowlist. Customers use controlled synthetic numbers `+1999xxxxxxx` (an unassigned NANP area code); the fake provider rejects any other recipient and the run fails.
- The demo administrator and any second tenant get random per-run passwords; no credential or hash is shared with the developer stack. The AI provider is forced to `mock`.
- Teardown removes only the project's containers, network and volumes, so repeated runs leave nothing behind. `STOCKPING_VALIDATION_KEEP=1` keeps a failed stack for inspection.

Go tests include configuration validation, actual HTTP health/error requests, and generated contract compatibility; the runner also checks formatting, `go vet`, the race detector and compilation. Java integration tests use disposable PostgreSQL 18 and real HTTP/gRPC servers. They test CRUD, inventory transitions/audits, concurrency/rollback, validation, tenant isolation and the preserved health/configuration boundaries. Frontend tests cover login/logout, dashboard loading, product and inventory workflows, waitlist states and consent, notification dispatch-state presentation, AI preview, filtering, and session expiry. The Compose test checks all application boundaries, three owned PostgreSQL connections, all six denied cross-service database connections, and authenticated RabbitMQ management access.

GitHub Actions runs these checks on push/PR, including a full Compose startup. Catalog regressions remain covered; Stage 2 adds JWT security, Demand persistence/concurrency, consent, tenant isolation and a real gRPC client. Stage 3 adds signed webhook, PostgreSQL duplicate/concurrency, provider-adapter and real Go → Java validation. Stage 4 adds transactional outboxes, notification idempotency, template safety gates and isolated real-broker acceptance. [Engineering requirements](docs/engineering-quality.md) define the feature-specific test gates for subsequent stages. [Stage 0 evidence](docs/stage-0-validation.md) is preserved; [Stage 1 evidence](docs/stage-1-validation.md) records the Catalog slice; [Stage 2 evidence](docs/stage-2-validation.md) records Demand.


## WhatsApp setup

Provider integration is disabled until official Cloud API credentials, channel IDs, an explicit Graph version and trusted merchant mapping are set in ignored `.env`. The initializer generates separate internal service credentials. Real acceptance needs an official number and temporary HTTPS callback ending `/webhooks/whatsapp`. Follow the [Messaging setup procedure](docs/messaging.md#real-whatsapp-acceptance-procedure). Automated tests and the local validator require no Meta credentials.

## Automatic stock notifications

Inventory updates use the existing authenticated Catalog PATCH endpoint. A zero-to-positive change creates the availability event in the same transaction; there is no notification button. Catalog and Demand relays start by default in Compose and tolerate broker downtime. Messaging leaves automatic requests queued until `STOCK_NOTIFICATIONS_ENABLED=true` and a template is configured. `/health/notifications` reports the configuration gate without exposing credentials; `/health` remains the database health check.

Set `WHATSAPP_STOCK_TEMPLATE_NAME`, `WHATSAPP_STOCK_TEMPLATE_LANGUAGE`, and an ordered `WHATSAPP_STOCK_TEMPLATE_PARAMETERS` list containing approved positional body fields (`product_name`, `sku`, `variant_description`). Names are never assumed approved. Intentional real-provider testing also requires `WHATSAPP_STOCK_REAL_SEND_ENABLED=true` and `WHATSAPP_STOCK_ALLOWED_RECIPIENTS` containing authorized international phone numbers. Do not enable these for automated tests. Local acceptance configures only a synthetic fake-provider template and leaves `.env` and normal volumes untouched.

`GET /api/notifications` returns the authenticated merchant's latest 100 job submission outcomes. Provider delivery history remains in Messaging; `ACCEPTED` means provider submission, not delivery. See [delivery guarantees, parked rows and dead-letter recovery](docs/stage-4-validation.md).

## AI-Assisted Customer Inquiry Processing

In Stage 5, Demand Service introduces constrained natural-language processing for unstructured WhatsApp inquiries (e.g., `"machan s25 ultra black 512 thiyenawada?"`). Rather than introducing heavy AI microservices, vector databases, or complex RAG infrastructure, StockPing uses a lightweight, resilient extraction pipeline.

### How It Works

1. **Deterministic Filter First**: Exact commands (`HELP`, `STOP`, `STOCK <SKU>`) bypass AI entirely, preserving sub-millisecond deterministic guarantees.
2. **Pluggable AI Extractor**: Free-text queries are routed through `AiExtractorService`.
   - **Google Gemini 2.5 Flash**: If `STOCKPING_AI_API_KEY` is provided, inquiries may be parsed via the Google Gemini REST API using structured JSON schema output to identify intent (`CHECK_STOCK`, `JOIN_WAITLIST`, `OPT_OUT`, `HELP`), product model, and variant attributes (color, storage capacity, size).
   - **Rule-Based & Singlish Fallback**: If the API key is absent, disabled, rate-limited, or times out (3000ms threshold), the engine falls back to a deterministic, zero-dependency `RuleBasedAiExtractor` capable of parsing English and colloquial Singlish expressions. Preview results should be treated as rule-based or configuration-dependent, not as guaranteed Gemini output.
3. **Authoritative Catalog Resolution**: The extracted model and attributes are searched against Catalog Service via internal gRPC. Authoritative stock numbers are verified directly from `catalog_db`. AI never invents or guesses stock levels.
4. **Conversational Waitlist Follow-Up**: If the requested variant is out of stock, Demand formats a helpful WhatsApp response and caches the query in a thread-safe in-memory cache (15-minute TTL). The customer can simply reply `"YES"` or `"WAITLIST <SKU>"` to be placed on the waitlist.
5. **Zero-PII & Safety**: Phone numbers and merchant secrets are never sent to external AI providers. AI output is strictly untrusted and cannot directly alter inventory, change prices, mutate permissions, or record consent without explicit customer action.
6. **Merchant AI Assistant UI**: Merchants can preview and test query extraction in the React frontend (`http://localhost:5173/` -> **AI Assistant** tab), inspecting parsed attributes, catalog matches, and suggested WhatsApp replies.

### AI Configuration

| Variable | Default | Purpose |
|---|---|---|
| `STOCKPING_AI_ENABLED` | `true` | Enable or disable natural-language query processing |
| `STOCKPING_AI_PROVIDER` | `GEMINI` | `GEMINI` for LLM, `NONE` or `MOCK` for deterministic fallback |
| `STOCKPING_AI_API_KEY` | `""` | Google Gemini API key (optional; system falls back safely if omitted) |
| `STOCKPING_AI_MODEL` | `gemini-2.5-flash` | Gemini model identifier |
| `STOCKPING_AI_TIMEOUT_MS` | `3000` | Upstream provider timeout before fallback activation |

## Merchant Application & Frontend Experience

StockPing provides a production-grade merchant interface and public landing page built with React 19, TypeScript, Material UI 9, and Vite, styled in a restrained contemporary retail operations design language (warm off-white canvas, deep ink typography, muted teal accents, soft amber alerts).

### Screens & Features

1. **Public Landing Page (`/`)**:
   - Original vector brand identity (inventory bar chart signal with restock notification ping).
   - Core value proposition: *"Out of stock shouldn't mean out of touch."*
   - Interactive live product demonstration card with real in-stock/out-of-stock and waiting indicators.
   - Five-step operational walkthrough and comprehensive feature matrix.
   - Direct merchant sign-in navigation.
2. **Merchant Sign-In (`/login`)**:
   - Secure login form with in-memory credential management (no passwords or tokens in `localStorage`/`sessionStorage`).
   - Server-side error handling and auto-expiring session timers matching JWT validity.
3. **Merchant Dashboard (`/app`)**:
   - Real-time KPI summaries populated directly from `GET /api/analytics/demand`: Active Requests, Waiting Customers, Tracked Variants, and Top Demanded Variant.
   - Demand ranking breakdown of top variants by active customer interest.
   - Quick action shortcuts to primary merchant workflows.
4. **Products & Inventory (`/app/products`)**:
   - Searchable product catalog with brand and name filtering.
   - Product creation modal (Name, Brand, Category, Status).
   - Variant management with SKU, pricing, and dynamic key-value attributes.
   - In-modal stock updates communicating restock trigger events to waiting customers.
5. **Customer Waitlists (`/app/waitlists`)**:
   - Status filters (`WAITING`, `NOTIFIED`, `FULFILLED`, etc.) and variant UUID filters.
   - Masked customer phone numbers (`+94•••••4567`) preserving merchant privacy.
   - Dedicated consent indicators (`Opted in`, `Opted out`, `Not recorded`).
   - Merchant-assisted waitlist registration requiring explicit customer consent confirmation.
   - Safe customer opt-out modal with explicit confirmation.
6. **Notification History (`/app/notifications`)**:
   - Audit trail of the recent 100 notification jobs from `GET /api/notifications`.
   - Dispatch job states include `QUEUED`, `AUTHORIZED`, `ACCEPTED`, `FAILED`, and `SKIPPED`.
   - `ACCEPTED` means provider submission; provider `SENT`, `DELIVERED`, and `READ` receipts are not exposed by this UI.
7. **AI Stock Query Assistant (`/app/ai`)**:
   - Real-time preview console testing natural language customer WhatsApp messages.
   - One-click sample queries including English, mixed-language, and colloquial inquiries.
   - Extraction inspector showing recognized Brand, Model, Product Phrase, and attributes.
   - Authoritative catalog resolution match card with real-time stock levels.
   - Confidence meter and suggested WhatsApp reply.

### Running Frontend Locally

```sh
cd frontend
npm ci
npm test        # Runs all 39 unit and integration tests
npm run build   # Verifies TypeScript types and creates optimized production bundle
npm run dev     # Starts local Vite development server on http://localhost:5173
```

### Local Demo Sign-In

1. Initialize local environment: `python3 scripts/init-local-env.py`.
2. Inspect your private `.env` to locate `DEMO_ADMIN_PASSWORD` (never commit or expose this value).
3. Open `http://localhost:5173/login`.
4. Sign in with:
   - **Email**: `admin@demo.stockping.local` (or value of `DEMO_ADMIN_EMAIL`)
   - **Password**: value from `DEMO_ADMIN_PASSWORD` in your private `.env`

### Known Limitations

- **In-Memory Sessions**: Authentication credentials/tokens are held in memory; browser refresh requires reauthentication.
- **Waitlist Registration**: Merchant-assisted registration currently requires a variant UUID; a product/variant picker is deferred.
- **Provider Receipts**: Notifications exposes dispatch job states such as `ACCEPTED`, but not provider delivery receipts.
- **AI Preview**: Inquiry previews may use rule-based interpretation; Gemini behavior is configuration-dependent.
- **Product Success Feedback**: Product-success feedback is implemented and covered by automated tests, but has not been independently verified in the browser.

## Portfolio Demonstration Walkthrough

StockPing can be demonstrated in 5 minutes using the local development stack:

1. **Merchant Sign-In**:
   - Navigate to `http://localhost:5173`.
   - Log in with `admin@demo.stockping.local` using the `DEMO_ADMIN_PASSWORD` generated in your local `.env`.
2. **Catalog & Inventory Setup**:
   - Inspect existing products or create a variant (e.g., `Samsung Galaxy S25 Ultra 512GB Black`).
   - Ensure the variant has an initial stock of `0` (Out of Stock).
3. **Customer WhatsApp Inquiry (Natural Language)**:
   - Simulate an incoming customer message via the signed webhook or `scripts/validate-stage5.py`:
     ```text
     "machan s25 ultra black 512 thiyenawada?"
     ```
   - StockPing extracts the model and attributes, queries Catalog gRPC, detects 0 stock, and replies:
     ```text
     "Samsung Galaxy S25 Ultra (512GB Black) is currently out of stock. Would you like to join the waitlist? Reply YES."
     ```
4. **Conversational Waitlist Registration**:
   - The customer replies `"YES"`.
   - Demand records `OPT_IN` consent, stores the customer record, and transitions the waitlist to `WAITING`.
   - The merchant refreshes the **Waitlists** tab on the React dashboard to see the customer registered.
5. **Restock Transition**:
   - The merchant updates inventory from `0` to `10` via the authenticated Catalog endpoint:
     `PATCH http://localhost:8081/api/variants/{variantId}/inventory` with body `{"quantity": 10}`.
   - Catalog acquires a row-level lock, records a stock audit event, and atomically writes to `event_outbox`.
6. **Automated Notification Dispatch**:
   - Catalog outbox publishes `inventory.stock_available.v1` to RabbitMQ.
   - Demand consumes the event, matches the `WAITING` waitlist entry, and publishes `notification.requested.v1`.
   - Messaging consumes the request, re-validates customer consent and stock over gRPC, and sends the back-in-stock WhatsApp template.
7. **Delivery Reconciliation**:
   - Provider delivery status updates (`SENT` -> `DELIVERED` -> `READ`) reconcile in `messaging_db`.
   - The waitlist entry automatically transitions to `NOTIFIED` on the merchant dashboard.
8. **Customer Opt-Out**:
   - The customer sends `"STOP"`.
   - Demand records `OPT_OUT` consent and atomically cancels all active waitlist subscriptions.
