package edu.fzrj.metrics.module.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("training_sheet_staff")
public class TrainingSheetStaff {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sheetId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long staffId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long assignedBy;

    private LocalDateTime createdAt;
}
