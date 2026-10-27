package edu.fzrj.metrics.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步导出任务
 */
@Data
@TableName("export_job")
public class ExportJob {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 导出类型：INDICATORS 指标体系 / TASKS B任务 / STATS_REPORT 统计报表 / SETTLEMENT 核算结果 / AUDIT_LOGS 操作日志 */
    private String type;

    /** 导出参数（过滤条件） */
    private String params;

    /** 任务状态：PENDING 排队中 / RUNNING 执行中 / SUCCEEDED 成功 / FAILED 失败 */
    private String status;

    /** 结果文件，引用 file_info.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;

    /** 失败原因 */
    private String errorMessage;

    /** 创建人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    /** 开始执行时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime finishedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
