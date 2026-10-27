package edu.fzrj.metrics.module.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内消息
 */
@Data
@TableName("notification")
public class Notification {

    /** 主键，雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 接收人，引用 sys_user.id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 通知类型：TASK_DISTRIBUTED 任务下发 / DEADLINE_REMINDER 截止提醒 / REVIEW_RESULT 审核结果 / ADJUSTMENT_TODO 调整待办 / ADJUSTMENT_RESULT 调整结果 / WARNING 预警 */
    private String type;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 关联业务类型 */
    private String bizType;

    /** 关联业务 id */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long bizId;

    /** 跳转链接（飞书 AppLink / 站内路由） */
    private String link;

    /** 阅读时间；未读为空 */
    private LocalDateTime readAt;

    /** 飞书推送状态：PENDING 待推送 / SENT 已推送 / FAILED 推送失败 / SKIPPED 无需推送 */
    private String feishuStatus;

    /** 飞书推送已重试次数（指数退避最多 3 次） */
    private Integer feishuRetryCount;

    /** 飞书推送下次重试时间（指数退避；空表示立即可推送） */
    private LocalDateTime feishuNextRetryAt;

    /** 飞书推送最近一次失败原因 */
    private String feishuLastError;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
