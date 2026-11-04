package io.aiexchange.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.json.JsonParserFactory;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatusApiTest {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void healthIsUp() throws Exception {
        var response = get("/actuator/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("json"));
        assertEquals("UP", JsonParserFactory.getJsonParser().parseMap(response.body()).get("status"));
    }

    @Test void statusDescribesScaffoldWithoutClaimingSimulation() throws Exception {
        var response = get("/api/v1/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("json"));
        assertEquals(java.util.Map.of("name", "AI Exchange", "stage", "scaffold", "simulationAvailable", false),
                JsonParserFactory.getJsonParser().parseMap(response.body()));
    }

    @Test void plannedRunApiIsNotExposed() throws Exception {
        assertEquals(404, get("/api/v1/runs").statusCode());
    }
}
