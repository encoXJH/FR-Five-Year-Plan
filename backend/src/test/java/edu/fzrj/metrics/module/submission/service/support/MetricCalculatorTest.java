package edu.fzrj.metrics.module.submission.service.support;

import edu.fzrj.metrics.module.submission.service.support.MetricCalculator.ApprovedValue;
import edu.fzrj.metrics.module.submission.service.support.MetricCalculator.YearResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MetricCalculator 纯逻辑单测（六口径年度完成值 / 汇总 / 完成率 / 达标判定 / 边界）
 */
class MetricCalculatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Nested
    class Increment {
        @Test
        void 年度完成值为审核通过增量之和() {
            YearResult r = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("2")), ApprovedValue.increment(bd("3"))), null);
            assertEquals(0, bd("5").compareTo(r.actualValue()));
        }

        @Test
        void 空数据返回空结果() {
            YearResult r = MetricCalculator.yearActual("INCREMENT", List.of(), null);
            assertNull(r.actualValue());
            assertFalse(r.hasData());
        }

        @Test
        void 汇总为各下级之和() {
            YearResult r = MetricCalculator.rollup("INCREMENT", List.of(
                    MetricCalculator.yearActual("INCREMENT", List.of(ApprovedValue.increment(bd("2"))), null),
                    MetricCalculator.yearActual("INCREMENT", List.of(ApprovedValue.increment(bd("3"))), null)), null);
            assertEquals(0, bd("5").compareTo(r.actualValue()));
        }

        @Test
        void 汇总时下级空值不参与() {
            YearResult r = MetricCalculator.rollup("INCREMENT", List.of(
                    YearResult.empty(),
                    MetricCalculator.yearActual("INCREMENT", List.of(ApprovedValue.increment(bd("3"))), null)), null);
            assertEquals(0, bd("3").compareTo(r.actualValue()));
        }

        @Test
        void 全部下级无数据时汇总为空() {
            assertNull(MetricCalculator.rollup("INCREMENT",
                    List.of(YearResult.empty(), YearResult.empty()), null).actualValue());
        }

        @Test
        void 完成率等于完成值除以目标() {
            YearResult r = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("1"))), null);
            assertEquals(0, bd("0.500000").compareTo(MetricCalculator.completionRate("INCREMENT", r, bd("2"))));
        }
    }

    @Nested
    class Stock {
        @Test
        void 年度完成值取当年最后一次审核通过的总量() {
            YearResult r = MetricCalculator.yearActual("STOCK",
                    List.of(ApprovedValue.total(bd("100")), ApprovedValue.total(bd("120"))), bd("80"));
            assertEquals(0, bd("120").compareTo(r.actualValue()));
            assertEquals(0, bd("120").compareTo(r.total()));
        }

        @Test
        void 增量等于本年总量减上年总量() {
            YearResult r = MetricCalculator.yearActual("STOCK",
                    List.of(ApprovedValue.total(bd("120"))), bd("80"));
            assertEquals(0, bd("40").compareTo(r.increment()));
        }

        @Test
        void 上年无数据时增量为空() {
            YearResult r = MetricCalculator.yearActual("STOCK",
                    List.of(ApprovedValue.total(bd("120"))), null);
            assertNull(r.increment());
        }

        @Test
        void 汇总为各下级总量之和() {
            YearResult r = MetricCalculator.rollup("STOCK", List.of(
                    MetricCalculator.yearActual("STOCK", List.of(ApprovedValue.total(bd("100"))), null),
                    MetricCalculator.yearActual("STOCK", List.of(ApprovedValue.total(bd("120"))), null)), bd("180"));
            assertEquals(0, bd("220").compareTo(r.total()));
            assertEquals(0, bd("40").compareTo(r.increment()));
        }

        @Test
        void 完成率按总量比目标() {
            YearResult r = MetricCalculator.yearActual("STOCK",
                    List.of(ApprovedValue.total(bd("90"))), bd("80"));
            assertEquals(0, bd("0.900000").compareTo(MetricCalculator.completionRate("STOCK", r, bd("100"))));
        }
    }

    @Nested
    class Growth {
        @Test
        void 第一年增长率用基线值() {
            YearResult r = MetricCalculator.yearActual("GROWTH",
                    List.of(ApprovedValue.total(bd("115"))), bd("100"));
            assertEquals(0, bd("0.150000").compareTo(r.growthRate()));
            assertEquals(0, r.growthRate().compareTo(r.actualValue()));
        }

        @Test
        void 非第一年增长率用上年总量() {
            YearResult r = MetricCalculator.yearActual("GROWTH",
                    List.of(ApprovedValue.total(bd("121"))), bd("110"));
            assertEquals(0, bd("0.100000").compareTo(r.growthRate()));
        }

        @Test
        void 基线为空时增长率为空() {
            YearResult r = MetricCalculator.yearActual("GROWTH",
                    List.of(ApprovedValue.total(bd("115"))), null);
            assertNull(r.growthRate());
            assertEquals(0, bd("115").compareTo(r.total()));
        }

        @Test
        void 基线为零时增长率为空() {
            assertNull(MetricCalculator.yearActual("GROWTH",
                    List.of(ApprovedValue.total(bd("115"))), BigDecimal.ZERO).growthRate());
        }

        @Test
        void 汇总先加总各单元总量再算增长率() {
            YearResult r = MetricCalculator.rollup("GROWTH", List.of(
                    MetricCalculator.yearActual("GROWTH", List.of(ApprovedValue.total(bd("60"))), null),
                    MetricCalculator.yearActual("GROWTH", List.of(ApprovedValue.total(bd("55"))), null)), bd("100"));
            assertEquals(0, bd("115").compareTo(r.total()));
            assertEquals(0, bd("0.150000").compareTo(r.growthRate()));
        }

        @Test
        void 完成率按总量比目标总量() {
            YearResult r = MetricCalculator.yearActual("GROWTH",
                    List.of(ApprovedValue.total(bd("115"))), bd("100"));
            assertEquals(0, bd("1.150000").compareTo(MetricCalculator.completionRate("GROWTH", r, bd("100"))));
        }
    }

    @Nested
    class Ratio {
        @Test
        void 单任务占比等于分子除以分母() {
            YearResult r = MetricCalculator.yearActual("RATIO",
                    List.of(ApprovedValue.ratio(bd("62"), bd("100"))), null);
            assertEquals(0, bd("0.620000").compareTo(r.actualValue()));
        }

        @Test
        void 汇总为分子之和除以分母之和而非百分比平均() {
            // 学院一 1/2=50%，学院二 3/4=75%；平均为 62.5%，正确汇总为 4/6≈66.67%
            YearResult r = MetricCalculator.rollup("RATIO", List.of(
                    MetricCalculator.yearActual("RATIO", List.of(ApprovedValue.ratio(bd("1"), bd("2"))), null),
                    MetricCalculator.yearActual("RATIO", List.of(ApprovedValue.ratio(bd("3"), bd("4"))), null)), null);
            assertEquals(0, bd("4").compareTo(r.numerator()));
            assertEquals(0, bd("6").compareTo(r.denominator()));
            assertEquals(0, bd("0.666667").compareTo(r.actualValue()));
        }

        @Test
        void 分母为零时比率为空() {
            assertNull(MetricCalculator.yearActual("RATIO",
                    List.of(ApprovedValue.ratio(bd("1"), BigDecimal.ZERO)), null).actualValue());
        }

        @Test
        void 多次填报分子分母分别累计() {
            YearResult r = MetricCalculator.yearActual("RATIO", List.of(
                    ApprovedValue.ratio(bd("10"), bd("40")), ApprovedValue.ratio(bd("30"), bd("60"))), null);
            assertEquals(0, bd("0.400000").compareTo(r.actualValue()));
        }
    }

    @Nested
    class DirectPercent {
        @Test
        void 单任务取最后一次审核通过的百分比() {
            YearResult r = MetricCalculator.yearActual("DIRECT_PERCENT",
                    List.of(ApprovedValue.percent(bd("0.5")), ApprovedValue.percent(bd("0.6"))), null);
            assertEquals(0, bd("0.6").compareTo(r.actualValue()));
        }

        @Test
        void 单一下级有数据时汇总沿用其值() {
            YearResult r = MetricCalculator.rollup("DIRECT_PERCENT", List.of(
                    YearResult.empty(),
                    MetricCalculator.yearActual("DIRECT_PERCENT", List.of(ApprovedValue.percent(bd("0.6"))), null)), null);
            assertEquals(0, bd("0.6").compareTo(r.actualValue()));
        }

        @Test
        void 多个下级有数据时不自动汇总() {
            YearResult r = MetricCalculator.rollup("DIRECT_PERCENT", List.of(
                    MetricCalculator.yearActual("DIRECT_PERCENT", List.of(ApprovedValue.percent(bd("0.5"))), null),
                    MetricCalculator.yearActual("DIRECT_PERCENT", List.of(ApprovedValue.percent(bd("0.6"))), null)), null);
            assertNull(r.actualValue());
        }
    }

    @Nested
    class BooleanCaliber {
        @Test
        void 任一审核通过为完成即完成() {
            YearResult r = MetricCalculator.yearActual("BOOLEAN",
                    List.of(ApprovedValue.bool(false), ApprovedValue.bool(true)), null);
            assertEquals(0, BigDecimal.ONE.compareTo(r.actualValue()));
        }

        @Test
        void 全部未完成为零() {
            YearResult r = MetricCalculator.yearActual("BOOLEAN",
                    List.of(ApprovedValue.bool(false)), null);
            assertEquals(0, BigDecimal.ZERO.compareTo(r.actualValue()));
        }

        @Test
        void 汇总要求全部下级完成() {
            YearResult r = MetricCalculator.rollup("BOOLEAN", List.of(
                    MetricCalculator.yearActual("BOOLEAN", List.of(ApprovedValue.bool(true)), null),
                    MetricCalculator.yearActual("BOOLEAN", List.of(ApprovedValue.bool(false)), null)), null);
            assertEquals(0, BigDecimal.ZERO.compareTo(r.actualValue()));
        }

        @Test
        void 完成率完成年完成为一() {
            YearResult r = MetricCalculator.yearActual("BOOLEAN",
                    List.of(ApprovedValue.bool(true)), null);
            assertEquals(0, BigDecimal.ONE.compareTo(MetricCalculator.completionRate("BOOLEAN", r, BigDecimal.ONE)));
        }

        @Test
        void 非完成年目标为空时完成率为空() {
            YearResult r = MetricCalculator.yearActual("BOOLEAN",
                    List.of(ApprovedValue.bool(true)), null);
            assertNull(MetricCalculator.completionRate("BOOLEAN", r, null));
        }
    }

    @Nested
    class AchieveJudge {
        @Test
        void 越大越好达到目标即达标() {
            YearResult r = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("2"))), null);
            assertTrue(MetricCalculator.achieved("HIGHER_BETTER", "INCREMENT", r, bd("2")));
            assertFalse(MetricCalculator.achieved("HIGHER_BETTER", "INCREMENT", r, bd("3")));
        }

        @Test
        void 越小越好低于目标即达标() {
            YearResult r = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("2"))), null);
            assertTrue(MetricCalculator.achieved("LOWER_BETTER", "INCREMENT", r, bd("2")));
            assertFalse(MetricCalculator.achieved("LOWER_BETTER", "INCREMENT", r, bd("1")));
        }

        @Test
        void 无数据或目标为空时无法判定() {
            assertNull(MetricCalculator.achieved("HIGHER_BETTER", "INCREMENT", YearResult.empty(), bd("1")));
            YearResult r = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("1"))), null);
            assertNull(MetricCalculator.achieved("HIGHER_BETTER", "INCREMENT", r, null));
        }

        @Test
        void 达成状态覆盖未开始进行中达标超额() {
            assertEquals("NOT_STARTED",
                    MetricCalculator.achieveStatus("HIGHER_BETTER", "INCREMENT", YearResult.empty(), bd("2")));
            YearResult half = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("1"))), null);
            assertEquals("IN_PROGRESS",
                    MetricCalculator.achieveStatus("HIGHER_BETTER", "INCREMENT", half, bd("2")));
            YearResult full = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("2"))), null);
            assertEquals("ACHIEVED",
                    MetricCalculator.achieveStatus("HIGHER_BETTER", "INCREMENT", full, bd("2")));
            YearResult over = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("3"))), null);
            assertEquals("EXCEEDED",
                    MetricCalculator.achieveStatus("HIGHER_BETTER", "INCREMENT", over, bd("2")));
        }

        @Test
        void 目标为零时完成率为空() {
            YearResult r = MetricCalculator.yearActual("INCREMENT",
                    List.of(ApprovedValue.increment(bd("1"))), null);
            assertNull(MetricCalculator.completionRate("INCREMENT", r, BigDecimal.ZERO));
        }

        @Test
        void 未知口径抛异常() {
            assertThrows(IllegalArgumentException.class,
                    () -> MetricCalculator.yearActual("UNKNOWN", List.of(ApprovedValue.bool(true)), null));
        }
    }
}
