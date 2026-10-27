package edu.fzrj.metrics.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * accessToken：JWT(HS256)，sub = userId，2 小时有效（API 约定 4.2）
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final long accessExpireMillis;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-expire-minutes}") long accessExpireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpireMillis = accessExpireMinutes * 60_000L;
    }

    public String createAccessToken(Long userId, String name, List<String> roles) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("name", name)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析并校验；过期抛 ExpiredJwtException，其他非法抛 JwtException */
    public Claims parse(String token) throws ExpiredJwtException, JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessExpireSeconds() {
        return accessExpireMillis / 1000L;
    }
}
