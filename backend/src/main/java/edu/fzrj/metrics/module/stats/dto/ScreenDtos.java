package edu.fzrj.metrics.module.stats.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 可视化大屏聚合 DTO（API 约定 15.8，FR-M9-01 ~ FR-M9-05）
 */
public final class ScreenDtos {

    private ScreenDtos() {
    }

    public record PlanRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            Integer startYear,
            Integer endYear) {
    }

    /** 年度趋势项（目标与实际对比按任务计数口径） */
    public record YearTrend(
            Integer year,
            long taskCount,
            long achievedCount,
            BigDecimal completionRate) {
    }

    /** 指标调整统计（延期 / 转移含「延期+转移」双重计入） */
    public record AdjustmentStats(
            long totalCount,
            long pendingCount,
            long approvedCount,
            long postponeCount,
            long transferCount) {
    }

    /** 最近审核动态 */
    public record ActivityItem(
            LocalDateTime time,
            String operatorName,
            String taskCode,
            Integer year,
            String result) {
    }

    /** 核心数字条：A 类 / A+ 类 / B 任务 / 佐证材料 / 本年审核通过（界面设计规范 9.1④） */
    public record CoreNumbers(
            long indicatorACount,
            long indicatorAPlusCount,
            long taskCount,
            long materialCount,
            long approvedThisYearCount) {
    }

    /** 大屏聚合数据 */
    public record ScreenView(
            PlanRef plan,
            Integer year,
            LocalDateTime generatedAt,
            StatsDtos.Overview overview,
            CoreNumbers coreNumbers,
            List<StatsDtos.UnitStat> byUnit,
            List<StatsDtos.IndicatorStat> byIndicator,
            List<YearTrend> trend,
            AdjustmentStats adjustmentStats,
            List<ActivityItem> recentActivities) {
    }
}
