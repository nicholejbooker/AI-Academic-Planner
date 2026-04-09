package com.aiplanner.backend.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Map<String, UserProfile> USERS = Map.of(
            "admin", new UserProfile("admin", "admin123", "admin", "demo-admin"),
            "student", new UserProfile("student", "student123", "student", "demo-student"));

    private final Map<String, SessionProfile> sessionsByToken = new ConcurrentHashMap<>();

    public Map<String, Object> login(String username, String password) {
        UserProfile profile = USERS.get(username);
        if (profile == null || !profile.password().equals(password)) {
            throw new IllegalArgumentException("Invalid username or password.");
        }

        String token = UUID.randomUUID().toString();
        SessionProfile session = new SessionProfile(
                token,
                profile.username(),
                profile.role(),
                profile.userId(),
                Instant.now().toString());
        sessionsByToken.put(token, session);

        return Map.of(
                "token", token,
                "username", session.username(),
                "role", session.role(),
                "userId", session.userId(),
                "issuedAt", session.issuedAt());
    }

    public Map<String, Object> validateSession(String token) {
        SessionProfile session = sessionsByToken.get(token);
        if (session == null) {
            throw new IllegalArgumentException("Session is invalid or expired. Please log in again.");
        }

        return Map.of(
                "username", session.username(),
                "role", session.role(),
                "userId", session.userId(),
                "issuedAt", session.issuedAt());
    }

    public boolean isAdmin(String token) {
        SessionProfile session = sessionsByToken.get(token);
        return session != null && "admin".equals(session.role());
    }

    private record UserProfile(String username, String password, String role, String userId) {
    }

    private record SessionProfile(String token, String username, String role, String userId, String issuedAt) {
    }
}
