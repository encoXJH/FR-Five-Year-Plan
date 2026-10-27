package edu.fzrj.metrics.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌
 */
@Data
@TableName("refresh_token")
public class RefreshToken {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 用户，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** refreshToken 的 SHA-256 */
    private String tokenHash;

    /** 过期时间（签发后 7 天） */
    private LocalDateTime expiresAt;

    /** 吊销时间（退出登录 / 被替换时） */
    private LocalDateTime revokedAt;

    /** 替换它的新令牌 id（旋转刷新），引用 refresh_token.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long replacedById;

    /** 客户端类型 */
    private String clientType;

    /** 签发时 IP */
    private String ip;

    /** 签发时间 */
    private LocalDateTime createdAt;
}
