package edu.fzrj.metrics.module.training.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("training_sheet")
public class TrainingSheet {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long unitId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;
    private String status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String submitterRole;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String currentReviewNode;
    private Integer revisionNo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime submittedAt;
    @JsonSerialize(using = ToStringSerializer.class)
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long submittedBy;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;
    private LocalDateTime updatedAt;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long deleted;
}
