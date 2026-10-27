package edu.fzrj.metrics.module.adjustment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调整审批环节
 */
@Data
@TableName("approval_step")
public class ApprovalStep {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 调整申请，引用 adjustment.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long adjustmentId;

    /** 环节顺序号（从 1 开始） */
    private Integer seq;

    /** 审批环节：OWNER_REVIEW 校级负责人初审 / RECEIVER_CONFIRM 接收方确认 / LEADER_APPROVAL 校领导审批 */
    private String node;

    /** 期望处理人，引用 sys_user.id；RECEIVER_CONFIRM 环节可为空（按接收单元负责人判断） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long expectedApproverId;

    /** 期望处理单元（RECEIVER_CONFIRM 环节），引用 org_unit.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long expectedUnitId;

    /** 审批决定：APPROVE 同意 / REJECT 驳回；未处理为空 */
    private String decision;

    /** 审批意见 */
    private String comment;

    /** 处理时间 */
    private LocalDateTime decidedAt;

    /** 处理人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long decidedBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
