package edu.fzrj.metrics.module.submission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 填报历史快照
 */
@Data
@TableName("submission_history")
public class SubmissionHistory {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 填报，引用 submission.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submissionId;

    /** 提交序号 */
    private Integer revisionNo;

    /** 提交时的填报值快照（MetricValue） */
    private String valueJson;

    /** 提交时引用的文件 id 数组 */
    private String fileIdsJson;

    /** 提交时间 */
    private LocalDateTime submittedAt;

    /** 提交人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submittedBy;
}
