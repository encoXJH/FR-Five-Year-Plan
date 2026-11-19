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
    public static final String STATS_VIEW = "stats:view";
    public static final String AUDIT_LOG_VIEW = "audit-log:view";
    public static final String AUDIT_LOG_EXPORT = "audit-log:export";
    public static final String SYSTEM_MANAGE = "system:manage";
    public static final String USER_CREATE = "user:create";
    public static final String MATERIAL_VIEW = "material:view";
    public static final String MATERIAL_UPLOAD = "material:upload";

    public static final Set<String> ALL = Set.of(
            PLAN_MANAGE, INDICATOR_EDIT, ALLOCATION_EDIT, TASK_DISTRIBUTE, TASK_ACCEPT,
            SUBMISSION_SUBMIT, SUBMISSION_REVIEW, ADJUSTMENT_APPLY, ADJUSTMENT_DECIDE,
            SETTLEMENT_MANAGE, SCREEN_VIEW, STATS_VIEW,
            AUDIT_LOG_VIEW, AUDIT_LOG_EXPORT, SYSTEM_MANAGE, USER_CREATE, MATERIAL_VIEW, MATERIAL_UPLOAD);

    private static final Map<String, Set<String>> ROLE_MAP = Map.of(
            "ADMIN", ALL,
            "LEADER", Set.of(SUBMISSION_REVIEW, ADJUSTMENT_DECIDE, SCREEN_VIEW, STATS_VIEW,
                    AUDIT_LOG_VIEW, USER_CREATE, MATERIAL_VIEW),
            "SCHOOL_OWNER", Set.of(INDICATOR_EDIT, ALLOCATION_EDIT, TASK_DISTRIBUTE,
                    ADJUSTMENT_APPLY, ADJUSTMENT_DECIDE, STATS_VIEW,
                    USER_CREATE, MATERIAL_VIEW, MATERIAL_UPLOAD),
            "UNIT_OWNER", Set.of(SUBMISSION_SUBMIT, SUBMISSION_REVIEW, ADJUSTMENT_DECIDE, STATS_VIEW,
                    USER_CREATE, MATERIAL_VIEW, MATERIAL_UPLOAD));

    public static final List<String> ROLE_ORDER = List.of("ADMIN", "LEADER", "SCHOOL_OWNER", "UNIT_OWNER");

    private static final Map<String, String> ROLE_LABELS = Map.of(
            "ADMIN", "平台管理员",
            "LEADER", "校级负责人",
            "SCHOOL_OWNER", "建设方向负责人",
            "UNIT_OWNER", "学院项目负责人",
            "STAFF", "干事");

    /**
     * 权限目录。lockedRoles 表示该角色必须保留的权限，configurableRoles 表示允许管理员开关的角色；
     * 不在两个集合中的组合一律禁止授予。ADMIN 的全部权限锁定，避免通过配置造成平台失管。
     */
    public record PermissionDefinition(
            String code, String name, String group, String description,
            Set<String> configurableRoles, Set<String> lockedRoles) {
    }

    private static final Set<String> ADMIN_ONLY = Set.of("ADMIN");
    private static final Set<String> ALL_ROLES = Set.of(ROLE_ORDER.toArray(String[]::new));

    private static final List<PermissionDefinition> DEFINITIONS = List.of(
            new PermissionDefinition(PLAN_MANAGE, "计划管理", "计划与指标",
                    "新建、修改、归档计划以及管理模板", Set.of(), ADMIN_ONLY),
            new PermissionDefinition(INDICATOR_EDIT, "指标编辑", "计划与指标",
                    "维护本人负责范围内的指标与年度目标", Set.of("SCHOOL_OWNER"), ADMIN_ONLY),
            new PermissionDefinition(ALLOCATION_EDIT, "分解编辑", "计划与指标",
                    "维护指标到学院任务的分解草稿", Set.of("SCHOOL_OWNER"), ADMIN_ONLY),
            new PermissionDefinition(TASK_DISTRIBUTE, "任务下发", "任务执行",
                    "预览并下发学院任务", Set.of("SCHOOL_OWNER"), ADMIN_ONLY),
            new PermissionDefinition(TASK_ACCEPT, "任务接收", "任务执行",
                    "历史任务接收确认权限；现行普通下发无需确认", Set.of(), ADMIN_ONLY),
            new PermissionDefinition(SUBMISSION_SUBMIT, "完成值填报", "任务执行",
                    "填写完成值、上传佐证材料并提交审核", Set.of("UNIT_OWNER"), ADMIN_ONLY),
            new PermissionDefinition(SUBMISSION_REVIEW, "审核中心", "任务执行",
                    "按当前审核节点处理本人可审核的学院填报", Set.of("LEADER"), Set.of("ADMIN", "UNIT_OWNER")),
            new PermissionDefinition(ADJUSTMENT_APPLY, "发起调整", "调整审批",
                    "发起符合业务规则的延期、转移等调整申请", Set.of("SCHOOL_OWNER"), ADMIN_ONLY),
            new PermissionDefinition(ADJUSTMENT_DECIDE, "调整审批", "调整审批",
                    "处理固定审批链中的初审、接收确认或终审", Set.of(), ALL_ROLES),
            new PermissionDefinition(SETTLEMENT_MANAGE, "核算管理", "计划与指标",
                    "年度激活、年度核算、解锁、补做与五年核算", Set.of(), ADMIN_ONLY),
            new PermissionDefinition(SCREEN_VIEW, "可视化大屏", "统计与审计",
                    "查看全校可视化大屏", Set.of("LEADER"), ADMIN_ONLY),
            new PermissionDefinition(STATS_VIEW, "统计报表", "统计与审计",
                    "查看本人数据范围内的统计报表", Set.of("LEADER", "SCHOOL_OWNER", "UNIT_OWNER"), ADMIN_ONLY),
            new PermissionDefinition(AUDIT_LOG_VIEW, "操作日志查看", "统计与审计",
                    "查看全校操作日志", Set.of("LEADER"), ADMIN_ONLY),
            new PermissionDefinition(AUDIT_LOG_EXPORT, "操作日志导出", "统计与审计",
                    "导出全校操作日志", Set.of(), ADMIN_ONLY),
            new PermissionDefinition(SYSTEM_MANAGE, "系统管理", "系统与材料",
                    "管理组织、用户、角色权限、字典、参数和集成", Set.of(), ADMIN_ONLY),
            new PermissionDefinition(USER_CREATE, "用户添加", "系统与材料",
                    "按创建人角色矩阵创建用户，其他用户管理仍需要系统管理权限",
                    Set.of("LEADER", "SCHOOL_OWNER", "UNIT_OWNER"), ADMIN_ONLY),
            new PermissionDefinition(MATERIAL_VIEW, "材料库查看", "系统与材料",
                    "检索、预览和下载本人数据范围内的材料",
                    Set.of("LEADER", "SCHOOL_OWNER", "UNIT_OWNER"), ADMIN_ONLY),
            new PermissionDefinition(MATERIAL_UPLOAD, "材料上传", "系统与材料",
                    "上传填报或业务附件；材料库直接绑定仍由角色和业务规则限制",
                    Set.of("SCHOOL_OWNER"), Set.of("ADMIN", "UNIT_OWNER")));

    public static List<PermissionDefinition> definitions() {
        return DEFINITIONS;
    }

    public static String roleLabel(String role) {
        return ROLE_LABELS.getOrDefault(role, role);
    }

    public static Set<String> defaultPermissions(String role) {
        return ROLE_MAP.getOrDefault(role, Set.of());
    }

    /** 默认角色权限并集，用于迁移种子和未初始化配置的兼容读取。 */
    public static List<String> permissionsOf(List<String> roles) {
        return roles.stream()
                .flatMap(role -> ROLE_MAP.getOrDefault(role, Set.of()).stream())
                .distinct()
                .sorted()
                .toList();
    }
}
