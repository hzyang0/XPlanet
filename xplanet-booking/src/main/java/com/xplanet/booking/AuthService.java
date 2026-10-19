package com.xplanet.booking;

import static com.xplanet.booking.Models.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final Store store;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  // Bounded, per-instance supplementary throttle. A gateway is needed for a cluster-wide login
  // limit.
  private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

  private record Attempt(int count, Instant since) {}

  public AuthService(Store store, Clock clock) {
    this.store = store;
    this.jdbc = store.jdbc();
    this.clock = clock;
  }

  String encode(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new Problem(400, "密码 UTF-8 长度不能超过 72 字节");
    return encoder.encode(password);
  }

  @Transactional
  public Session register(Register r) {
    long id =
        store.insert(
            "INSERT INTO app_user(username,password_hash,role,created_at) VALUES(?,?,'USER',?)",
            r.username(),
            encode(r.password()),
            now());
    return issue(new Actor(id, r.username(), "USER"));
  }

  @Transactional
  public Session login(Login login) {
    String username = login.username();
    Instant instant = clock.instant();
    if (attempts.size() > 10000)
      attempts
          .entrySet()
          .removeIf(e -> Duration.between(e.getValue().since(), instant).toMinutes() >= 5);
    if (attempts.size() > 10000) throw new Problem(429, "登录请求过多，请稍后再试");
    Attempt a =
        attempts.compute(
            username,
            (k, v) ->
                v == null || Duration.between(v.since(), instant).toMinutes() >= 5
                    ? new Attempt(1, instant)
                    : new Attempt(v.count() + 1, v.since()));
    if (a.count() > 10) throw new Problem(429, "登录尝试过多，请五分钟后重试");
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM app_user WHERE username=?", username);
    if (rows.isEmpty()
        || login.password().getBytes(StandardCharsets.UTF_8).length > 72
        || !encoder.matches(login.password(), (String) rows.get(0).get("password_hash")))
      throw new Problem(401, "账号或密码错误");
    attempts.remove(username);
    Map<String, Object> row = rows.get(0);
    return issue(
        new Actor(((Number) row.get("id")).longValue(), username, (String) row.get("role")));
  }

  private Session issue(Actor actor) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    LocalDateTime expires = now().plusHours(12);
    jdbc.update(
        "INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,?)",
        hash(token),
        actor.id(),
        expires);
    return new Session(token, actor, expires);
  }

  public Optional<Actor> resolve(String token) {
    if (token == null || token.length() != 43) return Optional.empty();
    return jdbc
        .query(
            "SELECT u.id,u.username,u.role FROM auth_session s JOIN app_user u ON u.id=s.user_id"
                + " WHERE s.token_hash=? AND s.expires_at>?",
            (r, n) -> new Actor(r.getLong(1), r.getString(2), r.getString(3)),
            hash(token),
            now())
        .stream()
        .findFirst();
  }

  public void logout(String token) {
    if (token != null) jdbc.update("DELETE FROM auth_session WHERE token_hash=?", hash(token));
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
