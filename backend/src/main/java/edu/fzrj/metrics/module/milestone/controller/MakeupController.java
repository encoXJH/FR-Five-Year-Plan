package edu.fzrj.metrics.module.milestone.controller;

import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.milestone.dto.MakeupDtos;
import edu.fzrj.metrics.module.milestone.service.MakeupService;
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

import java.util.List;

/**
 * 待补做接口（API 约定 15.7；记录由年度核算生成，此处提供查询与补做完成）
 */
@Tag(name = "待补做")
@RestController
@RequestMapping("/api/v1")
public class MakeupController {

    private final MakeupService makeupService;

    public MakeupController(MakeupService makeupService) {
        this.makeupService = makeupService;
    }

    @Operation(summary = "某 B 任务的待补做记录", description = "已登录，按任务数据范围，越权 20005")
    @GetMapping("/tasks/{taskId}/makeups")
    public List<MakeupDtos.View> listByTask(@PathVariable Long taskId) {
        return makeupService.listByTask(taskId);
    }

    @Operation(summary = "补做完成（PENDING → DONE，来源年度 MAKEUP_PENDING → MADE_UP）",
            description = "权限：submission:submit（本单元；管理员可代操作）；需带 version；必须带 Idempotency-Key")
    @PostMapping("/makeups/{makeupId}/complete")
    @PreAuthorize("hasAuthority('submission:submit')")
    @Idempotent
    @AuditLog(action = "UPDATE", resourceType = "makeup", resourceId = "#makeupId")
    public MakeupDtos.View complete(@PathVariable Long makeupId,
                                    @Valid @RequestBody MakeupDtos.CompleteRequest request) {
        return makeupService.complete(makeupId, request.getVersion());
    }
}
