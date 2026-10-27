package edu.fzrj.metrics.module.plan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计划年度
 */
@Data
@TableName("plan_year")
public class PlanYear {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 年度（自然年） */
    private Integer year;

    /** 年度状态：NOT_ACTIVATED 未激活 / ACTIVE 已激活 / LOCKED 已核算锁定 */
    private String status;

    /** 激活时间 */
    private LocalDateTime activatedAt;

    /** 激活操作人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activatedBy;

    /** 年度核算锁定时间 */
    private LocalDateTime settledAt;

    /** 核算操作人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long settledBy;

    /** 最近一次解锁原因（敏感操作必填） */
    private String lastUnlockReason;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 更新人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;

    /** 逻辑删除：0 未删除，否则为本行 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long deleted;
}
