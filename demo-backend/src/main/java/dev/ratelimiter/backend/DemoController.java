package dev.ratelimiter.backend;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("service", "demo-backend", "message", "Hello from the demo backend");
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
        return Map.of("service", "demo-backend", "echo", body);
    }
}