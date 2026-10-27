package edu.fzrj.metrics.module.notify.service.support;

import java.time.LocalDate;
import java.util.Set;

/**
 * 截止提醒纯逻辑（PRD FR-M11-03）：年度填报 / 核算的截止日 = 次年的 plan.settleDeadline（MM-dd）。
 * 截止前 N 天内（含截止当天）需要提醒；逾期不补提醒（年度核算锁定后由未达标流程接管）。
 */
public final class ReminderRules {

    /** 需要提醒的年度状态：进行中 / 尚未惰性激活（年度已激活即可填报） */
    public static final Set<String> REMINDABLE_YEAR_STATUS = Set.of("IN_PROGRESS", "NOT_ACTIVATED");

    /** 需要提醒的任务状态：已下发待确认 / 执行中 */
    public static final Set<String> REMINDABLE_TASK_STATUS = Set.of("DISTRIBUTED", "IN_PROGRESS");

    private ReminderRules() {
    }

    /** 截止日：次年的 settleDeadline（MM-dd，如 01-15）；格式非法返回 null */
    public static LocalDate deadlineOf(int year, String settleDeadline) {
        if (settleDeadline == null || !settleDeadline.matches("\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            int month = Integer.parseInt(settleDeadline.substring(0, 2));
            int day = Integer.parseInt(settleDeadline.substring(3, 5));
            return LocalDate.of(year + 1, month, day);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 今天是否处于「截止前 N 天（含）到截止日（含）」的提醒窗口 */
    public static boolean inRemindWindow(LocalDate today, LocalDate deadline, int daysBefore) {
        if (today == null || deadline == null || daysBefore < 0) {
            return false;
        }
        return !today.isBefore(deadline.minusDays(daysBefore)) && !today.isAfter(deadline);
    }

    /** 距截止日剩余天数（今天 < 截止日时为正；当天为 0；过后为负） */
    public static long daysRemaining(LocalDate today, LocalDate deadline) {
        return java.time.temporal.ChronoUnit.DAYS.between(today, deadline);
    }
}
