package edu.fzrj.metrics.common.ratelimit;

/**
 * 内存令牌桶（API 约定第 13 章频率限制）：容量 = 每分钟允许的请求数，
 * 按容量 / 分钟匀速补充，消耗 1 个令牌放行一次请求。
 * 纯逻辑类，时间由调用方注入（System.nanoTime），便于单元测试。
 */
public class TokenBucket {

    private static final double NANOS_PER_MINUTE = 60_000_000_000.0;

    private final int capacity;
    private final double nanosPerToken;
    private double tokens;
    private long lastNanos;

    public TokenBucket(int capacityPerMinute, long nowNanos) {
        if (capacityPerMinute <= 0) {
            throw new IllegalArgumentException("capacityPerMinute 必须为正数");
        }
        this.capacity = capacityPerMinute;
        this.nanosPerToken = NANOS_PER_MINUTE / capacityPerMinute;
        this.tokens = capacityPerMinute;
        this.lastNanos = nowNanos;
    }

    /** 尝试消费 1 个令牌；成功返回 true，无令牌返回 false */
    public synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** 按流逝时间补充令牌（不超过容量） */
    private void refill(long nowNanos) {
        if (nowNanos <= lastNanos) {
            return;
        }
        tokens = Math.min(capacity, tokens + (nowNanos - lastNanos) / nanosPerToken);
        lastNanos = nowNanos;
    }

    /** 距上次访问的间隔（纳秒），供空闲桶清理 */
    public synchronized long idleNanos(long nowNanos) {
        return nowNanos - lastNanos;
    }
}
