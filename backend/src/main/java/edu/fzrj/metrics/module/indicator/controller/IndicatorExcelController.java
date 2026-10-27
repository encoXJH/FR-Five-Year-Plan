package edu.fzrj.metrics.module.indicator.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.service.IndicatorExcelService;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 指标 Excel 导入接口（API 约定 15.3）
 */
@Tag(name = "指标导入")
@RestController
@PreAuthorize("hasAuthority('indicator:edit')")
public class IndicatorExcelController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final IndicatorExcelService indicatorExcelService;
    private final PlanService planService;

    public IndicatorExcelController(IndicatorExcelService indicatorExcelService, PlanService planService) {
        this.indicatorExcelService = indicatorExcelService;
        this.planService = planService;
    }

    @Operation(summary = "下载 Excel 导入模板（年份列按计划起止年动态生成）")
    @GetMapping("/api/v1/plans/{planId}/indicators/import-template")
    public ResponseEntity<byte[]> importTemplate(@PathVariable Long planId) {
        Plan plan = planService.requirePlan(planId);
        byte[] bytes = indicatorExcelService.buildImportTemplate(plan);
        String filename = URLEncoder.encode("指标导入模板-" + plan.getName() + ".xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(bytes);
    }

    @Operation(summary = "上传 Excel 预览（返回 importId、逐行解析结果和错误明细）")
    @PostMapping("/api/v1/plans/{planId}/indicators/import-preview")
    public IndicatorDtos.ImportPreviewResponse importPreview(@PathVariable Long planId,
                                                             @RequestParam("file") MultipartFile file) {
        return indicatorExcelService.preview(planId, file);
    }

    @Operation(summary = "确认导入 { importId }（事务内批量写入指标与量化年度目标）",
            description = "必须带 Idempotency-Key；存在校验失败的行时返回 40001；导入不创建或分配 S，A 的 S 归属由 ADMIN 另行配置")
    @PostMapping("/api/v1/plans/{planId}/indicators/import-confirm")
    @Idempotent
    @AuditLog(action = "IMPORT", resourceType = "plan", resourceId = "#planId")
    public IndicatorDtos.ImportConfirmResponse importConfirm(
            @PathVariable Long planId,
            @Valid @RequestBody IndicatorDtos.ImportConfirmRequest request) {
        return indicatorExcelService.confirm(planId, request.getImportId());
    }
}
