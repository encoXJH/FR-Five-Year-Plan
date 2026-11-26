package edu.fzrj.metrics.module.training.controller;

import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.training.dto.TrainingDtos;
import edu.fzrj.metrics.module.training.service.TrainingReviewService;
import edu.fzrj.metrics.module.training.service.TrainingService;
import edu.fzrj.metrics.module.training.service.TrainingStaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Tag(name = "职业培训信息采集")
@RestController
@RequestMapping("/api/v1")
public class TrainingController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TrainingService trainingService;
    private final TrainingReviewService reviewService;
    private final TrainingStaffService staffService;

    public TrainingController(TrainingService trainingService, TrainingReviewService reviewService,
                              TrainingStaffService staffService) {
        this.trainingService = trainingService;
        this.reviewService = reviewService;
        this.staffService = staffService;
    }

    @Operation(summary = "新建职业培训采集计划", description = "管理员创建；同名有效培训计划重复调用返回已有采集表；必须幂等")
    @PostMapping("/training-plans")
    @PreAuthorize("hasAuthority('system:manage')")
    @Idempotent
    @AuditLog(action = "CREATE", resourceType = "training_sheet", resourceId = "#result.sheet.id", after = "#result.sheet")
    public TrainingDtos.Detail createPlan(@Valid @RequestBody TrainingDtos.CreatePlanRequest request) {
        return trainingService.createPlan(request);
    }

    @Operation(summary = "当前用户可读取的职业培训采集表")
    @GetMapping("/training-sheets")
    @PreAuthorize("hasAnyAuthority('training:fill', 'submission:review')")
    public List<TrainingDtos.SheetSummary> list(@RequestParam(required = false) Long planId) {
        return trainingService.list(planId);
    }

    @Operation(summary = "查看培训采集表干事分配", description = "仅管理员或采集表指定负责人；返回当前干事和候选人")
    @GetMapping("/training-sheets/{sheetId}/staff")
    @PreAuthorize("hasAuthority('training:fill')")
    public TrainingDtos.StaffView staff(@PathVariable Long sheetId) {
        return staffService.staff(sheetId);
    }

    @Operation(summary = "整体保存培训采集表干事", description = "仅管理员或采集表指定负责人；候选人限本学院启用纯干事；需带 version")
    @PutMapping("/training-sheets/{sheetId}/staff")
    @PreAuthorize("hasAuthority('training:fill')")
    @AuditLog(action = "UPDATE", resourceType = "training_sheet", resourceId = "#sheetId",
            before = "staffAuditSnapshot(#sheetId)", after = "staffAuditSnapshot(#sheetId)")
    public TrainingDtos.StaffView putStaff(@PathVariable Long sheetId,
                                           @Valid @RequestBody TrainingDtos.StaffPutRequest request) {
        return staffService.assign(sheetId, request);
    }

    @Operation(summary = "当前用户可处理的职业培训待审列表")
    @GetMapping("/training-reviews/pending")
    @PreAuthorize("hasAuthority('submission:review')")
    public PageResult<TrainingDtos.SheetSummary> pending(@ModelAttribute TrainingDtos.PendingQuery query) {
        return reviewService.pending(query);
    }

    @Operation(summary = "职业培训采集表详情", description = "返回字段 schema、项目、审核历史和本人操作权限")
    @GetMapping("/training-sheets/{sheetId}")
    @PreAuthorize("hasAnyAuthority('training:fill', 'submission:review')")
    public TrainingDtos.Detail detail(@PathVariable Long sheetId) {
        return trainingService.detail(sheetId);
    }

    @Operation(summary = "整体保存培训项目草稿", description = "必须带 version；仅草稿或驳回状态可写；记录前后项目快照")
    @PutMapping("/training-sheets/{sheetId}/projects")
    @PreAuthorize("hasAuthority('training:fill')")
    @AuditLog(action = "UPDATE", resourceType = "training_sheet", resourceId = "#sheetId",
            before = "auditSnapshot(#sheetId)", after = "auditSnapshot(#sheetId)")
    public TrainingDtos.Detail saveProjects(@PathVariable Long sheetId,
                                            @Valid @RequestBody TrainingDtos.SaveProjectsRequest request) {
        return trainingService.saveProjects(sheetId, request);
    }

    @Operation(summary = "提交职业培训采集表", description = "干事送院审，负责人或管理员代填送校审；必须幂等和 version")
    @PostMapping("/training-sheets/{sheetId}/submit")
    @PreAuthorize("hasAuthority('training:fill')")
    @Idempotent
    @AuditLog(action = "SUBMIT", resourceType = "training_sheet", resourceId = "#sheetId",
            before = "auditSnapshot(#sheetId)", after = "auditSnapshot(#sheetId)")
    public TrainingDtos.Detail submit(@PathVariable Long sheetId,
                                      @Valid @RequestBody TrainingDtos.VersionRequest request) {
        return trainingService.submit(sheetId, request);
    }

    @Operation(summary = "审核职业培训采集表", description = "院审通过转校审，校审通过为终态；驳回必填意见；必须幂等和 version")
    @PostMapping("/training-sheets/{sheetId}/review")
    @PreAuthorize("hasAuthority('submission:review')")
    @Idempotent
    @AuditLog(action = "REVIEW", resourceType = "training_sheet", resourceId = "#sheetId",
            reason = "#request.reason", before = "auditSnapshot(#sheetId)", after = "auditSnapshot(#sheetId)")
    public TrainingDtos.Detail review(@PathVariable Long sheetId,
                                      @Valid @RequestBody TrainingDtos.ReviewRequest request) {
        return reviewService.review(sheetId, request);
    }

    @Operation(summary = "按原模板导出职业培训信息表", description = "导出 57 列、四行复合表头，从第 5 行写项目")
    @GetMapping("/training-sheets/{sheetId}/export")
    @PreAuthorize("hasAnyAuthority('training:fill', 'submission:review')")
    @AuditLog(action = "FILE_DOWNLOAD", resourceType = "training_sheet", resourceId = "#sheetId")
    public ResponseEntity<byte[]> export(@PathVariable Long sheetId) {
        TrainingService.ExportFile file = trainingService.export(sheetId);
        String filename = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(file.bytes());
    }

    public Map<String, Object> auditSnapshot(Long sheetId) {
        return trainingService.auditSnapshot(sheetId);
    }

    public Map<String, Object> staffAuditSnapshot(Long sheetId) {
        return staffService.auditSnapshot(sheetId);
    }
}
