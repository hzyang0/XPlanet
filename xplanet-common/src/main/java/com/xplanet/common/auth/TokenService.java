package com.xplanet.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 标准 JWT/JWS 的签发与校验服务。
 *
 * <p>密钥只从外部配置读取，HS256 至少需要 32 字节密钥。服务保持无状态，
 * article、interaction 和 user 使用相同配置即可独立验签。</p>
 */
@Component
public class TokenService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final long expirationSeconds;
    private final String issuer;

    public TokenService(
            @Value("${security.token.secret:}") String secret,
            @Value("${security.token.expiration-seconds:86400}") long expirationSeconds,
            @Value("${security.token.issuer:xplanet}") String issuer) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("security.token.secret/TOKEN_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("security.token.expiration-seconds must be positive");
        }
        if (issuer == null || issuer.trim().isEmpty()) {
            throw new IllegalArgumentException("security.token.issuer must not be blank");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationSeconds = expirationSeconds;
        this.issuer = issuer;
    }

    /** 签发带 subject、issuer、jti、签发时间和过期时间的 JWT。 */
    public String issue(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(issuer)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(signingKey)
                .compact();
    }

    /** 校验签名、issuer 和过期时间，失败统一返回 null。 */
    public Long verify(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long userId = Long.parseLong(claims.getSubject());
            return userId > 0 ? userId : null;
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
