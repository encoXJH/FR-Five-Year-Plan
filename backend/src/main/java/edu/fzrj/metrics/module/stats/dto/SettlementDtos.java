package edu.fzrj.metrics.module.stats.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 年度核算 / 五年核算 DTO（API 约定 15.8）
 */
public final class SettlementDtos {

    private SettlementDtos() {
    }

    /** 年度核算 / 五年核算请求体：乐观锁版本号（计划版本号） */
    @Data
    public static class SettleRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    /** 解锁请求体：敏感操作必须填写原因 */
    @Data
    public static class UnlockRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;

        @NotBlank(message = "解锁必须填写原因")
        @Size(max = 500, message = "原因不能超过 500 字")
        private String reason;
    }

    /** 年度核算结果 */
    public record SettleResult(
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            Integer year,
            LocalDateTime settledAt,
            long taskCount,
            long achievedCount,
            long notAchievedCount,
            long makeupCount) {
    }

    /** 年度核算结果查询（含逐任务明细；未核算时 items 为空） */
    public record SettlementView(
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            Integer year,
            String planYearStatus,
            LocalDateTime settledAt,
            UserRef settledBy,
            String lastUnlockReason,
            List<SettlementItem> items) {
    }

    public record SettlementItem(
            @JsonSerialize(using = ToStringSerializer.class) Long taskId,
            String taskCode,
            IndicatorRef indicator,
            StatsDtos.UnitRef unit,
            Object target,
            BigDecimal actualValue,
            BigDecimal completionRate,
            Boolean isAchieved,
            String yearStatus,
            @JsonSerialize(using = ToStringSerializer.class) Long makeupId,
            BigDecimal makeupGap,
            String makeupStatus) {
    }

    public record IndicatorRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }

    /** 解锁结果 */
    public record UnlockResult(
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            Integer year,
            String planYearStatus,
            String reason) {
    }

    /** 五年核算结果 */
    public record FinalSettlementView(
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            LocalDateTime finalSettledAt,
            UserRef finalSettledBy,
            long indicatorCount,
            long achievedCount,
            List<FinalItem> items) {
    }

    public record FinalItem(
            @JsonSerialize(using = ToStringSerializer.class) Long indicatorId,
            String code,
            String name,
            String nature,
            String statCaliber,
            String unitLabel,
            BigDecimal totalTarget,
            BigDecimal fiveYearActual,
            BigDecimal completionRate,
            Boolean isAchieved) {
    }
}
