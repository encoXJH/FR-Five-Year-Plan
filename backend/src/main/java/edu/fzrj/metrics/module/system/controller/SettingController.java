package edu.fzrj.metrics.module.system.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.service.SettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统参数（API 约定 15.10、PRD FR-M12-05）：文件格式与大小、预警阈值、提醒天数、年度核算截止日等
 */
@Tag(name = "系统参数")
@RestController
@RequestMapping("/api/v1/settings")
public class SettingController {

    private final SettingService settingService;

    public SettingController(SettingService settingService) {
        this.settingService = settingService;
    }

    @Operation(summary = "系统参数列表（权限：system:manage；响应 { items }，每项含 version）")
    @GetMapping
    @PreAuthorize("hasAuthority('system:manage')")
    public SystemDtos.SettingListView list() {
        return settingService.list();
    }

    @Operation(summary = "修改系统参数（逐项：key + value + version）",
            description = "权限：system:manage；只允许修改预置参数的值；按 valueType 校验（NUMBER/BOOLEAN/JSON/STRING）；"
                    + "任一失败整体不落库；版本冲突 10005；审计记录变更前后值")
    @PatchMapping
    @PreAuthorize("hasAuthority('system:manage')")
    @AuditLog(action = "UPDATE", resourceType = "setting",
            before = "#result.auditBefore", after = "#result.auditAfter")
    public SystemDtos.SettingListView patch(@Valid @RequestBody SystemDtos.SettingPatchRequest request) {
        return settingService.patch(request);
    }
}
