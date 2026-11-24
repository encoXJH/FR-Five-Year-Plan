package edu.fzrj.metrics.module.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("training_review")
public class TrainingReview {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sheetId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;
    private Integer revisionNo;
    private String reviewNode;
    private String result;
    private String comment;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewerId;
    private Boolean isProxy;
    private String proxyReason;
    private LocalDateTime createdAt;
}
