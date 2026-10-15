# Engineering and quality requirements

These requirements apply throughout StockPing implementation. A stage is complete only when its relevant automated tests pass; a running application alone is insufficient.

Use conventional package layouts with focused names, methods and classes. Keep controllers thin; put business rules in service/domain layers and persistence operations in repository/data-access components. Use DTOs at external boundaries. Apply SOLID where it improves maintainability and avoid speculative abstractions and duplicated logic.

Validate all external input and use consistent error handling. Enforce service database ownership, SRS tenant isolation, authorization, idempotency and consent rules on the server. Add migrations with the first persistent domain tables. Keep configuration external and secrets out of Git. Use structured logging without secrets or unnecessary customer data. Keep architecture and setup documentation synchronized.

Add tests with each feature: unit tests for business rules; repository/database tests for important queries, constraints and tenant scoping; API/gRPC integration tests for important boundaries; RabbitMQ integration tests for delivery, duplicates, retries and dead-lettering when messaging is introduced. Mock WhatsApp/LLM providers where appropriate. Add end-to-end tests for critical StockPing workflows as those workflows become available. Run all existing tests after changes and leave no known failures.

CI checks grow with the implementation. Prefer the smallest professional design that satisfies the current SRS stage. No Stage 1+ behavior is implied by the Stage 0 infrastructure or probe contract.

