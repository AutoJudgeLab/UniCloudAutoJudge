package org.university.judge.api.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "API process availability")
public class HealthController {

    @GetMapping
    @Operation(summary = "Check API availability",
            description = "Reports process availability. Use /actuator/health for dependency health.")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }
}
