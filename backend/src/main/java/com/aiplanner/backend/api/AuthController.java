package com.aiplanner.backend.api;

import com.aiplanner.backend.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody @Valid LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/session")
    public Map<String, Object> session(@RequestBody @Valid SessionRequest request) {
        return authService.validateSession(request.token());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> handleAuthError(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record SessionRequest(@NotBlank String token) {
    }
}
