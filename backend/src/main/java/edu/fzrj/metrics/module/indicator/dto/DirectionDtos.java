package edu.fzrj.metrics.module.indicator.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主要S 级任务 DTO（API 约定 15.3）
 */
public final class DirectionDtos {

    private DirectionDtos() {
    }

    @Data
    public static class CreateRequest {
        @NotBlank(message = "主体必填")
        private String subjectType;

        /** 主体为专业群时必填 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long majorGroupId;

        @NotBlank(message = "S 级任务名称必填")
        @Size(max = 200, message = "名称不能超过 200 字")
        private String name;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long leaderId;

        private Integer sort;
    }

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            String subjectType,
            MajorGroupRef majorGroup,
            String name,
            @JsonSerialize(using = ToStringSerializer.class) Long leaderId,
            UserRef leader,
            Integer sort,
            Integer indicatorCount,
            Integer version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record MajorGroupRef(@JsonSerialize(using = ToStringSerializer.class) Long id, String name) {
    }
}
