package edu.fzrj.metrics.module.training.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("training_project")
public class TrainingProject {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sheetId;
    private Integer rowNo;
    private String dataJson;
    private String status;
    private String submitterRole;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String currentReviewNode;
    private Integer revisionNo;
    private LocalDateTime submittedAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submittedBy;
    private LocalDateTime createdAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;
    private LocalDateTime updatedAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long deleted;
}
