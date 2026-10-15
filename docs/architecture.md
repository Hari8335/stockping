# Stage 0 architecture decisions

The supplied SRS v1.1 is the implementation baseline. Only its Stage 0 is implemented here.

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

