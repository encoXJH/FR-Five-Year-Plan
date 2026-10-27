package edu.fzrj.metrics.module.audit.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 审计日志 DTO（API 约定 15.9、16.6）
 */
public final class AuditLogDtos {

    private AuditLogDtos() {
    }

    /**
     * 查询条件：occurredAtFrom/occurredAtTo（ISO 8601）、unitId、action（英文逗号多值）、
     * operatorId、resourceType、resourceId（指标等资源的 id）。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class AuditQuery extends PageQuery {
        private String occurredAtFrom;
        private String occurredAtTo;
        private Long unitId;
        private String action;
        private Long operatorId;
        private String resourceType;
        private Long resourceId;
    }

    public record LogItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            LocalDateTime occurredAt,
            UserRef operator,
            String roles,
            UnitRef unit,
            String action,
            String resourceType,
            @JsonSerialize(using = ToStringSerializer.class) Long resourceId,
            String resourceCode,
            String before,
            String after,
            String reason,
            String clientType,
            String ip,
            String requestId) {
    }

    /** 单元引用（audit_log 只落 unit_id，名称查询时解析） */
    public record UnitRef(@JsonSerialize(using = ToStringSerializer.class) Long id, String name) {
    }
}
