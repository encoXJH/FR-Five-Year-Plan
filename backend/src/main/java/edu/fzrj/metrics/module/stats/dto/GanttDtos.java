package edu.fzrj.metrics.module.stats.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.module.task.dto.TaskDependencyDtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class GanttDtos {

    private GanttDtos() {
    }

    public record PlanView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String name,
            Integer startYear,
            Integer endYear,
            String status) {
    }

    public record YearCell(
            Integer year,
            Object target,
            BigDecimal actualValue,
            BigDecimal actualTotal,
            BigDecimal actualNumerator,
            BigDecimal actualDenominator,
            BigDecimal completionRate,
            String status,
            Integer targetVersion,
            BigDecimal frozenAmount) {
    }

    public record Node(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long parentId,
            String kind,
            String code,
            String name,
            String caliber,
            String unitLabel,
            String unitName,
            String ownerName,
            Integer startYear,
            Integer endYear,
            BigDecimal cumulativeRate,
            boolean isHistorical,
            String status,
            List<YearCell> years) {
    }

    public record MilestoneView(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long indicatorId,
            @JsonSerialize(using = ToStringSerializer.class) Long taskId,
            String name,
            Integer relatedYear,
            LocalDate dueDate,
            String status,
            LocalDateTime completedAt,
            Integer version) {
    }

    public record CriticalPath(
            String status,
            @JsonSerialize(contentUsing = ToStringSerializer.class) List<Long> taskIds,
            @JsonSerialize(contentUsing = ToStringSerializer.class) List<Long> dependencyIds) {
    }

    public record GraphInfo(
            boolean truncated,
            int omittedDependencies,
            String message) {
    }

    public record View(
            PlanView plan,
            List<Integer> years,
            List<Node> nodes,
            List<MilestoneView> milestones,
            List<TaskDependencyDtos.View> dependencies,
            CriticalPath criticalPath,
            GraphInfo graph) {
    }
}
