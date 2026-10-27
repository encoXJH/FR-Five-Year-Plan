package edu.fzrj.metrics.module.notify.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 待办与站内消息 DTO（API 约定 15.9）
 */
public final class NotifyDtos {

    private NotifyDtos() {
    }

    /** 待办类型：FILL 待填报 / REVIEW 待审核 / ACCEPT 待确认接收 / APPROVAL 待审批 */
    public record TodoItem(
            String type,
            @JsonSerialize(using = ToStringSerializer.class) Long bizId,
            String bizCode,
            String detail,
            LocalDateTime todoSince,
            /** 填报截止日（YYYY-MM-DD，仅 FILL / REVIEW 有值，系统参数 plan.fillDeadline 拼年度） */
            String deadline) {
    }

    /** 待办响应：items 按 todoSince 升序；summary 为各类型计数 */
    public record TodoView(List<TodoItem> items, Map<String, Long> summary) {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class NotificationQuery extends PageQuery {
        /** 只查未读 */
        private Boolean unreadOnly;
        /** 按消息类型过滤（NotificationType 枚举） */
        private String type;
    }

    public record NotificationItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String type,
            String title,
            String content,
            String bizType,
            @JsonSerialize(using = ToStringSerializer.class) Long bizId,
            String link,
            LocalDateTime readAt,
            LocalDateTime createdAt) {
    }

    /** 站内消息分页：比标准分页多一个 unreadCount（未读角标） */
    public record NotificationPage(
            List<NotificationItem> items,
            Long total,
            Integer page,
            Integer pageSize,
            Long unreadCount) {
    }

    public record ReadAllResult(Long readCount) {
    }
}
