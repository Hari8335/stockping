package dev.stockping.catalog;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One disposable real PostgreSQL database per Maven test JVM; never a developer database. */
@org.junit.jupiter.api.Tag("postgres")
abstract class PostgresTest {
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18-alpine");
    static { DATABASE.start(); }
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
        properties.add("catalog.grpc-port", () -> 0);
        properties.add("catalog.demo-merchant-id", () -> "11111111-1111-1111-1111-111111111111");
    }
}
