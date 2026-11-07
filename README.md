# StockPing

StockPing is a software engineering portfolio project for capturing retail demand through WhatsApp and notifying consenting customers when an exact product variant returns to stock.

**Current implementation: Stage 3 — WhatsApp + Go/gRPC integration; real-provider acceptance pending.** Messaging validates signed Cloud API webhooks, persists/deduplicates messages, calls authenticated Demand gRPC, and sends immediate replies. Demand supports `HELP`, `STOP`, and `STOCK <exact SKU>` through Catalog gRPC. Existing auth, consent, waitlists and frontend remain. See the [Messaging guide](docs/messaging.md), [Stage 3 validation](docs/stage-3-validation.md), [Demand guide](docs/demand.md), and [Catalog guide](docs/catalog.md). The [StockPing SRS v1.1](docs/StockPing_SRS_v1.1.docx) defines the baseline and staged boundaries.

## Architecture and technologies

The system has three backend services. Catalog owns products and authoritative inventory; Demand owns merchant authentication, customers, consent and waitlists; Messaging owns WhatsApp transport and delivery state. React + TypeScript + Material UI provides the merchant interface.

Browser/external boundaries use HTTP/REST. Messaging → Demand and Demand → Catalog use generated gRPC/Protocol Buffers with authenticated internal calls. One PostgreSQL server hosts three service-owned databases: catalog_db, demand_db and messaging_db. RabbitMQ remains infrastructure only; business events are deferred to Stage 4.

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
│   └── events/                # Reserved for Stage 4
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

# Infrastructure/HTTP checks against the running Compose environment.
python3 scripts/smoke-test.py
python3 scripts/validate-catalog.py
python3 scripts/validate-demand.py
python3 scripts/validate-messaging.py
```

Go tests include configuration validation, actual HTTP health/error requests, and generated contract compatibility; the runner also checks formatting, `go vet`, the race detector and compilation. Java integration tests use disposable PostgreSQL 18 and real HTTP/gRPC servers. They test CRUD, inventory transitions/audits, concurrency/rollback, validation, tenant isolation and the preserved health/configuration boundaries. Frontend tests cover login, waitlist states, filtering, logout, expiry and confirmed opt-out. The Compose test checks all application boundaries, three owned PostgreSQL connections, all six denied cross-service database connections, and authenticated RabbitMQ management access.

GitHub Actions runs these checks on push/PR, including a full Compose startup. Catalog regressions remain covered; Stage 2 adds JWT security, Demand persistence/concurrency, consent, tenant isolation and a real gRPC client. Stage 3 adds signed webhook, PostgreSQL duplicate/concurrency, provider-adapter and real Go → Java validation. RabbitMQ business events remain deferred. [Engineering requirements](docs/engineering-quality.md) define the feature-specific test gates for subsequent stages. [Stage 0 evidence](docs/stage-0-validation.md) is preserved; [Stage 1 evidence](docs/stage-1-validation.md) records the Catalog slice; [Stage 2 evidence](docs/stage-2-validation.md) records Demand.


## WhatsApp setup

Provider integration is disabled until official Cloud API credentials, channel IDs, an explicit Graph version and trusted merchant mapping are set in ignored `.env`. The initializer generates separate internal service credentials. Real acceptance needs an official number and temporary HTTPS callback ending `/webhooks/whatsapp`. Follow the [Messaging setup procedure](docs/messaging.md#real-whatsapp-acceptance-procedure). Automated tests and the local validator require no Meta credentials.
