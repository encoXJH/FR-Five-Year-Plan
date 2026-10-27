package edu.fzrj.metrics.module.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务文件关联
 */
@Data
@TableName("file_ref")
public class FileRef {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务类型：SUBMISSION / ADJUSTMENT / MILESTONE */
    private String bizType;

    /** 业务数据 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long bizId;

    /** 文件，引用 file_info.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;

    /** 排序号 */
    private Integer sort;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
