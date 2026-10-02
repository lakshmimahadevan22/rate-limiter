package dev.ratelimiter.gateway;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FallbackController {

    @RequestMapping(value = "/fallback/demo", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> demoBackendFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "service", "demo-backend",
                        "status", "degraded",
                        "message", "demo-backend is currently unavailable; please retry shortly"));
    }
}
