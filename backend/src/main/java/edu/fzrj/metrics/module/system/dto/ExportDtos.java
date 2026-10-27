package edu.fzrj.metrics.module.system.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步导出任务 DTO（API 约定 15.8）
 */
public final class ExportDtos {

    private ExportDtos() {
    }

    @Data
    public static class CreateRequest {
        @NotBlank(message = "导出类型必填")
        private String type;

        /** 导出参数：INDICATORS 需要 planId */
        private Map<String, Object> params = new LinkedHashMap<>();
    }

    /** 创建导出任务响应（API 约定 15.8：返回 exportId） */
    public record CreatedView(
            @JsonSerialize(using = ToStringSerializer.class) Long exportId) {
    }

    public record JobView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String type,
            String status,
            String errorMessage,
            String downloadUrl,
            LocalDateTime expiresAt,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
    }
}
