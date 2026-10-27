package edu.fzrj.metrics.module.task.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.page.PageQuery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * B 任务 / 分解矩阵 DTO（API 约定 15.4、16.3）
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    // ---------------- 分解矩阵 ----------------

    /** 矩阵行年度目标项：BOOLEAN 口径 target 为 true / null，其余口径为数字 */
    @Data
    public static class AllocationYearTargetItem {
        @NotNull(message = "年度必填")
        private Integer year;
        private Object target;
    }

    /** 矩阵行：一个执行单元 + 学院负责人 + 各年度目标 */
    @Data
    public static class AllocationRow {
        @NotNull(message = "执行单元必填")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;

        /** 学院项目负责人（草稿可空，下发前必填 50002） */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long ownerId;

        @NotNull(message = "年度目标必填")
        @Valid
        private List<AllocationYearTargetItem> yearTargets;
    }

    /** 保存分解矩阵（整体替换） */
    @Data
    public static class AllocationPutRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;

        @Valid
        private List<AllocationRow> rows;
    }

    public record UnitRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }

    public record IndicatorRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            String statCaliber,
            String unitLabel,
            String evidenceRequirement) {
    }

    /** 任务所属计划引用（任务详情用，供年度范围与展示） */
    public record PlanRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            Integer startYear,
            Integer endYear) {
    }

    /** 合计校验（按年度）：diff = allocatedTarget − indicatorTarget（空值按 0 参与计算） */
    public record SummaryItem(Integer year, BigDecimal indicatorTarget,
                              BigDecimal allocatedTarget, BigDecimal diff) {
    }

    public record YearTargetView(Integer year, Object target) {
    }

    /** 矩阵行视图 */
    public record AllocationRowView(
            @JsonSerialize(using = ToStringSerializer.class) Long taskId,
            String taskCode,
            String taskStatus,
            Boolean isHistorical,
            UnitRef unit,
            UserRef owner,
            List<YearTargetView> yearTargets) {
    }

    /** 分解矩阵响应（API 约定 16.3；rows 为当前矩阵内容） */
    public record AllocationView(
            @JsonSerialize(using = ToStringSerializer.class) Long indicatorId,
            Integer version,
            Boolean isBalanced,
            List<SummaryItem> summary,
            List<AllocationRowView> rows) {
    }

    // ---------------- 下发 ----------------

    /** 下发请求：indicatorIds 为空时下发全部可下发指标 */
    @Data
    public static class DistributeRequest {
        private List<Long> indicatorIds;

        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
    }

    public record DistributeResponse(Integer indicatorCount, Integer taskCount) {
    }

    // ---------------- 任务查询 ----------------

    /** B 任务分页查询参数 */
    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class TaskQuery extends PageQuery {
        @JsonSerialize(using = ToStringSerializer.class)
        private Long unitId;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long indicatorId;
        private Integer year;
        private String status;
        private String yearStatus;
        private Boolean isHistorical;
    }

    /** B 任务列表项；年度字段（yearTarget/actualValue/completionRate/yearStatus）取查询 year，未指定时取当前激活年度 */
    public record TaskListItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            String code,
            IndicatorRef indicator,
            IndicatorRef topIndicator,
            UnitRef unit,
            UserRef owner,
            String status,
            Boolean isHistorical,
            Object totalTarget,
            Object yearTarget,
            BigDecimal actualValue,
            BigDecimal completionRate,
            /** 五年累计完成值（INCREMENT 各年之和，STOCK/GROWTH 最新总量，RATIO/DIRECT_PERCENT 最新比率，BOOLEAN 0/1） */
            BigDecimal cumulativeActual,
            /** 五年累计完成率 = 累计完成 ÷ 五年总量目标 */
            BigDecimal cumulativeRate,
            String yearStatus,
            LocalDateTime distributedAt,
            LocalDateTime acceptedAt,
            /** 展示年度填报截止日（YYYY-MM-DD，系统参数 plan.fillDeadline 拼年度；无展示年度时为 null） */
            String deadline,
            Integer version) {
    }

    /** B 年度视图（详情）；actualNumerator / actualDenominator 仅 RATIO 口径有值 */
    public record TaskYearView(
            Integer year,
            Object target,
            BigDecimal originalTarget,
            BigDecimal actualValue,
            BigDecimal actualNumerator,
            BigDecimal actualDenominator,
            BigDecimal completionRate,
            Boolean isAchieved,
            String yearStatus) {
    }

    /** B 任务详情 */
    public record TaskDetail(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            String code,
            IndicatorRef indicator,
            IndicatorRef topIndicator,
            UnitRef unit,
            UserRef owner,
            String status,
            Boolean isHistorical,
            PlanRef plan,
            LocalDateTime distributedAt,
            UserRef distributedBy,
            LocalDateTime acceptedAt,
            UserRef acceptedBy,
            List<TaskYearView> years,
            /** 五年累计完成值（口径同 TaskListItem） */
            BigDecimal cumulativeActual,
            /** 五年累计完成率 = 累计完成 ÷ 五年总量目标 */
            BigDecimal cumulativeRate,
            /** 当前激活年度填报截止日（YYYY-MM-DD；无激活年度时为 null） */
            String deadline,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
