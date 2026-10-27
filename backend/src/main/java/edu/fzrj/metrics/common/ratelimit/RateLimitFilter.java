package edu.fzrj.metrics.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.api.ApiResponse;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.web.RequestIdFilter;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流过滤器（API 约定第 13 章，内存令牌桶，不引 Redis）：
 * 登录类接口按 IP 10 次/分钟；导出按用户 5 次/分钟；其余按用户 300 次/分钟（未登录按 IP）。
 * 超限返回 429 + 10006。挂在 JWT 过滤器之后（导出 / 普通接口需要用户维度）。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** 桶数量超过该阈值时做一次空闲清理（惰性，无需定时线程） */
    private static final int SWEEP_THRESHOLD = 10_000;
    private static final long IDLE_NANOS_LIMIT = 10L * 60_000_000_000L;

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong sweepCounter = new AtomicLong();

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;
    @Value("${app.rate-limit.login-per-minute:10}")
    private int loginPerMinute;
    @Value("${app.rate-limit.api-per-minute:300}")
    private int apiPerMinute;
    @Value("${app.rate-limit.export-per-minute:5}")
    private int exportPerMinute;

    public RateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        // 公开静态资源与文档不参与限流；签名文件流 /raw 走普通接口档（无登录态）
        return !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String key;
        int limit;
        if (isLoginRequest(request, path)) {
            key = "login:ip:" + clientIp(request);
            limit = loginPerMinute;
        } else if (isExportCreate(request, path)) {
            key = "export:u:" + userKey(request);
            limit = exportPerMinute;
        } else {
            key = "api:" + userKey(request);
            limit = apiPerMinute;
        }
        long now = System.nanoTime();
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(limit, now));
        if (!bucket.tryConsume(now)) {
            writeRejected(response);
            return;
        }
        maybeSweep(now);
        chain.doFilter(request, response);
    }

    private boolean isLoginRequest(HttpServletRequest request, String path) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && ("/api/v1/auth/login".equals(path) || "/api/v1/auth/feishu/login".equals(path));
    }

    private boolean isExportCreate(HttpServletRequest request, String path) {
        return "POST".equalsIgnoreCase(request.getMethod()) && "/api/v1/exports".equals(path);
    }

    /** 用户维度 key：已登录用 userId，未登录回退 IP */
    private String userKey(HttpServletRequest request) {
        LoginUser user = SecurityUtils.getLoginUserOrNull();
        return user != null ? String.valueOf(user.getUserId()) : "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 惰性清理：每 SWEEP_THRESHOLD 次请求扫描一次，移除空闲超过 10 分钟的桶 */
    private void maybeSweep(long now) {
        if (sweepCounter.incrementAndGet() % SWEEP_THRESHOLD != 0 || buckets.size() < SWEEP_THRESHOLD) {
            return;
        }
        Iterator<Map.Entry<String, TokenBucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().idleNanos(now) > IDLE_NANOS_LIMIT) {
                it.remove();
            }
        }
    }

    private void writeRejected(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.RATE_LIMITED.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.error(ErrorCode.RATE_LIMITED.getCode(),
                ErrorCode.RATE_LIMITED.getMessage(), MDC.get(RequestIdFilter.MDC_KEY));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
