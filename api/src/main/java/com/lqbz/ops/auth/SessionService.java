package com.lqbz.ops.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SessionService {
  private final Set<String> tokens = ConcurrentHashMap.newKeySet();
  private final SecureRandom secureRandom = new SecureRandom();
  private final String username;
  private final String password;
  private final String displayName;

  public SessionService(@Value("${ops.auth.username:admin}") String username,
      @Value("${ops.auth.password:admin123}") String password,
      @Value("${ops.auth.display-name:平台管理员}") String displayName) {
    this.username = username;
    this.password = password;
    this.displayName = displayName;
  }

  public String login(String submittedUsername, String submittedPassword) {
    if (!username.equals(submittedUsername) || !password.equals(submittedPassword)) return null;
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tokens.add(token);
    return token;
  }

  public boolean isValid(String token) { return token != null && tokens.contains(token); }
  public void logout(String token) { if (token != null) tokens.remove(token); }
  public String username() { return username; }
  public String displayName() { return displayName; }
}
