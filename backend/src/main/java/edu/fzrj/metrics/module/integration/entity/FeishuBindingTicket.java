package edu.fzrj.metrics.module.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/** 飞书首次绑定一次性票据（数据库只保存票据摘要） */
@Data
@TableName("feishu_binding_ticket")
public class FeishuBindingTicket {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String ticketHash;

    private String feishuOpenId;

    private String feishuUnionId;

    private String feishuName;

    private LocalDateTime expiresAt;

    private LocalDateTime consumedAt;

    private LocalDateTime createdAt;
}
