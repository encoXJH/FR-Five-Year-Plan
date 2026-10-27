package edu.fzrj.metrics.common.ratelimit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenBucket 纯逻辑单测（令牌桶：突发容量、匀速补充、容量上限、超限拒绝）
 */
class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;

    @Nested
    class Burst {
        @Test
        void 新桶允许打满容量() {
            TokenBucket bucket = new TokenBucket(10, 0);
            for (int i = 0; i < 10; i++) {
                assertTrue(bucket.tryConsume(i), "第 " + (i + 1) + " 次应放行");
            }
            assertFalse(bucket.tryConsume(10), "第 11 次应拒绝");
        }

        @Test
        void 容量为1的桶只能放行一次() {
            TokenBucket bucket = new TokenBucket(1, 0);
            assertTrue(bucket.tryConsume(0));
            assertFalse(bucket.tryConsume(1));
        }
    }

    @Nested
    class Refill {
        @Test
        void 按分钟匀速补充() {
            // 60/分钟 → 每秒补 1 个
            TokenBucket bucket = new TokenBucket(60, 0);
            for (int i = 0; i < 60; i++) {
                assertTrue(bucket.tryConsume(0), "打满 60 个应全部放行");
            }
            assertFalse(bucket.tryConsume(0), "打满后应拒绝");
            // 过 3 秒补 3 个
            assertTrue(bucket.tryConsume(3 * SECOND));
            assertTrue(bucket.tryConsume(3 * SECOND));
            assertTrue(bucket.tryConsume(3 * SECOND));
            assertFalse(bucket.tryConsume(3 * SECOND), "补充的 3 个用完后应拒绝");
        }

        @Test
        void 一分钟整后补满() {
            TokenBucket bucket = new TokenBucket(10, 0);
            for (int i = 0; i < 10; i++) {
                bucket.tryConsume(0);
            }
            for (int i = 0; i < 10; i++) {
                assertTrue(bucket.tryConsume(MINUTE), "一分钟后应补满");
            }
            assertFalse(bucket.tryConsume(MINUTE));
        }

        @Test
        void 补充不超过容量上限() {
            TokenBucket bucket = new TokenBucket(5, 0);
            // 一个令牌不用，放 10 分钟也不应超过容量 5
            for (int i = 0; i < 5; i++) {
                assertTrue(bucket.tryConsume(10 * MINUTE));
            }
            assertFalse(bucket.tryConsume(10 * MINUTE), "补充不应超过容量");
        }

        @Test
        void 时间回拨不吞令牌() {
            TokenBucket bucket = new TokenBucket(10, 100);
            assertTrue(bucket.tryConsume(50), "nowNanos 倒退时不应补充也不应报错");
        }
    }

    @Nested
    class Guard {
        @Test
        void 容量必须为正() {
            assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 0));
            assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1, 0));
        }

        @Test
        void 空闲时间统计() {
            TokenBucket bucket = new TokenBucket(10, 0);
            bucket.tryConsume(0);
            assertEquals(5 * SECOND, bucket.idleNanos(5 * SECOND));
        }
    }
}
