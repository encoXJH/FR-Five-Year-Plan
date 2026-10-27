package edu.fzrj.metrics.module.task.service.support;

import edu.fzrj.metrics.common.api.FieldError;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.task.dto.TaskDtos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分解矩阵 / 下发纯逻辑规则（PRD 4.5、5.3，API 约定 15.4 / 16.3）：
 * 任务编号、逐年合计与 diff、求和口径判定、下发校验链、接收状态机。
 * 不依赖 Spring，便于单元测试。
 */
public final class AllocationRules {

    private AllocationRules() {
    }

    /** B 任务编号：{指标编号}-{单元编码}，如 A01-2-C03 */
    public static String taskCode(String indicatorCode, String unitCode) {
        return indicatorCode + "-" + unitCode;
    }

    /**
     * 目标需要逐年求和校验的口径（PRD 4.5：比率类 GROWTH / RATIO / DIRECT_PERCENT 不做求和校验）。
     * INCREMENT / STOCK / BOOLEAN 需要：Σ各 B 年度目标 = A+ 当年目标。
     */
    public static boolean requiresAllocationSum(String statCaliber) {
        return "INCREMENT".equals(statCaliber) || "STOCK".equals(statCaliber) || "BOOLEAN".equals(statCaliber);
    }

    /** 仅 A+ 可生成分解矩阵 */
    public static boolean isAllocatable(String level) {
        return "A_PLUS".equals(level);
    }

    /**
     * 按年度汇总合计并计算 diff（diff = 合计 − 指标目标，空值按 0 参与计算；展示值保留原始 null）。
     */
    public static List<TaskDtos.SummaryItem> buildSummary(List<Integer> years,
                                                          Map<Integer, BigDecimal> indicatorTargets,
                                                          Map<Integer, BigDecimal> allocatedTargets) {
        List<TaskDtos.SummaryItem> summary = new ArrayList<>();
        for (Integer year : years) {
            BigDecimal indicatorTarget = indicatorTargets.get(year);
            BigDecimal allocated = allocatedTargets.get(year);
            BigDecimal diff = orZero(allocated).subtract(orZero(indicatorTarget));
            summary.add(new TaskDtos.SummaryItem(year, indicatorTarget, allocated, diff));
        }
        return summary;
    }

    /** 矩阵是否平衡：求和口径要求每年 diff 全为 0；比率类口径不做求和校验，视为平衡 */
    public static boolean isBalanced(String statCaliber, List<TaskDtos.SummaryItem> summary) {
        if (!requiresAllocationSum(statCaliber)) {
            return true;
        }
        return summary.stream().allMatch(s -> s.diff().compareTo(BigDecimal.ZERO) == 0);
    }

    /**
     * 下发前平衡校验（50001）：求和口径逐年比对，返回逐条错误明细（指标编号 + 年份 + 差值）。
     */
    public static List<FieldError> balanceErrors(String indicatorCode, String statCaliber,
                                                 List<TaskDtos.SummaryItem> summary) {
        List<FieldError> errors = new ArrayList<>();
        if (!requiresAllocationSum(statCaliber)) {
            return errors;
        }
        for (TaskDtos.SummaryItem item : summary) {
            if (item.diff().compareTo(BigDecimal.ZERO) != 0) {
                errors.add(new FieldError(
                        indicatorCode + ".yearTargets[" + item.year() + "]",
                        indicatorCode + " 在 " + item.year() + " 年：各学院合计 "
                                + orZero(item.allocatedTarget()).stripTrailingZeros().toPlainString()
                                + "，A+ 目标 " + orZero(item.indicatorTarget()).stripTrailingZeros().toPlainString()));
            }
        }
        return errors;
    }

    /** 下发前置：指标体系必须已定稿（50005） */
    public static void requireFinalized(java.time.LocalDateTime finalizedAt) {
        if (finalizedAt == null) {
            throw new BizException(ErrorCode.INDICATOR_NOT_FINALIZED);
        }
    }

    /** 下发前置：每条 B 必须有学院项目负责人（50002），errors 逐条列出任务编号 */
    public static List<FieldError> missingOwnerErrors(List<String> taskCodesWithoutOwner) {
        return taskCodesWithoutOwner.stream()
                .map(code -> new FieldError("tasks[" + code + "].ownerId",
                        code + " 未指定学院项目负责人"))
                .toList();
    }

    /**
     * 确认接收状态机（PRD 6.2：已下发 → 执行中：学院确认接收）。
     * DISTRIBUTED → IN_PROGRESS；IN_PROGRESS（已接收过）→ 原样返回（幂等）；其他状态抛 60001。
     */
    public static String statusOnAccept(String currentStatus) {
        if ("DISTRIBUTED".equals(currentStatus) || "IN_PROGRESS".equals(currentStatus)) {
            return "IN_PROGRESS";
        }
        throw new BizException(ErrorCode.SUBMISSION_STATUS_INVALID, "任务尚未下发，不能确认接收");
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
