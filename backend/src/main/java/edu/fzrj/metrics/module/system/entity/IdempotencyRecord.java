package edu.fzrj.metrics.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 幂等记录
 */
@Data
@TableName("idempotency_record")
public class IdempotencyRecord {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 提交人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 幂等键（UUID） */
    private String idemKey;

    /** 请求方法与路径，如 POST /api/v1/plans/1/distribute */
    private String methodPath;

    /** 请求体 SHA-256（同 Key 不同请求体返回 409+10004） */
    private String requestHash;

    /** 首次响应的 HTTP 状态码 */
    private Integer responseStatus;

    /** 首次响应体（重复请求直接返回） */
    private String responseBody;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 过期时间（创建后 24 小时） */
    private LocalDateTime expiresAt;
}
