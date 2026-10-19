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
  public Map<String, Object> status() { return Map.of("enabled", enabled); }

  @PostMapping("/login")
  public Map<String, Object> login(@RequestBody LoginRequest request) {
    String token = sessions.login(request.username(), request.password());
    if (token == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    return Map.of("token", token, "username", sessions.username(), "displayName", sessions.displayName(),
        "canManageIntegrations", true);
  }

  @GetMapping("/me")
  public Map<String, Object> me() {
    return Map.of("username", sessions.username(), "displayName", sessions.displayName(), "canManageIntegrations", true);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
    sessions.logout(extractToken(authorization));
  }

  static String extractToken(String authorization) {
    return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
  }

  public record LoginRequest(String username, String password) {}
}
