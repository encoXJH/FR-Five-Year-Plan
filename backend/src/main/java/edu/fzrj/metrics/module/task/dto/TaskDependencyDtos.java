package edu.fzrj.metrics.module.task.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

public final class TaskDependencyDtos {

    private TaskDependencyDtos() {
    }

    @Data
    public static class CreateRequest {
        @NotNull(message = "前置任务必填")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long predecessorTaskId;

        @NotNull(message = "后续任务必填")
        @JsonSerialize(using = ToStringSerializer.class)
        private Long successorTaskId;

        private String type;
    }

    public record View(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long predecessorTaskId,
            @JsonSerialize(using = ToStringSerializer.class) Long successorTaskId,
            String type,
            Integer version) {
    }
}
