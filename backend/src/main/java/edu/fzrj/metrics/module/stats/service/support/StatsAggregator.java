package edu.fzrj.metrics.module.stats.service.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 统计口径纯逻辑（PRD 4.4.6 / 5.9；所有数值用 BigDecimal）：
 * 完成率计数口径、时间进度、滞后预警判定、年度状态归类。
 */
public final class StatsAggregator {

    /** 完成率保留 6 位小数（与表 DECIMAL(20,6) 对齐） */
    private static final int SCALE = 6;

    private StatsAggregator() {
    }

    /** 是否计入统计基数：有正的年度目标（与数据库设计查询 h 的 target IS NOT NULL AND target > 0 一致） */
    public static boolean countable(BigDecimal target) {
        return target != null && target.compareTo(BigDecimal.ZERO) > 0;
    }

    /** 完成率 = 达标数 ÷ 基数；基数为 0 时返回 null */
    public static BigDecimal rate(long part, long total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(total), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 时间进度（小数 0~1）：今天在该年之前为 0，之后为 1，年内按已过年天数 ÷ 全年天数。
     */
    public static BigDecimal timeProgress(int year, LocalDate today) {
        LocalDate start = LocalDate.of(year, 1, 1);
        if (today.isBefore(start)) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        LocalDate end = LocalDate.of(year, 12, 31);
        if (today.isAfter(end)) {
            return BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);
        }
        long elapsed = today.toEpochDay() - start.toEpochDay() + 1;
        long days = start.lengthOfYear();
        return BigDecimal.valueOf(elapsed).divide(BigDecimal.valueOf(days), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 滞后预警（PRD 4.4.6：时间进度 − 完成进度 > 阈值，阈值可配置）。
     * 没有实际完成率时不预警，避免把未填报误报为进度滞后。
     */
    public static boolean isLagging(BigDecimal completionRate, BigDecimal timeProgress, BigDecimal threshold) {
        if (completionRate == null || timeProgress == null || threshold == null) {
            return false;
        }
        return timeProgress.subtract(completionRate).compareTo(threshold) > 0;
    }

    /** 年度状态是否算「已达标」（已补做视为达标口径） */
    public static boolean isAchievedStatus(String yearStatus) {
        return "ACHIEVED".equals(yearStatus) || "MADE_UP".equals(yearStatus);
    }

    /** 年度状态是否算「未达标」（待补做属于未达标缺口） */
    public static boolean isNotAchievedStatus(String yearStatus) {
        return "NOT_ACHIEVED".equals(yearStatus) || "MAKEUP_PENDING".equals(yearStatus);
    }

    /** 年度状态是否算「进行中」（含待审核与调整审批中） */
    public static boolean isInProgressStatus(String yearStatus) {
        return "IN_PROGRESS".equals(yearStatus) || "PENDING_REVIEW".equals(yearStatus)
                || "ADJUSTING".equals(yearStatus);
    }
}
