package com.finwise.agent.api;

import com.finwise.agent.domain.User;
import com.finwise.agent.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * User management endpoints.
 *
 * POST /api/users          - create a user
 * GET  /api/users          - list all users
 * GET  /api/users/{id}     - get one user
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            return ResponseEntity.badRequest().build();
        }
        User user = new User();
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        return ResponseEntity.ok(userRepository.save(user));
    }

    @GetMapping
    public ResponseEntity<List<User>> listUsers() {
        return ResponseEntity.ok(userRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUser(@PathVariable UUID id) {
        return userRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    public record CreateUserRequest(String email, String fullName) {}
}
