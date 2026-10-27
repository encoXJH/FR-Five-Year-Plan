package edu.fzrj.metrics.module.stats.service.support;

import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator.YearResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SettlementRules 纯逻辑单测（年度核算终态 / 待补做缺口 / 五年核算口径 / 解锁回退）
 */
class SettlementRulesTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Nested
    class SettleStatus {
        @Test
        void 越大越好达到目标判已达标() {
            YearResult result = MetricCalculator.yearActual("INCREMENT",
                    List.of(MetricCalculator.ApprovedValue.increment(bd("3"))), null);
            assertEquals("ACHIEVED", SettlementRules.settleStatus("HIGHER_BETTER", "INCREMENT", result, bd("3")));
        }

        @Test
        void 越大越好未达目标判未达标() {
            YearResult result = MetricCalculator.yearActual("INCREMENT",
                    List.of(MetricCalculator.ApprovedValue.increment(bd("2"))), null);
            assertEquals("NOT_ACHIEVED", SettlementRules.settleStatus("HIGHER_BETTER", "INCREMENT", result, bd("3")));
        }

        @Test
        void 越小越好低于目标判已达标() {
            YearResult result = new YearResult(bd("1"), null, null, null, null, null);
            assertEquals("ACHIEVED", SettlementRules.settleStatus("LOWER_BETTER", "INCREMENT", result, bd("2")));
        }

        @Test
        void 无任何审核数据判未达标() {
            assertEquals("NOT_ACHIEVED", SettlementRules.settleStatus("HIGHER_BETTER", "INCREMENT",
                    YearResult.empty(), bd("3")));
        }

        @Test
        void 目标为空不判定_BOOLEAN非完成年() {
            YearResult result = new YearResult(BigDecimal.ONE, null, null, null, null, null);
            assertNull(SettlementRules.settleStatus("HIGHER_BETTER", "BOOLEAN", result, null));
        }

        @Test
        void BOOLEAN到计划完成年未完成判未达标() {
            YearResult result = new YearResult(BigDecimal.ZERO, null, null, null, null, null);
            assertEquals("NOT_ACHIEVED", SettlementRules.settleStatus("HIGHER_BETTER", "BOOLEAN", result, BigDecimal.ONE));
        }
    }

    @Nested
    class Makeup {
        @Test
        void 仅增量与是否型生成待补做() {
            assertTrue(SettlementRules.makeupEligible("INCREMENT"));
            assertTrue(SettlementRules.makeupEligible("BOOLEAN"));
            assertFalse(SettlementRules.makeupEligible("STOCK"));
            assertFalse(SettlementRules.makeupEligible("GROWTH"));
            assertFalse(SettlementRules.makeupEligible("RATIO"));
            assertFalse(SettlementRules.makeupEligible("DIRECT_PERCENT"));
        }

        @Test
        void 增量缺口为目标减完成值() {
            YearResult result = new YearResult(bd("2"), null, null, null, null, null);
            assertEquals(0, bd("3").compareTo(SettlementRules.gapAmount("INCREMENT", result, bd("5"))));
        }

        @Test
        void 无审核数据时缺口为目标全额() {
            assertEquals(0, bd("5").compareTo(
                    SettlementRules.gapAmount("INCREMENT", YearResult.empty(), bd("5"))));
        }

        @Test
        void 超额完成无缺口() {
            YearResult result = new YearResult(bd("6"), null, null, null, null, null);
            assertNull(SettlementRules.gapAmount("INCREMENT", result, bd("5")));
        }

        @Test
        void 是否型缺口恒为一() {
            assertEquals(0, BigDecimal.ONE.compareTo(
                    SettlementRules.gapAmount("BOOLEAN", new YearResult(BigDecimal.ZERO, null, null, null, null, null), BigDecimal.ONE)));
        }

        @Test
        void 非补做口径与空目标返回空() {
            assertNull(SettlementRules.gapAmount("RATIO", YearResult.empty(), bd("0.6")));
            assertNull(SettlementRules.gapAmount("INCREMENT", YearResult.empty(), null));
        }

        @Test
        void 补做年度为下一年末年封顶() {
            assertEquals(2028, SettlementRules.makeupToYear(2027, 2030));
            assertEquals(2030, SettlementRules.makeupToYear(2030, 2030));
        }
    }

    @Nested
    class Unlock {
        @Test
        void 未达标与待补做回退为进行中() {
            assertEquals("IN_PROGRESS", SettlementRules.statusOnUnlock("NOT_ACHIEVED"));
            assertEquals("IN_PROGRESS", SettlementRules.statusOnUnlock("MAKEUP_PENDING"));
        }

        @Test
        void 其他状态不动() {
            assertEquals("ACHIEVED", SettlementRules.statusOnUnlock("ACHIEVED"));
            assertEquals("MADE_UP", SettlementRules.statusOnUnlock("MADE_UP"));
            assertEquals("IN_PROGRESS", SettlementRules.statusOnUnlock("IN_PROGRESS"));
        }
    }

    @Nested
    class FiveYear {
        @Test
        void 增量五年完成值为各年之和() {
            List<YearResult> years = List.of(
                    new YearResult(bd("1"), null, null, null, null, null),
                    YearResult.empty(),
                    new YearResult(bd("2"), null, null, null, null, null));
            assertEquals(0, bd("3").compareTo(SettlementRules.fiveYearActual("INCREMENT", years)));
        }

        @Test
        void 存量五年完成值取最后一个有数据年的总量() {
            List<YearResult> years = List.of(
                    new YearResult(bd("100"), null, null, bd("100"), null, null),
                    new YearResult(bd("120"), null, null, bd("120"), null, null),
                    YearResult.empty());
            assertEquals(0, bd("120").compareTo(SettlementRules.fiveYearActual("STOCK", years)));
        }

        @Test
        void 增长率五年完成值同样取末年总量() {
            List<YearResult> years = List.of(
                    new YearResult(bd("0.05"), null, null, bd("1050"), null, null),
                    new YearResult(bd("0.10"), null, null, bd("1155"), null, null));
            assertEquals(0, bd("1155").compareTo(SettlementRules.fiveYearActual("GROWTH", years)));
        }

        @Test
        void 占比与直填百分比取末年值() {
            List<YearResult> years = List.of(
                    new YearResult(bd("0.55"), null, null, null, null, null),
                    new YearResult(bd("0.62"), null, null, null, null, null));
            assertEquals(0, bd("0.62").compareTo(SettlementRules.fiveYearActual("RATIO", years)));
            assertEquals(0, bd("0.62").compareTo(SettlementRules.fiveYearActual("DIRECT_PERCENT", years)));
        }

        @Test
        void 是否型任一年完成即完成() {
            List<YearResult> years = List.of(
                    new YearResult(BigDecimal.ZERO, null, null, null, null, null),
                    new YearResult(BigDecimal.ONE, null, null, null, null, null));
            assertEquals(0, BigDecimal.ONE.compareTo(SettlementRules.fiveYearActual("BOOLEAN", years)));
        }

        @Test
        void 全无数据返回空() {
            List<YearResult> years = List.of(YearResult.empty(), YearResult.empty());
            assertNull(SettlementRules.fiveYearActual("INCREMENT", years));
            assertNull(SettlementRules.fiveYearActual("STOCK", years));
            assertNull(SettlementRules.fiveYearActual("BOOLEAN", years));
        }

        @Test
        void 五年达标判定按达成方向() {
            assertTrue(SettlementRules.fiveYearAchieved("HIGHER_BETTER", "INCREMENT", bd("5"), bd("5")));
            assertFalse(SettlementRules.fiveYearAchieved("HIGHER_BETTER", "INCREMENT", bd("4"), bd("5")));
            assertTrue(SettlementRules.fiveYearAchieved("LOWER_BETTER", "INCREMENT", bd("4"), bd("5")));
            assertNull(SettlementRules.fiveYearAchieved("HIGHER_BETTER", "INCREMENT", null, bd("5")));
            assertNull(SettlementRules.fiveYearAchieved("HIGHER_BETTER", "INCREMENT", bd("5"), null));
        }
    }
}
