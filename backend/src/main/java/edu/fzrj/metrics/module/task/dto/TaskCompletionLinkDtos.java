package edu.fzrj.metrics.module.task.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

public final class TaskCompletionLinkDtos {

    private TaskCompletionLinkDtos() {
    }

    @Data
    public static class CreateRequest {
        @NotNull(message = "关联任务 A 必填")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long taskAId;

        @NotNull(message = "关联任务 B 必填")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long taskBId;

        @Size(max = 500, message = "关联来源说明不能超过 500 字")
        private String sourceNote;
    }

    public record View(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long taskAId,
            @JsonSerialize(using = ToStringSerializer.class) Long taskBId,
            String sourceNote,
            Integer version) {
    }
}
