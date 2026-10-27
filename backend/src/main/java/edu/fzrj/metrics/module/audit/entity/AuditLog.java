package edu.fzrj.metrics.module.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（只追加）
 */
@Data
@TableName("audit_log")
public class AuditLog {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 操作时间 */
    private LocalDateTime occurredAt;

    /** 操作人，引用 sys_user.id；系统操作为空 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;

    /** 操作人姓名快照 */
    private String operatorName;

    /** 操作人当时的角色数组 */
    private String roles;

    /** 操作人所属单元，引用 org_unit.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long unitId;

    /** 相关计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 动作：LOGIN / CREATE / UPDATE / DELETE / IMPORT / EXPORT / FINALIZE / DISTRIBUTE / ACCEPT / SUBMIT / WITHDRAW / REVIEW / ADJUST_APPLY / ADJUST_DECIDE / SETTLE / UNLOCK / PERMISSION_CHANGE / FILE_DOWNLOAD */
    private String action;

    /** 资源类型，如 indicator / task / submission / adjustment */
    private String resourceType;

    /** 资源 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long resourceId;

    /** 资源编号，如 A01-2-C03 */
    private String resourceCode;

    /** 变更前数据 */
    private String beforeJson;

    /** 变更后数据 */
    private String afterJson;

    /** 相关干系人 user id 数组 */
    private String stakeholderIds;

    /** 操作原因（敏感操作必填） */
    private String reason;

    /** 客户端：WEB / FEISHU_PC / FEISHU_MOBILE / SCREEN */
    private String clientType;

    /** 客户端 IP */
    private String ip;

    /** 客户端 UA */
    private String userAgent;

    /** 请求追踪 ID（X-Request-Id） */
    private String requestId;
}
