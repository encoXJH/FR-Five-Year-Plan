package edu.fzrj.metrics.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void 签发并解析成功() {
        JwtService jwtService = new JwtService(SECRET, 120);
        String token = jwtService.createAccessToken(1234567890123456789L, "张三", List.of("ADMIN"));
        Claims claims = jwtService.parse(token);
        assertEquals("1234567890123456789", claims.getSubject());
        assertEquals("张三", claims.get("name"));
        assertEquals(List.of("ADMIN"), claims.get("roles"));
        assertEquals(120 * 60, jwtService.getAccessExpireSeconds());
    }

    @Test
    void 过期Token抛ExpiredJwtException() {
        JwtService jwtService = new JwtService(SECRET, 0) {
            // 不动；用负分钟直接造过期 token
        };
        // 造一个已过期的 token：有效期为 0 分钟时签发即过期边界，改用反射太重，直接用手工过期 token
        // 简化：有效期 0 分钟签发的 token 立即过期
        String token = jwtService.createAccessToken(1L, "x", List.of());
        assertThrows(ExpiredJwtException.class, () -> jwtService.parse(token));
    }

    @Test
    void 篡改Token抛JwtException() {
        JwtService jwtService = new JwtService(SECRET, 120);
        String token = jwtService.createAccessToken(1L, "x", List.of());
        // 改签名段中间一位（避免仅改动末位 base64 填充位导致解码字节不变）
        int i = token.length() - 10;
        char repl = token.charAt(i) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, i) + repl + token.substring(i + 1);
        assertThrows(JwtException.class, () -> jwtService.parse(tampered));
    }

    @Test
    void 其他密钥签发的Token抛JwtException() {
        JwtService issuer = new JwtService(SECRET, 120);
        JwtService other = new JwtService(
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 120);
        String token = issuer.createAccessToken(1L, "x", List.of());
        assertThrows(JwtException.class, () -> other.parse(token));
    }
}
