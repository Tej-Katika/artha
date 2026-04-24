package com.artha.agent.api;

import com.artha.agent.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health check endpoint.
 * First thing to test after starting the app:
 *   curl http://localhost:8080/api/health
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            // Verify database connection
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long userCount = userRepository.count();

            return ResponseEntity.ok(Map.of(
                "status", "ok",
                "database", "connected",
                "timestamp", Instant.now().toString(),
                "users_in_db", userCount
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "error",
                "database", "disconnected",
                "error", e.getMessage()
            ));
        }
    }
}
