package edu.fzrj.metrics.module.adjustment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标调整申请
 */
@Data
@TableName("adjustment")
public class Adjustment {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 计划，引用 plan.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    /** 调整类型：POSTPONE 延期 / TRANSFER 转移 / POSTPONE_TRANSFER 延期+转移 / TARGET_CHANGE 目标调整 */
    private String type;

    /** 转出的 B 任务，引用 task.id；TARGET_CHANGE 时可为空 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /** 涉及的 A+（或目标调整的 A），引用 indicator.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long indicatorId;

    /** 转出执行单元，引用 org_unit.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fromUnitId;

    /** 接收执行单元，引用 org_unit.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long toUnitId;

    /** 调整状态：PENDING_OWNER_REVIEW 待初审 / PENDING_RECEIVER_CONFIRM 待接收方确认 / PENDING_LEADER_APPROVAL 待校领导审批 / APPROVED 已生效 / REJECTED 已驳回 / WITHDRAWN 已撤回 */
    private String status;

    /** 当前审批环节：OWNER_REVIEW / RECEIVER_CONFIRM / LEADER_APPROVAL；结束后为空 */
    private String currentNode;

    /** 申请人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long applicantId;

    /** 调整原因 */
    private String reason;

    /** 调整前涉及的全部 B 年度目标快照（原指标） */
    private String snapshotBefore;

    /** 调整后涉及的全部 B 年度目标快照（变更后指标） */
    private String snapshotAfter;

    /** 生效时间 */
    private LocalDateTime effectiveAt;

    /** 撤回时间 */
    private LocalDateTime withdrawnAt;

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
