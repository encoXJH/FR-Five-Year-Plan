package edu.fzrj.metrics.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_dependency")
public class TaskDependency {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long planId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long predecessorTaskId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long successorTaskId;

    private String dependencyType;

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
