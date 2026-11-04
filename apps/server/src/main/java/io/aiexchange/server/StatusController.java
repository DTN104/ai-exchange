package io.aiexchange.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    public record ServiceStatus(String name, String stage, boolean simulationAvailable) {}

    @GetMapping("/api/v1/status")
    public ServiceStatus status() {
        return new ServiceStatus("AI Exchange", "scaffold", false);
    }
}
