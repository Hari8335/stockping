# Stage 0 validation

Validated locally on 15 October 2026 (Asia/Colombo), on macOS ARM64 with Docker Desktop / Linux ARM64 containers. Work is limited to Stage 0. The branch remains `main`; no commits or branches were created. The pre-existing empty nested Git repository is preserved and excluded from the application checkout.

## Completed checks

| Check | Result |
| --- | --- |
| `./scripts/test.sh` | Passed end to end against the final sources |
| `mvn -B -ntp verify` | Passed: 5 Java tests (1 protobuf compatibility test, 2 HTTP tests per service), generated Java messages/gRPC stubs, both executable jars |
| Frontend `npm ci`, `npm test`, `npm run build` | Passed: 1 rendered placeholder test, strict TypeScript compilation and Vite production build |
| `./scripts/generate-proto.sh` | Passed: pinned Docker toolchain generated Go messages and gRPC stubs |
| Go formatting, `go vet ./...`, `go test -race ./...`, `go build` | Passed: all 3 test packages (configuration, generated contracts, HTTP); runtime binary built |
| `docker compose config --quiet` | Passed |
| All 4 application Docker builds | Passed; service tests run during image builds |
| `docker compose up --build --wait --wait-timeout 180` | Passed; all 6 containers healthy |
| `python3 scripts/smoke-test.py` | Passed: 4 HTTP boundaries, 3 owned database connections, 6 denied cross-service connections, authenticated RabbitMQ management API |
| Shell syntax, new-file whitespace and relative documentation links | Passed |

The shared Java and Go wire fixture is `0a0973746f636b70696e67` for `ProbeRequest(message="stockping")`. Both generated RPC descriptors name `stockping.skeleton.v1.SkeletonProbe/Check`. Generated code compiles in both languages. The probe has no deployed server; runtime gRPC service integration starts with Stage 1.

Compose smoke output:

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

## Setup issues resolved during validation

- The Spring Boot health test now reads the JSON status field and permits standard health-group metadata.
- Material UI styling uses its current `sx` API. The frontend test DOM dependency is locked to a Node 24.14-compatible release; the final installation reports no dependency vulnerabilities.
- The Go test runner uses writable temporary module/build caches when running as the host user.
- RabbitMQ diagnostics run as the RabbitMQ user, preventing root-owned cookie creation during startup. A fresh broker volume was verified successfully after this change. A stable hostname preserves the broker node identity across recreation.
- A locked Maven build cache shares downloads and serializes Java builds. The HTTP integration tests use a bounded 30-second request timeout to tolerate constrained development build resources. Both Java image builds then passed.

## Limits and remaining manual steps

No application build, test or local startup error remains in the checks above. The environment is left running at the ports in the [README](../README.md). The ignored local `.env` contains example-only development credentials and has mode 0600.

GitHub Actions was authored and its underlying commands were exercised locally. It has not run on GitHub; the owner must commit/push the repository to trigger it. No Git commit or remote publication was performed.

No browser was connected for visual inspection. The frontend was verified by its rendered component test, production build, live HTML and served React module. No business workflow, database domain table, actual gRPC server/client call, event producer/consumer, WhatsApp or LLM integration exists in Stage 0. Their relevant tests are required with their implementation stages.

## Created file inventory

Generated targets, node_modules, Go stubs and the private local `.env` are excluded. The original SRS was copied unchanged into docs for a portable baseline.

```text
.dockerignore
.env.example
.github/workflows/ci.yml
.gitignore
README.md
catalog-service/Dockerfile
catalog-service/pom.xml
catalog-service/src/main/java/dev/stockping/catalog/CatalogApplication.java
catalog-service/src/main/resources/application.yml
catalog-service/src/test/java/dev/stockping/catalog/HealthEndpointTest.java
contracts/README.md
contracts/events/README.md
contracts/java/pom.xml
contracts/java/src/test/java/dev/stockping/contracts/ProbeContractTest.java
contracts/proto/stockping/skeleton/v1/probe.proto
demand-service/Dockerfile
demand-service/pom.xml
demand-service/src/main/java/dev/stockping/demand/DemandApplication.java
demand-service/src/main/resources/application.yml
demand-service/src/test/java/dev/stockping/demand/HealthEndpointTest.java
docker-compose.yml
docs/StockPing_SRS_v1.1.docx
docs/architecture.md
docs/engineering-quality.md
docs/stage-0-validation.md
frontend/.dockerignore
frontend/Dockerfile
frontend/index.html
frontend/package-lock.json
frontend/package.json
frontend/src/App.test.tsx
frontend/src/App.tsx
frontend/src/main.tsx
frontend/src/test/setup.ts
frontend/tsconfig.json
frontend/vite.config.ts
infra/postgres/init-databases.sh
messaging-service/Dockerfile
messaging-service/cmd/messaging/main.go
messaging-service/go.mod
messaging-service/go.sum
messaging-service/internal/config/config.go
messaging-service/internal/config/config_test.go
messaging-service/internal/contracts/probe_test.go
messaging-service/internal/http/handler.go
messaging-service/internal/http/handler_test.go
pom.xml
scripts/generate-proto.sh
scripts/smoke-test.py
scripts/test.sh
```
