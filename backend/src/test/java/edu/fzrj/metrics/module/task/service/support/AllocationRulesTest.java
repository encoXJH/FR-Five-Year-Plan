package edu.fzrj.metrics.module.task.service.support;

import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.task.dto.TaskDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationRulesTest {

    private static final List<Integer> YEARS = List.of(2026, 2027, 2028, 2029, 2030);

    @Test
    void 任务编号生成_指标编号加单元编码() {
        assertEquals("A01-2-C03", AllocationRules.taskCode("A01-2", "C03"));
        assertEquals("A05-S01", AllocationRules.taskCode("A05", "S01"));
    }

    @Test
    void 分解矩阵_仅APlus可生成B任务() {
        assertTrue(AllocationRules.isAllocatable("A_PLUS"));
        assertFalse(AllocationRules.isAllocatable("A"));
        assertFalse(AllocationRules.isAllocatable("B"));
        assertFalse(AllocationRules.isAllocatable(null));
    }

    @Test
    void 求和口径_INCREMENT_STOCK_BOOLEAN需要求和() {
        assertTrue(AllocationRules.requiresAllocationSum("INCREMENT"));
        assertTrue(AllocationRules.requiresAllocationSum("STOCK"));
        assertTrue(AllocationRules.requiresAllocationSum("BOOLEAN"));
    }

    @Test
    void 求和口径_比率类不做求和校验() {
        assertFalse(AllocationRules.requiresAllocationSum("GROWTH"));
        assertFalse(AllocationRules.requiresAllocationSum("RATIO"));
        assertFalse(AllocationRules.requiresAllocationSum("DIRECT_PERCENT"));
    }

    @Test
    void 合计diff_平衡时每年diff为零() {
        Map<Integer, BigDecimal> indicator = Map.of(2026, bd("1"), 2027, bd("1"), 2028, bd("0"),
                2029, bd("1"), 2030, bd("1"));
        Map<Integer, BigDecimal> allocated = Map.of(2026, bd("1"), 2027, bd("1"), 2028, bd("0"),
                2029, bd("1"), 2030, bd("1"));
        List<TaskDtos.SummaryItem> summary = AllocationRules.buildSummary(YEARS, indicator, allocated);
        assertEquals(5, summary.size());
        assertTrue(summary.stream().allMatch(s -> s.diff().compareTo(BigDecimal.ZERO) == 0));
        assertTrue(AllocationRules.isBalanced("INCREMENT", summary));
        assertTrue(AllocationRules.balanceErrors("A01-2", "INCREMENT", summary).isEmpty());
    }

    @Test
    void 合计diff_逐年给出差值且不平衡() {
        Map<Integer, BigDecimal> indicator = Map.of(2026, bd("2"), 2027, bd("1"), 2028, bd("0"),
                2029, bd("0"), 2030, bd("1"));
        Map<Integer, BigDecimal> allocated = Map.of(2026, bd("1"), 2027, bd("1"), 2028, bd("0"),
                2029, bd("0"), 2030, bd("3"));
        List<TaskDtos.SummaryItem> summary = AllocationRules.buildSummary(YEARS, indicator, allocated);
        assertFalse(AllocationRules.isBalanced("INCREMENT", summary));
        List<FieldError> errors = AllocationRules.balanceErrors("A01-2", "INCREMENT", summary);
        assertEquals(2, errors.size());
        assertEquals("A01-2.yearTargets[2026]", errors.get(0).getField());
        assertTrue(errors.get(0).getMessage().contains("A01-2 在 2026 年"));
        assertTrue(errors.get(0).getMessage().contains("各学院合计 1"));
        assertTrue(errors.get(0).getMessage().contains("A+ 目标 2"));
        assertEquals("A01-2.yearTargets[2030]", errors.get(1).getField());
    }

    @Test
    void 合计diff_无分解行时合计按零参与判定() {
        Map<Integer, BigDecimal> indicator = Map.of(2026, bd("1"), 2027, bd("1"), 2028, bd("1"),
                2029, bd("1"), 2030, bd("1"));
        List<TaskDtos.SummaryItem> summary = AllocationRules.buildSummary(YEARS, indicator, Map.of());
        assertFalse(AllocationRules.isBalanced("INCREMENT", summary));
        assertEquals(0, bd("-1").compareTo(summary.get(0).diff()));
        assertEquals(5, AllocationRules.balanceErrors("A01-2", "INCREMENT", summary).size());
    }

    @Test
    void 合计diff_比率类口径不做求和校验视为平衡() {
        Map<Integer, BigDecimal> indicator = Map.of(2026, bd("0.5"), 2027, bd("0.6"), 2028, bd("0.7"),
                2029, bd("0.8"), 2030, bd("0.9"));
        Map<Integer, BigDecimal> allocated = Map.of(2026, bd("0.1"));
        List<TaskDtos.SummaryItem> summary = AllocationRules.buildSummary(YEARS, indicator, allocated);
        assertTrue(AllocationRules.isBalanced("RATIO", summary));
        assertTrue(AllocationRules.balanceErrors("A02-1", "RATIO", summary).isEmpty());
        assertTrue(AllocationRules.isBalanced("GROWTH", summary));
        assertTrue(AllocationRules.isBalanced("DIRECT_PERCENT", summary));
    }

    @Test
    void BOOLEAN完成年_分解落在同一年平衡() {
        Map<Integer, BigDecimal> indicator = new HashMap<>();
        indicator.put(2027, BigDecimal.ONE);
        Map<Integer, BigDecimal> allocated = new HashMap<>();
        allocated.put(2027, BigDecimal.ONE);
        List<TaskDtos.SummaryItem> summary = AllocationRules.buildSummary(YEARS, indicator, allocated);
        assertTrue(AllocationRules.isBalanced("BOOLEAN", summary));
    }

    @Test
    void BOOLEAN完成年_分解落在不同年份不平衡() {
        Map<Integer, BigDecimal> indicator = new HashMap<>();
        indicator.put(2027, BigDecimal.ONE);
        Map<Integer, BigDecimal> allocated = new HashMap<>();
        allocated.put(2028, BigDecimal.ONE);
        List<TaskDtos.SummaryItem> summary = AllocationRules.buildSummary(YEARS, indicator, allocated);
        assertFalse(AllocationRules.isBalanced("BOOLEAN", summary));
        List<FieldError> errors = AllocationRules.balanceErrors("A03", "BOOLEAN", summary);
        assertEquals(2, errors.size());
    }

    @Test
    void 下发校验_未定稿抛50005已定稿通过() {
        assertCode(50005, () -> AllocationRules.requireFinalized(null));
        assertDoesNotThrow(() -> AllocationRules.requireFinalized(LocalDateTime.now()));
    }

    @Test
    void 下发校验_缺负责人生成任务编号明细() {
        List<FieldError> errors = AllocationRules.missingOwnerErrors(List.of("A01-2-C03", "A01-2-C05"));
        assertEquals(2, errors.size());
        assertEquals("tasks[A01-2-C03].ownerId", errors.get(0).getField());
        assertTrue(errors.get(0).getMessage().contains("A01-2-C03"));
    }

    @Test
    void 确认接收_已下发转执行中_重复接收幂等() {
        assertEquals("IN_PROGRESS", AllocationRules.statusOnAccept("DISTRIBUTED"));
        assertEquals("IN_PROGRESS", AllocationRules.statusOnAccept("IN_PROGRESS"));
    }

    @Test
    void 确认接收_待下发状态抛60001() {
        assertCode(60001, () -> AllocationRules.statusOnAccept("PENDING_DISTRIBUTION"));
        assertCode(60001, () -> AllocationRules.statusOnAccept("CLOSED"));
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
