package com.tokenhub.security;

import com.tokenhub.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final JwtProperties properties;
    private final SecretKey key;
    public JwtService(JwtProperties properties) { this.properties = properties; this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8)); }
    public String createAccessToken(long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder().subject(String.valueOf(userId)).claim("username", username).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(properties.accessTokenTtlSeconds()))).signWith(key).compact();
    }
    public long parseUserId(String token) { Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload(); return Long.parseLong(claims.getSubject()); }
}
