package edu.fzrj.metrics.module.stats.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.stats.dto.SettlementDtos;
import edu.fzrj.metrics.module.stats.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 年度核算与五年核算接口（API 约定 15.8）
 */
@Tag(name = "统计核算")
@RestController
@RequestMapping("/api/v1/plans")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Operation(summary = "年度核算并锁定（逐 task_year 终态化达标/未达标，缺口生成待补做）",
            description = "权限：settlement:manage；需带 version（计划版本号）；必须带 Idempotency-Key；已锁定返回 60003")
    @PostMapping("/{planId}/years/{year}/settle")
    @PreAuthorize("hasAuthority('settlement:manage')")
    @Idempotent
    @AuditLog(action = "SETTLE", resourceType = "plan", resourceId = "#planId")
    public SettlementDtos.SettleResult settle(@PathVariable Long planId, @PathVariable Integer year,
                                              @Valid @RequestBody SettlementDtos.SettleRequest request) {
        return settlementService.settle(planId, year, request.getVersion());
    }

    @Operation(summary = "年度核算结果查询（含逐任务明细与待补做关联）",
            description = "已登录；按数据范围过滤明细；未核算时 items 为空")
    @GetMapping("/{planId}/years/{year}/settlement")
    public SettlementDtos.SettlementView settlement(@PathVariable Long planId, @PathVariable Integer year) {
        return settlementService.settlement(planId, year);
    }

    @Operation(summary = "解锁年度数据（敏感操作，必须带 reason）",
            description = "权限：settlement:manage；需带 version（计划版本号）；必须带 Idempotency-Key")
    @PostMapping("/{planId}/years/{year}/unlock")
    @PreAuthorize("hasAuthority('settlement:manage')")
    @Idempotent
    @AuditLog(action = "UNLOCK", resourceType = "plan", resourceId = "#planId", reason = "#request.reason")
    public SettlementDtos.UnlockResult unlock(@PathVariable Long planId, @PathVariable Integer year,
                                              @Valid @RequestBody SettlementDtos.UnlockRequest request) {
        return settlementService.unlock(planId, year, request.getVersion(), request.getReason());
    }

    @Operation(summary = "五年核算（规划期全部年度锁定后；任务终态化并生成五年核算结果）",
            description = "权限：settlement:manage；需带 version（计划版本号）；必须带 Idempotency-Key；重复调用幂等返回已生成结果")
    @PostMapping("/{planId}/final-settle")
    @PreAuthorize("hasAuthority('settlement:manage')")
    @Idempotent
    @AuditLog(action = "SETTLE", resourceType = "plan", resourceId = "#planId")
    public SettlementDtos.FinalSettlementView finalSettle(@PathVariable Long planId,
                                                          @Valid @RequestBody SettlementDtos.SettleRequest request) {
        return settlementService.finalSettle(planId, request.getVersion());
    }

    @Operation(summary = "五年核算结果查询", description = "已登录；未核算返回 10003")
    @GetMapping("/{planId}/final-settlement")
    public SettlementDtos.FinalSettlementView finalSettlement(@PathVariable Long planId) {
        return settlementService.finalSettlement(planId);
    }
}
