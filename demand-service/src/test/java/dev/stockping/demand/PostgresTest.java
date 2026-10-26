package dev.stockping.demand;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@org.junit.jupiter.api.Tag("postgres")
abstract class PostgresTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18-alpine");
    static final TestCatalog CATALOG = new TestCatalog();
    static { DATABASE.start(); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("auth.jwt-secret", () -> TestTokens.SECRET);
        properties.add("catalog.grpc-host", () -> "localhost");
        properties.add("catalog.grpc-port", CATALOG::port);
        properties.add("catalog.grpc-timeout-ms", () -> 1000);
    }
}
