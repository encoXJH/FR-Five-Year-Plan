package edu.fzrj.metrics.module.integration.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 飞书集成 DTO（API 约定 15.11）
 */
public final class FeishuDtos {

    private FeishuDtos() {
    }

    /** 组织同步结果摘要（同时写入审计日志 after） */
    public record SyncOrgView(
            int departmentCount,
            int feishuUserCount,
            int matchedCount,
            /** 历史兼容字段：首次绑定须校内核验，组织同步不再绑定，恒为 0 */
            int openIdBoundCount,
            int nameUpdatedCount,
            int unitUpdatedCount,
            int unmatchedCount,
            LocalDateTime syncedAt) {
    }

    /** 飞书集成状态（P15 飞书集成 Tab；system:manage） */
    public record StatusView(
            boolean configured,
            String appIdMasked,
            String authorizeCallbackUrl,
            String eventCallbackUrl,
            LocalDateTime lastSyncAt,
            SyncOrgView lastSyncResult) {
    }

    public record BotRuleView(String code, String name, String description) {
    }

    public record BotStatusView(
            boolean configured,
            boolean pushEnabled,
            boolean botEnabled,
            String timezone,
            String progressCron,
            String leaderDigestCron,
            List<BotRuleView> rules,
            Map<String, Long> todayPush) {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class BotLogQuery extends PageQuery {
        private String status;
        private String type;
        private String keyword;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate createdDate;
    }

    public record BotLogItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long userId,
            String receiverName,
            String type,
            String title,
            String content,
            String link,
            String feishuStatus,
            Integer feishuRetryCount,
            String feishuLastError,
            LocalDateTime createdAt) {
    }

    public record BotRunRequest(String job) {
    }
}
