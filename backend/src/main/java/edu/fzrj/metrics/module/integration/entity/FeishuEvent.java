package edu.fzrj.metrics.module.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 飞书事件去重
 */
@Data
@TableName("feishu_event")
public class FeishuEvent {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 飞书事件 id（用于去重） */
    private String eventId;

    /** 事件类型 */
    private String eventType;

    /** 事件报文 */
    private String payload;

    /** 接收时间 */
    private LocalDateTime receivedAt;

    /** 处理完成时间 */
    private LocalDateTime processedAt;

    /** 处理状态：RECEIVED 已接收 / PROCESSED 已处理 / FAILED 处理失败 */
    private String status;
}
