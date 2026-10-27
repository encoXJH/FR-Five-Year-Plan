package edu.fzrj.metrics.module.notify.service.support;

/**
 * 待办聚合纯逻辑（API 约定 15.9 / 数据库设计 7.9）。
 */
public final class TodoRules {

    private TodoRules() {
    }

    /**
     * 该任务年度是否属于「待填报」：
     * 进行中 / 待补做直接可填；未激活（NOT_ACTIVATED）仅当所属 plan_year 已激活时可填（惰性激活口径，阶段 4 决策）。
     */
    public static boolean isFillableTaskYear(String yearStatus, boolean planYearActive) {
        return switch (yearStatus) {
            case "IN_PROGRESS", "MAKEUP_PENDING" -> true;
            case "NOT_ACTIVATED" -> planYearActive;
            default -> false;
        };
    }
}
