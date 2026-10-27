package edu.fzrj.metrics.module.stats.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.List;

/**
 * 统计接口 DTO（API 约定 15.8）
 */
public final class StatsDtos {

    private StatsDtos() {
    }

    public record UnitRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name) {
    }

    /** 滞后预警项（时间进度 − 完成进度 > 阈值） */
    public record WarningItem(
            @JsonSerialize(using = ToStringSerializer.class) Long taskId,
            String taskCode,
            String indicatorName,
            UnitRef unit,
            Integer year,
            BigDecimal completionRate,
            BigDecimal timeProgress,
            BigDecimal lagGap) {
    }

    /** 总体概览 */
    public record Overview(
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            Integer year,
            BigDecimal timeProgress,
            BigDecimal overallCompletionRate,
            /** 五年累计完成率：各 A 指标累计完成率（累计完成 ÷ 五年总量目标）的算术平均；无数据为 null */
            BigDecimal cumulativeCompletionRate,
            long constraintTotal,
            long constraintAchieved,
            long developmentTotal,
            long developmentAchieved,
            long taskCount,
            long achievedCount,
            long notAchievedCount,
            long inProgressCount,
            long makeupPendingCount,
            long madeUpCount,
            long warningCount,
            List<WarningItem> warnings) {
    }

    /** 按执行单元统计（完成率排名） */
    public record UnitStat(
            UnitRef unit,
            long taskCount,
            long achievedCount,
            BigDecimal completionRate,
            /** 五年累计完成率：该单元可见 B 任务累计完成率的算术平均；无数据为 null */
            BigDecimal cumulativeRate,
            int rank) {
    }

    /** 按 A 级指标统计（非量化 A 不参与） */
    public record IndicatorStat(
            @JsonSerialize(using = ToStringSerializer.class) Long indicatorId,
            String code,
            String name,
            long aPlusCount,
            long taskCount,
            long achievedCount,
            BigDecimal completionRate,
            /** 五年累计完成率：该 A 下可见 B 任务累计完成率的算术平均，保留历史任务；无数据为 null */
            BigDecimal cumulativeRate) {
    }

    /**
     * A → A+ → B 下钻树节点。B 叶子节点带 taskId / unit / yearStatus / isAchieved / isHistorical；
     * A / A+ 节点带 children。target 对 BOOLEAN 口径为 Boolean（计划完成年 true）。
     */
    public record TreeNode(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            String level,
            String nature,
            String statCaliber,
            String unitLabel,
            String targetDirection,
            Object target,
            BigDecimal actualValue,
            BigDecimal completionRate,
            String achieveStatus,
            /** 五年累计完成值（INCREMENT 为各年之和，STOCK/GROWTH 为最新总量，RATIO/DIRECT_PERCENT 为最新比率，BOOLEAN 为 0/1） */
            BigDecimal cumulativeActual,
            /** 五年累计完成率 = 累计完成 ÷ 五年总量目标 */
            BigDecimal cumulativeRate,
            @JsonSerialize(using = ToStringSerializer.class) Long taskId,
            UnitRef unit,
            String yearStatus,
            Boolean isAchieved,
            Boolean isHistorical,
            List<TreeNode> children) {
    }

    /** 指标趋势（各年度目标与实际） */
    public record TrendView(
            @JsonSerialize(using = ToStringSerializer.class) Long indicatorId,
            String code,
            String name,
            String statCaliber,
            String unitLabel,
            BigDecimal baselineValue,
            BigDecimal totalTarget,
            /** 五年累计完成值（INCREMENT 各年之和；STOCK/GROWTH 最新总量；RATIO/DIRECT_PERCENT 最新比率；BOOLEAN 0/1） */
            BigDecimal cumulativeActual,
            /** 五年累计完成率 = 累计完成 ÷ 五年总量目标 */
            BigDecimal cumulativeRate,
            List<TrendYear> years) {
    }

    public record TrendYear(
            Integer year,
            Object target,
            BigDecimal actualValue,
            BigDecimal actualTotal,
            BigDecimal completionRate,
            String achieveStatus) {
    }
}
