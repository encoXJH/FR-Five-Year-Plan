package edu.fzrj.metrics.module.training.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.common.page.PageQuery;
import edu.fzrj.metrics.module.file.dto.FileInfoResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class TrainingDtos {

    private TrainingDtos() {
    }

    @Data
    public static class CreatePlanRequest {
        @NotBlank(message = "计划名称必填")
        @Size(max = 200, message = "名称不能超过 200 字")
        private String name;
        @NotNull(message = "起始年份必填")
        private Integer startYear;
        @NotNull(message = "结束年份必填")
        private Integer endYear;
        private Integer baselineYear;
        @NotNull(message = "填报学院必填")
        private Long unitId;
        @NotNull(message = "填报负责人必填")
        private Long ownerId;
    }

    @Data
    public static class ProjectInput {
        private Long id;
        @NotNull(message = "项目行号必填")
        @Min(value = 1, message = "项目行号必须大于 0")
        private Integer rowNo;
        @NotNull(message = "项目数据不能为空")
        private Map<String, Object> data;
        private List<Long> fileIds;
    }

    @Data
    public static class StaffPutRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
        @NotNull(message = "staffIds 不能为空")
        private List<Long> staffIds;
    }

    @Data
    public static class SaveProjectsRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
        @NotNull(message = "projects 不能为空")
        @Valid
        private List<ProjectInput> projects;
    }

    @Data
    public static class VersionRequest {
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
        @NotEmpty(message = "至少选择一个培训项目")
        private List<Long> projectIds;
    }

    @Data
    public static class ReviewRequest {
        @NotNull(message = "培训项目必填")
        private Long projectId;
        @NotNull(message = "缺少 version（乐观锁版本号）")
        private Integer version;
        @NotBlank(message = "审核结果必填")
        private String result;
        @Size(max = 1000, message = "审核意见不能超过 1000 字")
        private String comment;
        @Size(max = 500, message = "代审原因不能超过 500 字")
        private String reason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class PendingQuery extends PageQuery {
        private Long planId;
        private Long unitId;
        private String reviewNode;
    }

    public record SheetSummary(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long planId,
            String planName,
            Integer startYear,
            Integer endYear,
            Integer baselineYear,
            @JsonSerialize(using = ToStringSerializer.class) Long unitId,
            @JsonSerialize(using = ToStringSerializer.class) Long ownerId,
            String status,
            long projectCount,
            Integer version,
            LocalDateTime updatedAt,
            String unitName,
            String ownerName,
            String submitterRole,
            String currentReviewNode,
            Integer revisionNo,
            @JsonSerialize(using = ToStringSerializer.class) Long submittedBy,
            String submittedByName,
            LocalDateTime submittedAt,
            boolean canEdit,
            boolean canReview,
            boolean canAssignStaff) {
    }

    public record StaffView(
            Integer version,
            List<UserRef> staff,
            List<UserRef> assignableStaff,
            boolean canAssignStaff) {
    }

    public record FieldDefinition(
            String key,
            Integer column,
            String label,
            String group,
            String groupLabel,
            String type,
            boolean required,
            List<String> options) {
    }

    public record Project(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            Integer rowNo,
            Map<String, Object> data,
            String status,
            String submitterRole,
            String currentReviewNode,
            Integer revisionNo,
            @JsonSerialize(using = ToStringSerializer.class) Long submittedBy,
            LocalDateTime submittedAt,
            boolean canEdit,
            boolean canReview,
            String reviewComment,
            List<FileInfoResponse> files) {
    }

    public record ReviewItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long projectId,
            Integer rowNo,
            Integer revisionNo,
            String reviewNode,
            String result,
            String comment,
            @JsonSerialize(using = ToStringSerializer.class) Long reviewerId,
            String reviewerName,
            boolean isProxy,
            String proxyReason,
            LocalDateTime createdAt) {
    }

    public record Detail(SheetSummary sheet, List<FieldDefinition> fields, List<Project> projects,
                         List<ReviewItem> reviews) {
    }
}
