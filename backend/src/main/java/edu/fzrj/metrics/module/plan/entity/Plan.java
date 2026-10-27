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
 * 计划
 */
@Data
@TableName("plan")
public class Plan {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 计划名称，如 2026-2030 五年发展规划 */
    private String name;

    /** 起始年份 */
    private Integer startYear;

    /** 结束年份 */
    private Integer endYear;

    /** 基线年（规划起始前一年） */
    private Integer baselineYear;

    /** 年度核算截止的月-日，如 01-15 */
    private String settleDeadline;

    /** 计划状态：DRAFT 草稿 / ACTIVE 执行中 / ARCHIVED 已归档 */
    private String status;

    /** 新建方式：BLANK 空白 / TEMPLATE 从模板 / COPY 复制计划 */
    private String source;

    /** 来源模板，引用 template.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long templateId;

    /** 复制来源计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourcePlanId;

    /** 转移时是否需要接收方确认（FR-M6-07） */
    private Boolean isReceiverConfirmRequired;

    /** 指标体系定稿时间 */
    private LocalDateTime finalizedAt;

    /** 定稿操作人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long finalizedBy;

    /** 五年核算时间（规划期结束后统一核算） */
    private LocalDateTime finalSettledAt;

    /** 五年核算操作人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long finalSettledBy;

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
