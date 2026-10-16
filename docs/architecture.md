# Architecture decisions

The supplied SRS v1.1 is the implementation baseline. Stages 0 and 1 are implemented here. The Stage 0 decisions below remain as historical context; the Stage 1 changes follow them.

- Use Java 21 and stable Spring Boot 4.1.1, with one root Maven reactor and independently packaged applications. The shared Java contract module is build tooling; it is not another service. No shared persistence module exists.
- Use Spring MVC and Actuator only. Add validation, security, persistence/migrations, RabbitMQ adapters and actual gRPC transports when the relevant vertical slice needs them. Empty layer packages and speculative repositories/controllers provide no value at this stage.
- Use Go's standard library for HTTP, configuration and JSON structured logging. `cmd/messaging` owns startup/shutdown; `internal/http` owns HTTP responses; `internal/config` validates the port. Generated protobuf/gRPC dependencies exist solely for the contract build/test at this stage.
- Use Vite and Material UI for a single placeholder. The local frontend container runs Vite after verifying tests and the production build. It is a development container, not a public deployment design. Source changes require a rebuild unless running native Vite.
- Use one PostgreSQL 18 instance, three empty logical databases, three non-superuser owner roles and a separate bootstrap administrator. Revoke default public database connection grants. Never give application services the administrator password. Stage 0 applications do not use the stores yet.
- Use RabbitMQ's management image, stable node hostname and persistent volume. Run its health diagnostics as the RabbitMQ user to avoid creating a root-owned Erlang cookie during slow startup. No application queue, exchange, producer, consumer or provider integration is introduced before the event slice.
- Use one generation-only versioned probe contract. Generate Java automatically with Maven and Go with a pinned Docker toolchain; do not require a host protoc installation. Generated code is ignored and regenerated from source.
- Bind published ports to loopback and keep example credentials outside application code. Port 5434 avoids the existing local PostgreSQL mapping at 5433. Applications have no artificial dependency on unused infrastructure.
- Add initial GitHub Actions checks now because builds, tests, contract generation and Compose smoke checks are already meaningful. Deployment remains Stage 7.

Future boundaries follow the SRS: Messaging → Demand gRPC; Demand → Catalog gRPC; Catalog restock and Demand outbound-message events through RabbitMQ; each service owns its own database. Authentication, tenant ownership, consent and idempotency must be implemented with their features and tested before those features are complete.


## Stage 1 Catalog slice

- Add conventional controller/DTO, application service, domain, repository and gRPC packages in Catalog. Both transports reuse one application service; no shared persistence or speculative orchestration layers are introduced.
- Use Spring Data JPA and PostgreSQL JSONB for simple variant attributes. Flyway owns the schema; Hibernate validates it. Merchant-aware unique/composite foreign-key constraints protect tenant ownership inside catalog_db. Price is optional exact decimal catalogue metadata, without financial workflows.
- Create inventory at zero with the variant. Derive availability from quantity and serialize updates with a PostgreSQL row lock. Persist each real change and its StockEvent atomically. Expose the domain's restock predicate without publishing messages yet.
- Use a replaceable MerchantContextProvider backed by server configuration for a single demo merchant per instance. Internal RPC merchant assertions must equal this context. This stage is private local development, with no authentication or public deployment claim. Stage 2 must establish authenticated and trusted internal tenant context.
- Add a small stockping.catalog.v1 contract and a lifecycle-managed grpc-java Netty listener. Keep the Stage 0 probe and its tests. Manage protobuf runtime explicitly at the root to match protoc; Spring Boot's transitive runtime default was older than the pinned generated code.
- Test against disposable real PostgreSQL 18 using Testcontainers, including TCP REST/gRPC, tenant access, constraints, concurrent writes and audit failure rollback. Image builds run unit/contract tests; the full host/CI suite runs PostgreSQL-tagged integration tests. The existing Stage 0 health assertions remain intact.
- Keep frontend, Demand and Messaging business behavior unchanged. A standalone Go script validates generated client interoperability; it is not a production service client. RabbitMQ remains running infrastructure with no business producers/consumers.

The [Catalog guide](catalog.md) documents the schema, ports, error/search semantics, temporary context and validation commands. [Stage 1 validation](stage-1-validation.md) records current evidence.
