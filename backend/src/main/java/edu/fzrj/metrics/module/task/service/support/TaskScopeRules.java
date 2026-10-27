package edu.fzrj.metrics.module.task.service.support;

/**
 * B 任务数据范围纯逻辑判定（PRD 6.2 / API 约定 4.3）：
 * ADMIN / LEADER 全部；UNIT_OWNER 本单元；SCHOOL_OWNER 本人负责的指标（A+ 未设负责人时沿用 A）。
 * 多角色取并集：单元匹配或负责人匹配任一成立即可访问。
 */
public final class TaskScopeRules {

    private TaskScopeRules() {
    }

    /**
     * 是否可访问某条 B 任务。
     *
     * @param isAllData        是否全校数据范围（ADMIN / LEADER）
     * @param isUnitScope      是否有学院负责人角色
     * @param isOwnerScope     是否有校级负责人角色
     * @param userId           当前用户 id
     * @param userUnitId       当前用户所属单元 id
     * @param taskUnitId       任务承接单元 id
     * @param indicatorOwnerId 任务所属指标（A+ / 不可下分 A）负责人 id
     * @param topOwnerId       任务所属 A 负责人 id
     */
    public static boolean canAccess(boolean isAllData, boolean isUnitScope, boolean isOwnerScope,
                                    Long userId, Long userUnitId, Long taskUnitId,
                                    Long indicatorOwnerId, Long topOwnerId) {
        if (isAllData) {
            return true;
        }
        if (isUnitScope && userUnitId != null && userUnitId.equals(taskUnitId)) {
            return true;
        }
        return isOwnerScope && userId != null
                && (userId.equals(indicatorOwnerId) || userId.equals(topOwnerId));
    }

    /**
     * 列表查询是否需要按负责人过滤（调用方据此决定是否加载负责人指标集合）。
     * 返回 true 表示无任何可见数据（既非全校，也没有单元或负责人范围）。
     */
    public static boolean noVisibleScope(boolean isAllData, boolean isUnitScope, boolean isOwnerScope,
                                         Long userUnitId) {
        if (isAllData) {
            return false;
        }
        boolean unitVisible = isUnitScope && userUnitId != null;
        return !unitVisible && !isOwnerScope;
    }
}
