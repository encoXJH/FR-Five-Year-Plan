package edu.fzrj.metrics.module.integration.dto;

import java.time.LocalDateTime;

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
}
