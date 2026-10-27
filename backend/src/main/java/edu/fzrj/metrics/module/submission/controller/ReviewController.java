package edu.fzrj.metrics.module.submission.controller;

import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.submission.dto.SubmissionDtos;
import edu.fzrj.metrics.module.submission.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 校级审核接口（API 约定 15.5）
 */
@Tag(name = "审核")
@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "待审核列表（过滤 planId / unitId / indicatorId / year）",
            description = "权限：submission:review；校级负责人只待审本人负责指标的")
    @GetMapping("/reviews/pending")
    @PreAuthorize("hasAuthority('submission:review')")
    public PageResult<SubmissionDtos.ListItem> pending(@ModelAttribute SubmissionDtos.PendingQuery query) {
        return reviewService.pending(query);
    }

    @Operation(summary = "审核：{ result, comment, approvedValue? }，approvedValue 用于核减",
            description = "权限：submission:review（指标负责人；管理员代审需 reason）；必须带 Idempotency-Key；"
                    + "驳回必须意见 60004 / 认定值不能大于填报值 60005 / 年度已锁定 60003")
    @PostMapping("/submissions/{submissionId}/review")
    @PreAuthorize("hasAuthority('submission:review')")
    @Idempotent
    @AuditLog(action = "REVIEW", resourceType = "submission", resourceId = "#submissionId",
            reason = "#request.reason")
    public SubmissionDtos.Detail review(@PathVariable Long submissionId,
                                        @Valid @RequestBody SubmissionDtos.ReviewRequest request) {
        return reviewService.review(submissionId, request);
    }

    @Operation(summary = "批量审核通过（逐条独立事务，任一条失败不影响其他）",
            description = "权限：submission:review；必须带 Idempotency-Key")
    @PostMapping("/submissions/batch-review")
    @PreAuthorize("hasAuthority('submission:review')")
    @Idempotent
    @AuditLog(action = "REVIEW", resourceType = "submission", reason = "#request.reason")
    public SubmissionDtos.BatchReviewResponse batchReview(
            @Valid @RequestBody SubmissionDtos.BatchReviewRequest request) {
        return reviewService.batchReview(request);
    }
}
