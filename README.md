# StockPing

StockPing is a software engineering portfolio project for capturing retail demand through WhatsApp and notifying consenting customers when an exact product variant returns to stock.

**Current implementation: Stage 0 — Project Skeleton only.** The frontend is a placeholder, the backend applications expose health endpoints, and the infrastructure and contract-generation builds are ready. No business workflows are implemented. The supplied [StockPing SRS v1.1](docs/StockPing_SRS_v1.1.docx) defines the project baseline and incremental stages.

## Architecture and technologies

The planned system has three backend services. Catalog owns products and authoritative inventory; Demand owns merchant authentication, customers, consent and waitlists; Messaging owns WhatsApp transport and delivery state. React + TypeScript + Material UI provides the merchant interface.

Browser/external boundaries use HTTP/REST. Internal synchronous calls will use gRPC/Protocol Buffers (Messaging → Demand → Catalog). Asynchronous events will use RabbitMQ. One PostgreSQL server hosts three service-owned databases. Applications do not connect to a database or broker in Stage 0; each application can start independently. No gRPC server is deployed yet.

The skeleton uses Java 21, Spring Boot 4.1.1, Maven, Go 1.26.8, Node.js 24, Vite, PostgreSQL 18 and RabbitMQ 4 management. [Architecture decisions](docs/architecture.md) explain the small generation-only contract and database isolation.

```text
stockping/
├── frontend/                  # Vite, React, TypeScript, MUI, page test
├── catalog-service/           # Spring Boot app and HTTP integration tests
├── demand-service/            # Spring Boot app and HTTP integration tests
├── messaging-service/         # Go cmd/, internal/, generated gen/ (ignored)
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
cp .env.example .env
chmod 600 .env
docker compose config --quiet
docker compose up --build --wait --wait-timeout 180
python3 scripts/smoke-test.py
```

The example passwords are local development examples. Set private values in the ignored `.env`. Do not publish that file or resolved Compose configuration. All published ports bind to loopback. Images run application processes as unprivileged users. Initial builds run the relevant service tests and can take several minutes.

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

Named volumes preserve PostgreSQL and RabbitMQ data across restarts. Database creation scripts run only on the first initialization of an empty PostgreSQL volume. Changing passwords in `.env` does not change existing database roles; use SQL to rotate them or deliberately reset a disposable local volume. No domain tables or migrations exist yet. Migrations become mandatory when persistent domain tables are introduced.

## Ports and health checks

| Component | Default host port | Health / local URL |
| --- | ---: | --- |
| Frontend | 5173 | http://localhost:5173/ |
| Catalog | 8081 | http://localhost:8081/actuator/health |
| Demand | 8082 | http://localhost:8082/actuator/health |
| Messaging | 8083 | http://localhost:8083/health |
| PostgreSQL | 5434 | `docker compose exec postgres pg_isready` with configured user/database |
| RabbitMQ AMQP | 5673 | Container `rabbitmq-diagnostics -q ping` |
| RabbitMQ management | 15673 | http://localhost:15673/ (credentials in `.env`) |

Override host ports in `.env` if occupied. Container ports remain fixed. PostgreSQL databases/users are `catalog_db`/`catalog_user`, `demand_db`/`demand_user`, and `messaging_db`/`messaging_user`. The bootstrap administrator is for infrastructure administration only. Each service role can connect to its own database and is denied connections to the other two service databases.

## Run applications independently

Build Java from the repository root, then run either jar in its own terminal:

```sh
mvn -B -ntp verify
java -jar catalog-service/target/catalog-service-0.0.1-SNAPSHOT.jar
java -jar demand-service/target/demand-service-0.0.1-SNAPSHOT.jar
```

To build just one service: `mvn -B -ntp -pl catalog-service -am verify` (or `demand-service`). Override its HTTP port with `PORT=9001 java -jar ...`. Both services expose only Actuator health; environment/configuration endpoints are not exposed.

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

The Go command builds a tool image with protoc 4.36.2, protoc-gen-go 1.36.12 and protoc-gen-go-grpc 1.6.2. The Java plugin uses the same protoc version and grpc-java 1.85.0. Generated sources are build artifacts; edit `.proto` files rather than generated code. Both languages test the same wire fixture and generated RPC method descriptor. See [contract details](contracts/README.md).

## Tests and CI

```sh
# All Java, frontend and Go tests/builds (Go runs in Docker).
./scripts/test.sh

# Infrastructure/HTTP checks against the running Compose environment.
python3 scripts/smoke-test.py
```

Go tests include configuration validation, actual HTTP health/error requests, and generated contract compatibility; the runner also checks formatting, `go vet`, the race detector and compilation. Java integration tests start real HTTP servers and verify health plus non-exposure of configuration endpoints. The frontend test checks the rendered placeholder. The Compose test checks all application boundaries, three owned PostgreSQL connections, all six denied cross-service database connections, and authenticated RabbitMQ management access.

GitHub Actions runs these checks on push/PR, including a full Compose startup. Stage 0 has no business logic, repositories, message handlers, external provider adapters or critical business workflow to test yet. [Engineering requirements](docs/engineering-quality.md) define the feature-specific test gates for subsequent stages. [Validation evidence](docs/stage-0-validation.md) records local results and limitations.

