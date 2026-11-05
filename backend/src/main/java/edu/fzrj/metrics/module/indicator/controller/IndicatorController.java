package edu.fzrj.metrics.module.indicator.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.indicator.dto.IndicatorDtos;
import edu.fzrj.metrics.module.indicator.service.DecompositionService;
import edu.fzrj.metrics.module.indicator.service.IndicatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指标接口（API 约定 15.3）
 */
@Tag(name = "指标")
@RestController
public class IndicatorController {

    private final IndicatorService indicatorService;
    private final DecompositionService decompositionService;
    private final edu.fzrj.metrics.module.indicator.service.ManualValueService manualValueService;

    public IndicatorController(IndicatorService indicatorService,
                               DecompositionService decompositionService,
                               edu.fzrj.metrics.module.indicator.service.ManualValueService manualValueService) {
        this.indicatorService = indicatorService;
        this.decompositionService = decompositionService;
        this.manualValueService = manualValueService;
    }

    @Operation(summary = "指标列表（不分页）；view=tree 返回 A → A+ 树",
            description = "按数据范围过滤：SCHOOL_OWNER 只看本人负责的 A/A+；UNIT_OWNER 阶段 2 不可见")
    @GetMapping("/api/v1/plans/{planId}/indicators")
    public List<IndicatorDtos.TreeNode> list(@PathVariable Long planId,
                                             @RequestParam(required = false) String view,
                                             @RequestParam(required = false) String level,
                                             @RequestParam(required = false) Long directionId,
                                             @RequestParam(required = false) Long ownerId,
                                             @RequestParam(required = false) String keyword) {
        return indicatorService.tree(planId, level, directionId, ownerId, keyword, view);
    }

    @Operation(summary = "新增 A 或 A+（A+ 必须带 parentId 且父 A 可量化，否则 40006）",
            description = "权限：indicator:edit；A 的 code 为空时按所属方向生成 A01、B01…，未分配方向暂用 A 系列；草稿 A 的 S 归属可暂空，仅 ADMIN 可在创建时指定")
    @PostMapping("/api/v1/plans/{planId}/indicators")
    @PreAuthorize("hasAuthority('indicator:edit')")
    @AuditLog(action = "CREATE", resourceType = "indicator", resourceId = "#result.id", after = "#result")
    public IndicatorDtos.Detail create(@PathVariable Long planId,
                                       @Valid @RequestBody IndicatorDtos.CreateRequest request) {
        return indicatorService.create(planId, request);
    }

    @Operation(summary = "一页式新建 A+ 并保存 B 分解草稿",
            description = "权限：indicator:edit + allocation:edit；同一事务创建 A+（含年度目标）并整体保存 B 草稿；"
                    + "不下发、不通知；必须带 Idempotency-Key")
    @PostMapping("/api/v1/plans/{planId}/indicators/{parentId}/decomposition")
    @PreAuthorize("hasAuthority('indicator:edit') and hasAuthority('allocation:edit')")
    @Idempotent
    @AuditLog(action = "CREATE", resourceType = "indicator", resourceId = "#result.aPlus.id",
            before = "detail(#parentId)", after = "#result")
    public IndicatorDtos.DecompositionResponse decompose(
            @PathVariable Long planId, @PathVariable Long parentId,
            @Valid @RequestBody IndicatorDtos.DecompositionRequest request) {
        return decompositionService.decompose(planId, parentId, request);
    }

    @Operation(summary = "指标详情（含年度目标、下属 A+ 与 B 概要；历史 A 直承 B 只读返回）")
    @GetMapping("/api/v1/indicators/{indicatorId}")
    public IndicatorDtos.Detail detail(@PathVariable Long indicatorId) {
        return indicatorService.detail(indicatorId);
    }

    @Operation(summary = "修改未下发指标（带 version，冲突 10005；已下发 50003）", description = "权限：indicator:edit")
    @PatchMapping("/api/v1/indicators/{indicatorId}")
    @PreAuthorize("hasAuthority('indicator:edit')")
    @AuditLog(action = "UPDATE", resourceType = "indicator", resourceId = "#indicatorId",
            before = "detail(#indicatorId)", after = "#result")
    public IndicatorDtos.Detail patch(@PathVariable Long indicatorId, @RequestBody JsonNode body) {
        return indicatorService.patch(indicatorId, new PatchHelper(body));
    }

    @Operation(summary = "配置或清除未下发 A 的 S 归属（同步下属 A+）",
            description = "仅 ADMIN；带 version（冲突 10005）；自身或下属已下发后不可修改")
    @PutMapping("/api/v1/indicators/{indicatorId}/direction")
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "UPDATE", resourceType = "indicator", resourceId = "#indicatorId",
            before = "detail(#indicatorId)", after = "#result")
    public IndicatorDtos.Detail assignDirection(@PathVariable Long indicatorId,
                                                @Valid @RequestBody IndicatorDtos.DirectionAssignRequest request) {
        return indicatorService.assignDirection(indicatorId, request);
    }

    @Operation(summary = "删除未下发指标（已下发后 40004；有 A+ 子级 40008）", description = "权限：indicator:edit")
    @DeleteMapping("/api/v1/indicators/{indicatorId}")
    @PreAuthorize("hasAuthority('indicator:edit')")
    @AuditLog(action = "DELETE", resourceType = "indicator", resourceId = "#indicatorId",
            before = "detail(#indicatorId)")
    public void delete(@PathVariable Long indicatorId) {
        indicatorService.delete(indicatorId);
    }

    @Operation(summary = "设置未下发指标的五年总量和年度目标（INCREMENT 校验 Σ年=总量 40002；比率类不求和；BOOLEAN 完成年唯一）",
            description = "权限：indicator:edit；自身或下属已下发后须发起目标调整")
    @PutMapping("/api/v1/indicators/{indicatorId}/year-targets")
    @PreAuthorize("hasAuthority('indicator:edit')")
    @AuditLog(action = "UPDATE", resourceType = "indicator", resourceId = "#indicatorId",
            before = "detail(#indicatorId)", after = "#result")
    public IndicatorDtos.Detail putYearTargets(@PathVariable Long indicatorId,
                                               @Valid @RequestBody IndicatorDtos.YearTargetsPutRequest request) {
        return indicatorService.putYearTargets(indicatorId, request);
    }

    @Operation(summary = "MANUAL 汇总 A 指标的人工认定（PRD 4.5：校级项目负责人手工认定完成值）",
            description = "权限：indicator:edit + 指标负责人数据范围（越权 20005）；认定值即该年 A 完成值并重算达成状态；"
                    + "年度未激活 50004、已锁定 60003、计划归档 30001；带 version（冲突 10005）；必须带 Idempotency-Key")
    @PutMapping("/api/v1/indicators/{indicatorId}/year-targets/{year}/manual-value")
    @PreAuthorize("hasAuthority('indicator:edit')")
    @edu.fzrj.metrics.common.idempotent.Idempotent
    @AuditLog(action = "UPDATE", resourceType = "indicator", resourceId = "#indicatorId", reason = "#request.note",
            before = "detail(#indicatorId)", after = "detail(#indicatorId)")
    public edu.fzrj.metrics.module.indicator.dto.ManualValueDtos.View recognizeManualValue(
            @PathVariable Long indicatorId, @PathVariable Integer year,
            @Valid @RequestBody edu.fzrj.metrics.module.indicator.dto.ManualValueDtos.RecognizeRequest request) {
        return manualValueService.recognize(indicatorId, year, request);
    }

}
