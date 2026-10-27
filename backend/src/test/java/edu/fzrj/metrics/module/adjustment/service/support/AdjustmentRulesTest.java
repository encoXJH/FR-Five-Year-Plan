package edu.fzrj.metrics.module.adjustment.service.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjustmentRulesTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ---------------- 70006 适用范围矩阵 ----------------

    @Test
    void 适用范围_延期支持增量与是否型() {
        assertTrue(AdjustmentRules.supports("INCREMENT", "POSTPONE"));
        assertTrue(AdjustmentRules.supports("BOOLEAN", "POSTPONE"));
    }

    @Test
    void 适用范围_延期不支持存量与比率类() {
        assertFalse(AdjustmentRules.supports("STOCK", "POSTPONE"));
        assertFalse(AdjustmentRules.supports("GROWTH", "POSTPONE"));
        assertFalse(AdjustmentRules.supports("RATIO", "POSTPONE"));
        assertFalse(AdjustmentRules.supports("DIRECT_PERCENT", "POSTPONE"));
    }

    @Test
    void 适用范围_转移与延期加转移仅支持增量() {
        assertTrue(AdjustmentRules.supports("INCREMENT", "TRANSFER"));
        assertTrue(AdjustmentRules.supports("INCREMENT", "POSTPONE_TRANSFER"));
        assertFalse(AdjustmentRules.supports("BOOLEAN", "TRANSFER"));
        assertFalse(AdjustmentRules.supports("BOOLEAN", "POSTPONE_TRANSFER"));
        assertFalse(AdjustmentRules.supports("STOCK", "TRANSFER"));
        assertFalse(AdjustmentRules.supports("RATIO", "POSTPONE_TRANSFER"));
    }

    @Test
    void 适用范围_目标调整支持全部口径() {
        for (String caliber : List.of("INCREMENT", "STOCK", "GROWTH", "RATIO", "DIRECT_PERCENT", "BOOLEAN")) {
            assertTrue(AdjustmentRules.supports(caliber, "TARGET_CHANGE"), caliber);
        }
    }

    @Test
    void 适用范围_未知类型不支持() {
        assertFalse(AdjustmentRules.supports("INCREMENT", "FOO"));
        assertFalse(AdjustmentRules.isKnownType("FOO"));
        assertTrue(AdjustmentRules.isKnownType("POSTPONE_TRANSFER"));
    }

    // ---------------- 审批链 ----------------

    @Test
    void 审批链_延期无接收确认环节() {
        assertEquals(List.of("OWNER_REVIEW", "LEADER_APPROVAL"),
                AdjustmentRules.nodesOf("POSTPONE", true));
    }

    @Test
    void 审批链_转移按计划配置决定是否接收确认() {
        assertEquals(List.of("OWNER_REVIEW", "RECEIVER_CONFIRM", "LEADER_APPROVAL"),
                AdjustmentRules.nodesOf("TRANSFER", true));
        assertEquals(List.of("OWNER_REVIEW", "LEADER_APPROVAL"),
                AdjustmentRules.nodesOf("TRANSFER", false));
        assertEquals(List.of("OWNER_REVIEW", "RECEIVER_CONFIRM", "LEADER_APPROVAL"),
                AdjustmentRules.nodesOf("POSTPONE_TRANSFER", true));
    }

    @Test
    void 审批链_目标调整仅校领导审批() {
        assertEquals(List.of("LEADER_APPROVAL"), AdjustmentRules.nodesOf("TARGET_CHANGE", true));
    }

    // ---------------- 审批状态机 ----------------

    @Test
    void 状态机_初审通过后转移类进入接收确认_延期类进入终审() {
        assertEquals("PENDING_RECEIVER_CONFIRM", AdjustmentRules.statusOnDecision(
                "TRANSFER", "PENDING_OWNER_REVIEW", "APPROVE", true));
        assertEquals("PENDING_LEADER_APPROVAL", AdjustmentRules.statusOnDecision(
                "POSTPONE", "PENDING_OWNER_REVIEW", "APPROVE", true));
    }

    @Test
    void 状态机_接收确认通过进入终审_终审通过生效() {
        assertEquals("PENDING_LEADER_APPROVAL", AdjustmentRules.statusOnDecision(
                "TRANSFER", "PENDING_RECEIVER_CONFIRM", "APPROVE", true));
        assertEquals("APPROVED", AdjustmentRules.statusOnDecision(
                "TRANSFER", "PENDING_LEADER_APPROVAL", "APPROVE", true));
    }

    @Test
    void 状态机_任一环节驳回整个申请结束() {
        assertEquals("REJECTED", AdjustmentRules.statusOnDecision(
                "TRANSFER", "PENDING_OWNER_REVIEW", "REJECT", true));
        assertEquals("REJECTED", AdjustmentRules.statusOnDecision(
                "TRANSFER", "PENDING_RECEIVER_CONFIRM", "REJECT", true));
        assertEquals("REJECTED", AdjustmentRules.statusOnDecision(
                "POSTPONE", "PENDING_LEADER_APPROVAL", "REJECT", true));
    }

    @Test
    void 状态机_已结束状态不再流转() {
        assertNull(AdjustmentRules.statusOnDecision("TRANSFER", "APPROVED", "APPROVE", true));
        assertNull(AdjustmentRules.statusOnDecision("TRANSFER", "REJECTED", "APPROVE", true));
        assertNull(AdjustmentRules.statusOnDecision("TRANSFER", "WITHDRAWN", "APPROVE", true));
    }

    @Test
    void 状态机_目标调整终审直接生效() {
        assertEquals("APPROVED", AdjustmentRules.statusOnDecision(
                "TARGET_CHANGE", "PENDING_LEADER_APPROVAL", "APPROVE", true));
    }

    @Test
    void 撤回_仅进行中状态可撤回() {
        assertTrue(AdjustmentRules.canWithdraw("PENDING_OWNER_REVIEW"));
        assertTrue(AdjustmentRules.canWithdraw("PENDING_RECEIVER_CONFIRM"));
        assertTrue(AdjustmentRules.canWithdraw("PENDING_LEADER_APPROVAL"));
        assertFalse(AdjustmentRules.canWithdraw("APPROVED"));
        assertFalse(AdjustmentRules.canWithdraw("REJECTED"));
        assertFalse(AdjustmentRules.canWithdraw("WITHDRAWN"));
    }

    // ---------------- 70001 尚未完成的量（含 70004 冻结占用） ----------------

    @Test
    void 剩余量_目标减已完成减已冻结() {
        assertEquals(0, bd("5").compareTo(AdjustmentRules.remaining(bd("10"), bd("3"), bd("2"))));
    }

    @Test
    void 剩余量_空值按零参与() {
        assertEquals(0, bd("10").compareTo(AdjustmentRules.remaining(bd("10"), null, null)));
        assertEquals(0, bd("0").compareTo(AdjustmentRules.remaining(null, null, null)));
    }

    @Test
    void 剩余量_额度已冻结时不足_并发重复申请的判定基础() {
        // 目标 10、已完成 2：首次冻结 8 后剩余 0，再次申请 1 即超额
        BigDecimal remaining = AdjustmentRules.remaining(bd("10"), bd("2"), bd("8"));
        assertTrue(bd("1").compareTo(remaining) > 0);
        assertTrue(bd("8").compareTo(AdjustmentRules.remaining(bd("10"), bd("2"), bd("0"))) <= 0);
    }

    // ---------------- 70003 目标年份 ----------------

    @Test
    void 年份_超出规划期() {
        assertEquals("目标年份 2031 超出规划期（2026–2030）",
                AdjustmentRules.yearError("POSTPONE", 2026, 2031, 2026, 2030));
        assertEquals("目标年份 2025 超出规划期（2026–2030）",
                AdjustmentRules.yearError("TRANSFER", 2026, 2025, 2026, 2030));
    }

    @Test
    void 年份_早于原年份() {
        assertEquals("目标年份 2026 早于原年份 2027",
                AdjustmentRules.yearError("TRANSFER", 2027, 2026, 2026, 2030));
    }

    @Test
    void 年份_延期必须晚于原年份_转移允许同年() {
        assertEquals("延期的目标年份必须晚于原年份 2026",
                AdjustmentRules.yearError("POSTPONE", 2026, 2026, 2026, 2030));
        assertEquals("延期的目标年份必须晚于原年份 2026",
                AdjustmentRules.yearError("POSTPONE_TRANSFER", 2026, 2026, 2026, 2030));
        assertNull(AdjustmentRules.yearError("TRANSFER", 2026, 2026, 2026, 2030));
        assertNull(AdjustmentRules.yearError("POSTPONE", 2026, 2030, 2026, 2030));
    }

    // ---------------- 试算（快照 after） ----------------

    @Test
    void 试算_延期在同一任务跨年移动() {
        List<AdjustmentRules.TargetEntry> before = List.of(
                new AdjustmentRules.TargetEntry("A01-2-C03", null, 2026, bd("1")),
                new AdjustmentRules.TargetEntry("A01-2-C03", null, 2027, bd("1")));
        List<AdjustmentRules.TargetEntry> after = AdjustmentRules.applyMoves(before,
                List.of(new AdjustmentRules.MoveItem("A01-2-C03", 2026, "A01-2-C03", 2027, bd("1"))));
        assertEquals(bd("0"), after.get(0).target());
        assertEquals(bd("2"), after.get(1).target());
        assertEquals(2026, after.get(0).year());
        assertEquals(2027, after.get(1).year());
    }

    @Test
    void 试算_转移给无任务的接收方时按零新建条目_自动新建任务语义() {
        List<AdjustmentRules.TargetEntry> before = List.of(
                new AdjustmentRules.TargetEntry("A01-2-C03", null, 2026, bd("1")));
        List<AdjustmentRules.TargetEntry> after = AdjustmentRules.applyMoves(before,
                List.of(new AdjustmentRules.MoveItem("A01-2-C03", 2026, "A01-2-C05", 2026, bd("1"))));
        assertEquals(2, after.size());
        assertEquals(bd("0"), after.get(0).target());
        assertEquals("A01-2-C05", after.get(1).taskCode());
        assertEquals(bd("1"), after.get(1).target());
    }

    @Test
    void 试算_延期加转移且多条累加() {
        List<AdjustmentRules.TargetEntry> before = List.of(
                new AdjustmentRules.TargetEntry("A01-2-C03", null, 2026, bd("2")),
                new AdjustmentRules.TargetEntry("A01-2-C03", null, 2027, bd("0")),
                new AdjustmentRules.TargetEntry("A01-2-C05", null, 2027, bd("1")));
        List<AdjustmentRules.TargetEntry> after = AdjustmentRules.applyMoves(before, List.of(
                new AdjustmentRules.MoveItem("A01-2-C03", 2026, "A01-2-C05", 2027, bd("1")),
                new AdjustmentRules.MoveItem("A01-2-C03", 2026, "A01-2-C03", 2027, bd("1"))));
        assertEquals(bd("0"), after.get(0).target());
        assertEquals(bd("1"), after.get(1).target());
        assertEquals(bd("2"), after.get(2).target());
    }

    @Test
    void 试算_转出条目不存在抛异常() {
        assertThrows(IllegalArgumentException.class, () -> AdjustmentRules.applyMoves(List.of(),
                List.of(new AdjustmentRules.MoveItem("A01-2-C03", 2026, "A01-2-C03", 2027, bd("1")))));
    }

    @Test
    void 试算_目标调整按指标与年覆盖_总量条目year为空() {
        List<AdjustmentRules.TargetEntry> before = List.of(
                new AdjustmentRules.TargetEntry(null, "A02-1", 2026, bd("0.5")),
                new AdjustmentRules.TargetEntry(null, "A02-1", null, bd("0.9")));
        List<AdjustmentRules.TargetEntry> after = AdjustmentRules.applySetItems(before, List.of(
                new AdjustmentRules.SetItem("A02-1", 2026, bd("0.6")),
                new AdjustmentRules.SetItem("A02-1", null, bd("0.95"))));
        assertEquals(2, after.size());
        assertEquals(bd("0.6"), after.get(0).target());
        assertEquals(bd("0.95"), after.get(1).target());
    }

    // ---------------- 70002 守恒 ----------------

    @Test
    void 守恒_逐年合计相等通过() {
        Map<Integer, BigDecimal> child = Map.of(2026, bd("1"), 2027, bd("2"));
        Map<Integer, BigDecimal> parent = Map.of(2026, bd("1"), 2027, bd("2"));
        assertTrue(AdjustmentRules.conservationErrors("INCREMENT", "A+ 目标", child, parent).isEmpty());
    }

    @Test
    void 守恒_逐年差值逐条报告() {
        Map<Integer, BigDecimal> child = Map.of(2026, bd("1"), 2027, bd("1"));
        Map<Integer, BigDecimal> parent = Map.of(2026, bd("2"), 2027, bd("1"));
        List<String> errors = AdjustmentRules.conservationErrors("INCREMENT", "A+ 目标", child, parent);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("2026"));
        assertTrue(errors.get(0).contains("1"));
        assertTrue(errors.get(0).contains("2"));
    }

    @Test
    void 守恒_比率类不做求和校验() {
        Map<Integer, BigDecimal> child = Map.of(2026, bd("0.1"));
        Map<Integer, BigDecimal> parent = Map.of(2026, bd("0.9"), 2027, bd("0.9"));
        assertTrue(AdjustmentRules.conservationErrors("RATIO", "A+ 目标", child, parent).isEmpty());
        assertTrue(AdjustmentRules.conservationErrors("GROWTH", "A+ 目标", child, parent).isEmpty());
        assertTrue(AdjustmentRules.conservationErrors("DIRECT_PERCENT", "A+ 目标", child, parent).isEmpty());
    }

    @Test
    void 守恒_缺失年度按零参与() {
        Map<Integer, BigDecimal> child = new HashMap<>();
        Map<Integer, BigDecimal> parent = Map.of(2027, bd("1"));
        assertEquals(1, AdjustmentRules.conservationErrors("BOOLEAN", "A+ 目标", child, parent).size());
    }

    // ---------------- 40002 语义（年和 = 总量） ----------------

    @Test
    void 年和_增量型按年和等于总量() {
        assertTrue(AdjustmentRules.yearSumMatchesTotal("INCREMENT",
                Map.of(2026, bd("1"), 2027, bd("2")), bd("3")));
        assertFalse(AdjustmentRules.yearSumMatchesTotal("INCREMENT",
                Map.of(2026, bd("1"), 2027, bd("2")), bd("4")));
        assertFalse(AdjustmentRules.yearSumMatchesTotal("INCREMENT", Map.of(), null));
    }

    @Test
    void 年和_比率类不校验() {
        assertTrue(AdjustmentRules.yearSumMatchesTotal("RATIO", Map.of(2026, bd("0.1")), bd("9")));
        assertTrue(AdjustmentRules.yearSumMatchesTotal("STOCK", Map.of(2026, bd("1")), bd("9")));
    }

    // ---------------- 70005 处理人判定 ----------------

    @Test
    void 处理人_初审由期望负责人处理() {
        assertTrue(AdjustmentRules.canDecide("OWNER_REVIEW", 203L, null, 203L, null,
                List.of("SCHOOL_OWNER")));
        assertFalse(AdjustmentRules.canDecide("OWNER_REVIEW", 203L, null, 204L, null,
                List.of("SCHOOL_OWNER")));
        assertFalse(AdjustmentRules.canDecide("OWNER_REVIEW", 203L, null, 1L, null, List.of("ADMIN")));
    }

    @Test
    void 处理人_初审负责人为空时仅管理员可处理() {
        assertTrue(AdjustmentRules.canDecide("OWNER_REVIEW", null, null, 1L, null, List.of("ADMIN")));
        assertFalse(AdjustmentRules.canDecide("OWNER_REVIEW", null, null, 203L, null,
                List.of("SCHOOL_OWNER")));
    }

    @Test
    void 处理人_接收确认须接收单元的学院负责人() {
        assertTrue(AdjustmentRules.canDecide("RECEIVER_CONFIRM", null, 105L, 201L, 105L,
                List.of("UNIT_OWNER")));
        assertFalse(AdjustmentRules.canDecide("RECEIVER_CONFIRM", null, 105L, 201L, 103L,
                List.of("UNIT_OWNER")));
        assertFalse(AdjustmentRules.canDecide("RECEIVER_CONFIRM", null, 105L, 1L, 105L,
                List.of("ADMIN")));
    }

    @Test
    void 处理人_终审须校领导() {
        assertTrue(AdjustmentRules.canDecide("LEADER_APPROVAL", null, null, 202L, null,
                List.of("LEADER")));
        assertFalse(AdjustmentRules.canDecide("LEADER_APPROVAL", null, null, 203L, null,
                List.of("SCHOOL_OWNER")));
        assertFalse(AdjustmentRules.canDecide("LEADER_APPROVAL", null, null, 1L, null, List.of("ADMIN")));
    }
}
