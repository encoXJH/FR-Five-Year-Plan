package edu.fzrj.metrics.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限码映射（权限并集见 API 约定 4.3）
 */
public final class RolePermissions {

    private RolePermissions() {
    }

    public static final String PLAN_MANAGE = "plan:manage";
    public static final String INDICATOR_EDIT = "indicator:edit";
    public static final String ALLOCATION_EDIT = "allocation:edit";
    public static final String TASK_DISTRIBUTE = "task:distribute";
    public static final String TASK_ACCEPT = "task:accept";
    public static final String SUBMISSION_SUBMIT = "submission:submit";
    public static final String SUBMISSION_REVIEW = "submission:review";
    public static final String ADJUSTMENT_APPLY = "adjustment:apply";
    public static final String ADJUSTMENT_DECIDE = "adjustment:decide";
    public static final String SETTLEMENT_MANAGE = "settlement:manage";
    public static final String SCREEN_VIEW = "screen:view";
    public static final String AUDIT_LOG_VIEW = "audit-log:view";
    public static final String AUDIT_LOG_EXPORT = "audit-log:export";
    public static final String SYSTEM_MANAGE = "system:manage";

    public static final Set<String> ALL = Set.of(
            PLAN_MANAGE, INDICATOR_EDIT, ALLOCATION_EDIT, TASK_DISTRIBUTE, TASK_ACCEPT,
            SUBMISSION_SUBMIT, SUBMISSION_REVIEW, ADJUSTMENT_APPLY, ADJUSTMENT_DECIDE,
            SETTLEMENT_MANAGE, SCREEN_VIEW, AUDIT_LOG_VIEW, AUDIT_LOG_EXPORT, SYSTEM_MANAGE);

    private static final Map<String, Set<String>> ROLE_MAP = Map.of(
            "ADMIN", ALL,
            "LEADER", Set.of(ADJUSTMENT_DECIDE, SCREEN_VIEW, AUDIT_LOG_VIEW),
            "SCHOOL_OWNER", Set.of(INDICATOR_EDIT, ALLOCATION_EDIT, TASK_DISTRIBUTE,
                    SUBMISSION_REVIEW, ADJUSTMENT_APPLY, ADJUSTMENT_DECIDE),
            "UNIT_OWNER", Set.of(TASK_ACCEPT, SUBMISSION_SUBMIT, ADJUSTMENT_APPLY, ADJUSTMENT_DECIDE));

    /** 多个角色取权限并集 */
    public static List<String> permissionsOf(List<String> roles) {
        return roles.stream()
                .flatMap(role -> ROLE_MAP.getOrDefault(role, Set.of()).stream())
                .distinct()
                .sorted()
                .toList();
    }
}
