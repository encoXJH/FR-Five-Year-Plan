package edu.fzrj.metrics.module.indicator.service.support;

import edu.fzrj.metrics.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IndicatorRulesTest {

    @Test
    void 计量口径组合_合法组合全部通过() {
        assertDoesNotThrow(() -> IndicatorRules.requireValidCombination("NUMBER", "INCREMENT"));
        assertDoesNotThrow(() -> IndicatorRules.requireValidCombination("NUMBER", "STOCK"));
        assertDoesNotThrow(() -> IndicatorRules.requireValidCombination("BOOLEAN", "BOOLEAN"));
        assertDoesNotThrow(() -> IndicatorRules.requireValidCombination("PERCENT_QUANTIFIABLE", "GROWTH"));
        assertDoesNotThrow(() -> IndicatorRules.requireValidCombination("PERCENT_QUANTIFIABLE", "RATIO"));
        assertDoesNotThrow(() -> IndicatorRules.requireValidCombination("PERCENT_DIRECT", "DIRECT_PERCENT"));
    }

    @Test
    void 计量口径组合_非法组合抛40007() {
        assertCode(40007, () -> IndicatorRules.requireValidCombination("NUMBER", "GROWTH"));
        assertCode(40007, () -> IndicatorRules.requireValidCombination("NUMBER", "RATIO"));
        assertCode(40007, () -> IndicatorRules.requireValidCombination("BOOLEAN", "INCREMENT"));
        assertCode(40007, () -> IndicatorRules.requireValidCombination("PERCENT_QUANTIFIABLE", "STOCK"));
        assertCode(40007, () -> IndicatorRules.requireValidCombination("PERCENT_DIRECT", "RATIO"));
    }

    @Test
    void INCREMENT守恒_年和等于总量通过() {
        assertDoesNotThrow(() -> IndicatorRules.requireIncrementSum("INCREMENT",
                new BigDecimal("5"), List.of(bd("1"), bd("1"), bd("1"), bd("1"), bd("1"))));
    }

    @Test
    void INCREMENT守恒_大数小数位数不同按值比较() {
        assertDoesNotThrow(() -> IndicatorRules.requireIncrementSum("INCREMENT",
                new BigDecimal("0.20"), List.of(bd("0.10"), bd("0.1"))));
        assertDoesNotThrow(() -> IndicatorRules.requireIncrementSum("INCREMENT",
                new BigDecimal("1.1000"), List.of(bd("0.5"), bd("0.6"))));
    }

    @Test
    void INCREMENT守恒_不等抛40002() {
        assertCode(40002, () -> IndicatorRules.requireIncrementSum("INCREMENT",
                new BigDecimal("5"), List.of(bd("1"), bd("1"), bd("1"), bd("1"), bd("2"))));
    }

    @Test
    void 比率类口径不做求和校验() {
        assertDoesNotThrow(() -> IndicatorRules.requireIncrementSum("RATIO",
                new BigDecimal("0.6"), List.of(bd("0.5"), bd("0.7"))));
        assertDoesNotThrow(() -> IndicatorRules.requireIncrementSum("GROWTH",
                new BigDecimal("0.15"), List.of(bd("100"), bd("200"))));
        assertDoesNotThrow(() -> IndicatorRules.requireIncrementSum("DIRECT_PERCENT",
                null, List.of(bd("0.9"))));
        assertTrue(IndicatorRules.isRatioCaliber("RATIO"));
        assertFalse(IndicatorRules.isRatioCaliber("INCREMENT"));
    }

    @Test
    void AUTO_SUM守恒_相等通过() {
        assertDoesNotThrow(() -> IndicatorRules.requireAutoSumConservation("INCREMENT", "AUTO_SUM",
                new BigDecimal("5"), List.of(bd("1"), bd("4"))));
    }

    @Test
    void AUTO_SUM守恒_不等抛40003() {
        assertCode(40003, () -> IndicatorRules.requireAutoSumConservation("INCREMENT", "AUTO_SUM",
                new BigDecimal("5"), List.of(bd("1"), bd("1"))));
    }

    @Test
    void AUTO_SUM守恒_手工认定与比率类跳过() {
        assertDoesNotThrow(() -> IndicatorRules.requireAutoSumConservation("INCREMENT", "MANUAL",
                new BigDecimal("5"), List.of(bd("1"))));
        assertDoesNotThrow(() -> IndicatorRules.requireAutoSumConservation("GROWTH", "AUTO_SUM",
                new BigDecimal("115"), List.of(bd("100"))));
    }

    @Test
    void 量化性质_A加天然量化A由标志决定() {
        assertTrue(IndicatorRules.isQuantifiable("A", true));
        assertFalse(IndicatorRules.isQuantifiable("A", false));
        assertFalse(IndicatorRules.isQuantifiable("A", null));
        assertTrue(IndicatorRules.isQuantifiable("A_PLUS", null));
    }

    @Test
    void A加挂载_可量化A通过() {
        assertDoesNotThrow(() -> IndicatorRules.requireAPlusAttachable("A", true));
    }

    @Test
    void A加挂载_不可量化或层级错误抛40006() {
        assertCode(40006, () -> IndicatorRules.requireAPlusAttachable("A", false));
        assertCode(40006, () -> IndicatorRules.requireAPlusAttachable("A", null));
        assertCode(40006, () -> IndicatorRules.requireAPlusAttachable("A_PLUS", true));
    }

    @Test
    void 量化定义_不可量化A跳过校验() {
        assertDoesNotThrow(() -> IndicatorRules.requireQuantitativeDefinition(
                "A", false, null, null, null, null, null));
    }

    @Test
    void 量化定义_可量化指标必填且A必须指定汇总方式() {
        assertDoesNotThrow(() -> IndicatorRules.requireQuantitativeDefinition(
                "A", true, "NUMBER", "INCREMENT", "项", "HIGHER_BETTER", "AUTO_SUM"));
        assertDoesNotThrow(() -> IndicatorRules.requireQuantitativeDefinition(
                "A_PLUS", null, "NUMBER", "INCREMENT", "项", "HIGHER_BETTER", null));
        assertCode(10001, () -> IndicatorRules.requireQuantitativeDefinition(
                "A", true, null, "INCREMENT", "项", "HIGHER_BETTER", "AUTO_SUM"));
        assertCode(10001, () -> IndicatorRules.requireQuantitativeDefinition(
                "A", true, "NUMBER", "INCREMENT", "项", "HIGHER_BETTER", null));
    }

    @Test
    void 年度目标_仅量化指标允许设置() {
        assertDoesNotThrow(() -> IndicatorRules.requireYearTargetsAllowed("A", true));
        assertDoesNotThrow(() -> IndicatorRules.requireYearTargetsAllowed("A_PLUS", null));
        assertCode(40006, () -> IndicatorRules.requireYearTargetsAllowed("A", false));
        assertCode(40006, () -> IndicatorRules.requireYearTargetsAllowed("A", null));
    }

    @Test
    void BOOLEAN计划完成年_恰好一年通过() {
        assertDoesNotThrow(() -> IndicatorRules.requireBooleanSingleYear(
                Map.of(2026, BigDecimal.ONE)));
        Map<Integer, BigDecimal> targets = new java.util.HashMap<>();
        targets.put(2026, null);
        targets.put(2028, BigDecimal.ONE);
        assertDoesNotThrow(() -> IndicatorRules.requireBooleanSingleYear(targets));
    }

    @Test
    void BOOLEAN计划完成年_零年或多年抛10001() {
        assertCode(10001, () -> IndicatorRules.requireBooleanSingleYear(Map.of()));
        assertCode(10001, () -> IndicatorRules.requireBooleanSingleYear(
                Map.of(2026, BigDecimal.ONE, 2027, BigDecimal.ONE)));
    }

    @Test
    void 年度集合必须恰好覆盖计划起止年() {
        assertDoesNotThrow(() -> IndicatorRules.requirePlanYears(
                List.of(2027, 2026, 2030, 2028, 2029), 2026, 2030));
        assertCode(10001, () -> IndicatorRules.requirePlanYears(List.of(2026, 2027), 2026, 2030));
        assertCode(10001, () -> IndicatorRules.requirePlanYears(
                List.of(2026, 2026, 2027, 2028, 2029, 2030), 2026, 2030));
        assertCode(10001, () -> IndicatorRules.requirePlanYears(
                List.of(2025, 2026, 2027, 2028, 2029, 2030), 2026, 2030));
    }

    @Test
    void 目标值规范化_BOOLEAN布尔转1其余为null() {
        assertEquals(0, BigDecimal.ONE.compareTo(IndicatorRules.normalizeTarget(true, "BOOLEAN")));
        assertNull(IndicatorRules.normalizeTarget(false, "BOOLEAN"));
        assertNull(IndicatorRules.normalizeTarget(null, "BOOLEAN"));
    }

    @Test
    void 目标值规范化_数值口径接受数字拒绝布尔() {
        assertEquals(0, new BigDecimal("0.15").compareTo(IndicatorRules.normalizeTarget(0.15, "RATIO")));
        assertCode(10001, () -> IndicatorRules.normalizeTarget(true, "INCREMENT"));
        assertCode(10001, () -> IndicatorRules.normalizeTarget("abc", "INCREMENT"));
    }

    @Test
    void 编号生成_A类取最大加一() {
        assertEquals("A01", IndicatorRules.nextACode(Set.of()));
        assertEquals("A04", IndicatorRules.nextACode(Set.of("A01", "A03", "A01-2", "A02-9")));
        assertEquals("A10", IndicatorRules.nextACode(Set.of("A09")));
    }

    @Test
    void 编号生成_A加取父编号下最大加一() {
        assertEquals("A01-1", IndicatorRules.nextAPlusCode("A01", Set.of()));
        assertEquals("A01-3", IndicatorRules.nextAPlusCode("A01", Set.of("A01-1", "A01-2", "A02-9")));
    }

    @Test
    void 增长率计算_首年用基线值() {
        Map<Integer, BigDecimal> totals = Map.of(2026, bd("110"), 2027, bd("121"));
        // 2026 是首年：上年总量 = 基线值 100
        BigDecimal previous = IndicatorRules.previousTotalForGrowth(totals, 2026, 2026, bd("100"));
        assertEquals(0, bd("100").compareTo(previous));
        BigDecimal rate = IndicatorRules.growthRate(bd("110"), previous);
        assertEquals(0, new BigDecimal("0.1").compareTo(rate.stripTrailingZeros()));
        // 非首年：用上一年总量（2027 的上年为 2026 的 110）
        BigDecimal previous2027 = IndicatorRules.previousTotalForGrowth(totals, 2027, 2026, bd("100"));
        assertEquals(0, bd("110").compareTo(previous2027));
    }

    @Test
    void 增长率计算_上年为空或零返回null() {
        assertNull(IndicatorRules.growthRate(bd("110"), null));
        assertNull(IndicatorRules.growthRate(bd("110"), BigDecimal.ZERO));
        assertNull(IndicatorRules.growthRate(null, bd("100")));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertCode(int code, Runnable runnable) {
        BizException e = assertThrows(BizException.class, runnable::run);
        assertEquals(code, e.getErrorCode().getCode(),
                "期望错误码 " + code + " 实际 " + e.getErrorCode().getCode());
    }
}
