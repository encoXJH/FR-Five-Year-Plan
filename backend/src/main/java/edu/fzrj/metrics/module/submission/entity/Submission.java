package edu.fzrj.metrics.module.submission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 填报
 */
@Data
@TableName("submission")
public class Submission {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** B 任务，引用 task.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /** B 年度，引用 task_year.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskYearId;

    /** 填报年度 */
    private Integer year;

    /** 冗余：计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 冗余：指标，引用 indicator.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long indicatorId;

    /** 冗余：执行单元，引用 org_unit.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long unitId;

    /** 统计口径快照（填报时指标的口径） */
    private String statCaliber;

    /** 填报值：INCREMENT 本次新增数量 */
    private BigDecimal amount;

    /** 填报值：STOCK / GROWTH 当前总量 */
    private BigDecimal total;

    /** 填报值：RATIO 分子 */
    private BigDecimal numerator;

    /** 填报值：RATIO 分母 */
    private BigDecimal denominator;

    /** 填报值：DIRECT_PERCENT 直填百分比（小数） */
    private BigDecimal percent;

    /** 填报值：BOOLEAN 是否完成 */
    private Boolean achieved;

    /** 认定值：审核认定的增量（核减后） */
    private BigDecimal approvedAmount;

    /** 认定值：审核认定的总量 */
    private BigDecimal approvedTotal;

    /** 认定值：审核认定的分子 */
    private BigDecimal approvedNumerator;

    /** 认定值：审核认定的分母 */
    private BigDecimal approvedDenominator;

    /** 认定值：审核认定的百分比 */
    private BigDecimal approvedPercent;

    /** 认定值：审核认定的是否完成 */
    private Boolean approvedAchieved;

    /** 完成情况说明 */
    private String remark;

    /** 数据来源说明（直填百分比型必填） */
    private String sourceNote;

    /** 填报状态：DRAFT 草稿 / PENDING_REVIEW 待审核 / APPROVED 已通过 / REJECTED 已驳回 / WITHDRAWN 已撤回 */
    private String status;

    /** 提交次数（每次提交 +1，对应 submission_history / review） */
    private Integer revisionNo;

    /** 最近提交时间 */
    private LocalDateTime submittedAt;

    /** 最近提交人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submittedBy;

    /** 最近审核时间 */
    private LocalDateTime reviewedAt;

    /** 最近审核人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewerId;

    /** 是否管理员代填 */
    private Boolean isProxy;

    /** 代填原因（敏感操作必填） */
    private String proxyReason;

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
