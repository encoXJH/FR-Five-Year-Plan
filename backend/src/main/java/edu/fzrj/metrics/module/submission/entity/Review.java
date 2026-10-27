package edu.fzrj.metrics.module.submission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核记录
 */
@Data
@TableName("review")
public class Review {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 填报，引用 submission.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submissionId;

    /** 对应的提交序号 */
    private Integer revisionNo;

    /** 审核人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewerId;

    /** 审核结果：APPROVED 通过 / REJECTED 驳回 */
    private String result;

    /** 审核意见（驳回必填，60004） */
    private String comment;

    /** 认定值快照（核减时记录原值与认定值） */
    private String approvedValueJson;

    /** 是否管理员代审 */
    private Boolean isProxy;

    /** 代审原因（敏感操作必填） */
    private String proxyReason;

    /** 审核时间 */
    private LocalDateTime createdAt;
}
