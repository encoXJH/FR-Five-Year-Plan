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
        String validatedSecret = requireStrongSecret(secret);
        this.key = Keys.hmacShaKeyFor(validatedSecret.getBytes(StandardCharsets.UTF_8));
        this.accessExpireMillis = accessExpireMinutes * 60_000L;
    }

    private static String requireStrongSecret(String secret) {
        String value = secret == null ? "" : secret.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        boolean placeholder = lower.contains("please-change-me") || lower.contains("change-me")
                || lower.contains("changeme") || lower.contains("replace-me")
                || lower.contains("your-secret") || lower.contains("placeholder")
                || lower.startsWith("${");
        if (value.getBytes(StandardCharsets.UTF_8).length < 32 || placeholder) {
            throw new IllegalStateException("JWT 密钥缺失、过短或仍为占位值，请通过 JWT_SECRET 配置至少 32 字节的随机密钥");
        }
        return value;
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
