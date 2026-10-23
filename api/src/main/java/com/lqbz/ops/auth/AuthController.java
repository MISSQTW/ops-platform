package com.lqbz.ops.auth;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SessionService sessions;

    private final boolean enabled;

    public AuthController(SessionService sessions, @Value("${ops.auth.enabled:true}") boolean enabled) {
        this.sessions = sessions;
        this.enabled = enabled;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return com.lqbz.ops.compat.Java8.map("enabled", enabled);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        String token = sessions.login(request.username(), request.password());
        if (token == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        return com.lqbz.ops.compat.Java8.map("token", token, "username", sessions.username(), "displayName", sessions.displayName(), "canManageIntegrations", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return com.lqbz.ops.compat.Java8.map("username", sessions.username(), "displayName", sessions.displayName(), "canManageIntegrations", true);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        sessions.logout(extractToken(authorization));
    }

    static String extractToken(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class LoginRequest {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public LoginRequest(@com.fasterxml.jackson.annotation.JsonProperty("username") String username, @com.fasterxml.jackson.annotation.JsonProperty("password") String password) {
            this.username = username;
            this.password = password;
        }

        private final String username;

        public String username() {
            return this.username;
        }

        private final String password;

        public String password() {
            return this.password;
        }
    }
}
