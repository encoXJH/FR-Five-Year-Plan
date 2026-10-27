package edu.fzrj.metrics.module.stats.service.support;

import edu.fzrj.metrics.module.submission.service.support.MetricCalculator;
import edu.fzrj.metrics.module.task.entity.TaskYear;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 五年累计进度（CumulativeCalculator）口径单测：
 * INCREMENT 各年之和；STOCK/GROWTH 最新总量；RATIO/DIRECT_PERCENT 最新比率；BOOLEAN 任一完成。
 */
class CumulativeCalculatorTest {

    private static MetricCalculator.YearResult inc(String value) {
        return new MetricCalculator.YearResult(value == null ? null : new BigDecimal(value),
                null, null, null, null, null);
    }

    private static MetricCalculator.YearResult total(String value) {
        return new MetricCalculator.YearResult(value == null ? null : new BigDecimal(value),
                null, null, value == null ? null : new BigDecimal(value), null, null);
    }

    private static TaskYear ty(int year, String actual, String target) {
        TaskYear ty = new TaskYear();
        ty.setYear(year);
        ty.setActualValue(actual == null ? null : new BigDecimal(actual));
        ty.setTarget(target == null ? null : new BigDecimal(target));
        return ty;
    }

    // ---------------- INCREMENT ----------------

    @Test
    void incrementSumsAllYears() {
        // 「五年 5 本教材」：已完成 1+1=2 本，累计 40%
        List<MetricCalculator.YearResult> years = List.of(
                inc("1"), inc("1"), inc(null), inc(null), inc(null));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.of("INCREMENT", years, new BigDecimal("5"));
        assertEquals(new BigDecimal("2"), c.actual());
        assertEquals(new BigDecimal("0.400000"), c.rate());
    }

    @Test
    void incrementNoDataReturnsNull() {
        List<MetricCalculator.YearResult> years = List.of(inc(null), inc(null));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.of("INCREMENT", years, new BigDecimal("5"));
        assertNull(c.actual());
        assertNull(c.rate());
    }

    // ---------------- STOCK / GROWTH ----------------

    @Test
    void stockTakesLatestTotal() {
        List<MetricCalculator.YearResult> years = List.of(
                total("12000"), total("12600"), total(null), total(null), total(null));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.of("STOCK", years, new BigDecimal("15000"));
        assertEquals(new BigDecimal("12600"), c.actual());
        assertEquals(new BigDecimal("0.840000"), c.rate());
    }

    @Test
    void growthTakesLatestTotal() {
        List<MetricCalculator.YearResult> years = List.of(total("860"), total(null));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.of("GROWTH", years, new BigDecimal("1600"));
        assertEquals(new BigDecimal("860"), c.actual());
        assertEquals(new BigDecimal("0.537500"), c.rate());
    }

    // ---------------- RATIO / DIRECT_PERCENT ----------------

    @Test
    void ratioTakesLatestRate() {
        List<MetricCalculator.YearResult> years = List.of(
                inc(null), new MetricCalculator.YearResult(new BigDecimal("0.527059"), null, null, null, null, null));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.of("RATIO", years, new BigDecimal("0.75"));
        assertEquals(new BigDecimal("0.527059"), c.actual());
        assertEquals(new BigDecimal("0.702745"), c.rate());
    }

    @Test
    void directPercentTakesLatestValue() {
        List<MetricCalculator.YearResult> years = List.of(
                new MetricCalculator.YearResult(new BigDecimal("0.88"), null, null, null, null, null),
                new MetricCalculator.YearResult(new BigDecimal("0.90"), null, null, null, null, null));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.of("DIRECT_PERCENT", years, new BigDecimal("0.90"));
        assertEquals(new BigDecimal("0.90"), c.actual());
        assertEquals(new BigDecimal("1.000000"), c.rate());
    }

    // ---------------- BOOLEAN ----------------

    @Test
    void booleanAnyAchievedIsOne() {
        List<MetricCalculator.YearResult> years = List.of(
                inc(null), inc(null), new MetricCalculator.YearResult(BigDecimal.ONE, null, null, null, null, null));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.of("BOOLEAN", years, BigDecimal.ONE);
        assertEquals(BigDecimal.ONE, c.actual());
        assertEquals(new BigDecimal("1.000000"), c.rate());
    }

    @Test
    void booleanAllNotAchievedIsZero() {
        List<MetricCalculator.YearResult> years = List.of(
                new MetricCalculator.YearResult(BigDecimal.ZERO, null, null, null, null, null));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.of("BOOLEAN", years, BigDecimal.ONE);
        assertEquals(BigDecimal.ZERO, c.actual());
        assertEquals(new BigDecimal("0.000000"), c.rate());
    }

    // ---------------- 任务级 ----------------

    @Test
    void taskTotalTargetIncrementSumsYears() {
        List<TaskYear> years = List.of(ty(2026, "1", "1"), ty(2027, null, "1"), ty(2028, null, "2"));
        assertEquals(new BigDecimal("4"), CumulativeCalculator.taskTotalTarget("INCREMENT", years));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.ofTask("INCREMENT", years);
        assertEquals(new BigDecimal("1"), c.actual());
        assertEquals(new BigDecimal("0.250000"), c.rate());
    }

    @Test
    void taskTotalTargetBooleanIsOneWhenPlanned() {
        List<TaskYear> years = List.of(ty(2026, null, null), ty(2028, null, "1"));
        assertEquals(BigDecimal.ONE, CumulativeCalculator.taskTotalTarget("BOOLEAN", years));
    }

    @Test
    void taskTotalTargetOthersTakeLastYear() {
        List<TaskYear> years = List.of(ty(2026, null, "12500"), ty(2030, null, "15000"));
        assertEquals(new BigDecimal("15000"), CumulativeCalculator.taskTotalTarget("STOCK", years));
    }

    @Test
    void taskCumulativeSortsYears() {
        // 乱序输入也能按年排序取最新总量
        List<TaskYear> years = new ArrayList<>(List.of(ty(2027, null, "13000"), ty(2026, null, "12500")));
        TaskYear y2026 = years.get(1);
        y2026.setActualTotal(new BigDecimal("12000"));
        y2026.setActualValue(new BigDecimal("12000"));
        TaskYear y2027 = years.get(0);
        y2027.setActualTotal(new BigDecimal("12600"));
        y2027.setActualValue(new BigDecimal("12600"));
        CumulativeCalculator.Cumulative c = CumulativeCalculator.ofTask("STOCK", years);
        assertEquals(new BigDecimal("12600"), c.actual());
    }

    // ---------------- 边界 ----------------

    @Test
    void rateNullWhenTargetMissingOrZero() {
        assertNull(CumulativeCalculator.rate(new BigDecimal("1"), null));
        assertNull(CumulativeCalculator.rate(new BigDecimal("1"), BigDecimal.ZERO));
        assertNull(CumulativeCalculator.rate(null, new BigDecimal("5")));
    }

    @Test
    void avgRateAveragesNonNull() {
        assertEquals(new BigDecimal("0.500000"), CumulativeCalculator.avgRate(
                List.of(new BigDecimal("0.4"), new BigDecimal("0.6"))));
        assertNull(CumulativeCalculator.avgRate(List.of()));
    }
}
