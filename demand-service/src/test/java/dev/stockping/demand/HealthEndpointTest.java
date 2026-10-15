package dev.stockping.demand;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {
    @LocalServerPort
    private int port;

    @Test
    void healthIsAvailableOverHttp() throws Exception {
        var response = get("/actuator/health");
        assertEquals(200, response.statusCode());
        assertEquals("UP", JsonPath.<String>read(response.body(), "$.status"));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/vnd.spring-boot.actuator"));
    }

    @Test
    void configurationEndpointsAreNotExposed() throws Exception {
        assertEquals(404, get("/actuator/env").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
