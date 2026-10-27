package edu.fzrj.metrics.module.stats.service.support;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StatsAggregator 纯逻辑单测（计数口径 / 时间进度 / 滞后预警 / 状态归类）
 */
class StatsAggregatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Nested
    class Countable {
        @Test
        void 正目标计入统计基数() {
            assertTrue(StatsAggregator.countable(bd("1")));
            assertTrue(StatsAggregator.countable(bd("0.6")));
        }

        @Test
        void 空目标与零目标不计入() {
            assertFalse(StatsAggregator.countable(null));
            assertFalse(StatsAggregator.countable(BigDecimal.ZERO));
        }
    }

    @Nested
    class Rate {
        @Test
        void 完成率等于达标数除以基数() {
            assertEquals(0, bd("0.500000").compareTo(StatsAggregator.rate(2, 4)));
        }

        @Test
        void 基数为零时完成率为空() {
            assertNull(StatsAggregator.rate(0, 0));
        }

        @Test
        void 全部达标完成率为一() {
            assertEquals(0, BigDecimal.ONE.compareTo(StatsAggregator.rate(3, 3)));
        }
    }

    @Nested
    class TimeProgress {
        @Test
        void 今天在该年之前进度为零() {
            assertEquals(0, BigDecimal.ZERO.compareTo(
                    StatsAggregator.timeProgress(2026, LocalDate.of(2025, 12, 31))));
        }

        @Test
        void 今天在该年之后进度为一() {
            assertEquals(0, BigDecimal.ONE.compareTo(
                    StatsAggregator.timeProgress(2026, LocalDate.of(2027, 1, 1))));
        }

        @Test
        void 年中按已过年天数计算() {
            // 2026-07-02 是 2026 年（平年 365 天）第 183 天
            BigDecimal p = StatsAggregator.timeProgress(2026, LocalDate.of(2026, 7, 2));
            assertEquals(0, bd("0.501370").compareTo(p));
        }

        @Test
        void 年初第一天为三百六十五分之一() {
            BigDecimal p = StatsAggregator.timeProgress(2026, LocalDate.of(2026, 1, 1));
            assertEquals(0, BigDecimal.ONE.divide(bd("365"), 6, java.math.RoundingMode.HALF_UP).compareTo(p));
        }
    }

    @Nested
    class Lagging {
        @Test
        void 时间进度减完成进度超过阈值则滞后() {
            assertTrue(StatsAggregator.isLagging(bd("0.2"), bd("0.8"), bd("0.5")));
        }

        @Test
        void 差值恰好等于阈值不预警() {
            assertFalse(StatsAggregator.isLagging(bd("0.3"), bd("0.8"), bd("0.5")));
        }

        @Test
        void 没有实际完成率不预警() {
            assertFalse(StatsAggregator.isLagging(null, bd("0.8"), bd("0.5")));
            assertFalse(StatsAggregator.isLagging(null, bd("0.4"), bd("0.5")));
        }

        @Test
        void 完成率超过时间进度不滞后() {
            assertFalse(StatsAggregator.isLagging(bd("1.2"), bd("0.8"), bd("0.5")));
        }
    }

    @Nested
    class YearStatusClassify {
        @Test
        void 已达标含已补做() {
            assertTrue(StatsAggregator.isAchievedStatus("ACHIEVED"));
            assertTrue(StatsAggregator.isAchievedStatus("MADE_UP"));
            assertFalse(StatsAggregator.isAchievedStatus("IN_PROGRESS"));
        }

        @Test
        void 未达标含待补做() {
            assertTrue(StatsAggregator.isNotAchievedStatus("NOT_ACHIEVED"));
            assertTrue(StatsAggregator.isNotAchievedStatus("MAKEUP_PENDING"));
            assertFalse(StatsAggregator.isNotAchievedStatus("ACHIEVED"));
        }

        @Test
        void 进行中含待审核与调整中() {
            assertTrue(StatsAggregator.isInProgressStatus("IN_PROGRESS"));
            assertTrue(StatsAggregator.isInProgressStatus("PENDING_REVIEW"));
            assertTrue(StatsAggregator.isInProgressStatus("ADJUSTING"));
            assertFalse(StatsAggregator.isInProgressStatus("NOT_ACTIVATED"));
        }
    }
}
