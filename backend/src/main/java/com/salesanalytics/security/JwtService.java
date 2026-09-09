package com.salesanalytics.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.ttl}") Duration ttl) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
    }

    public String issue(CurrentUser user) {
        Instant now = Instant.now();
        return Jwts.builder().subject(user.username())
                .claim("uid", user.id()).claim("name", user.displayName())
                .claim("role", user.role()).claim("storeId", user.storeId())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttl)))
                .signWith(key).compact();
    }

    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Number uid = claims.get("uid", Number.class);
        Number storeId = claims.get("storeId", Number.class);
        return new CurrentUser(uid.longValue(), claims.getSubject(), claims.get("name", String.class),
                claims.get("role", String.class), storeId == null ? null : storeId.longValue());
    }
}
