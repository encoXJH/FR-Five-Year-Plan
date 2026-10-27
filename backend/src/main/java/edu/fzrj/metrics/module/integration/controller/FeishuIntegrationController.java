package edu.fzrj.metrics.module.integration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.audit.AuditLog;
import edu.fzrj.metrics.common.idempotent.Idempotent;
import edu.fzrj.metrics.module.integration.dto.FeishuDtos;
import edu.fzrj.metrics.module.integration.service.FeishuEventService;
import edu.fzrj.metrics.module.integration.service.FeishuSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 飞书集成接口（API 约定 15.11）：事件回调走飞书签名（不使用平台 Token），组织同步走 system:manage。
 */
@Tag(name = "飞书集成")
@RestController
@RequestMapping("/api/v1/integrations/feishu")
public class FeishuIntegrationController {

    private final FeishuEventService feishuEventService;
    private final FeishuSyncService feishuSyncService;

    public FeishuIntegrationController(FeishuEventService feishuEventService,
                                       FeishuSyncService feishuSyncService) {
        this.feishuEventService = feishuEventService;
        this.feishuSyncService = feishuSyncService;
    }

    @Operation(summary = "飞书事件回调",
            description = "飞书签名验签（X-Lark-Signature），处理 url_verification，按 event_id 去重；"
                    + "3 秒内返回，业务处理异步执行；签名或 Verification Token 校验失败 90002")
    @PostMapping("/events")
    public ResponseEntity<String> events(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature) {
        JsonNode challenge = feishuEventService.handle(timestamp, nonce, signature, rawBody);
        // 回调响应不走统一 ApiResponse 包装（ResponseEntity 跳过 ResponseAdvice），url_verification 必须原样回 challenge
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(challenge == null ? "{}" : challenge.toString());
    }

    @Operation(summary = "飞书集成状态（P15 飞书集成 Tab）",
            description = "权限：system:manage；配置概览（AppID 脱敏、回调地址）+ 最近一次同步结果（取审计日志 SYNC_ORG 快照）")
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('system:manage')")
    public FeishuDtos.StatusView status() {
        return feishuSyncService.status();
    }

    @Operation(summary = "手动同步组织架构",
            description = "权限：system:manage；幂等。从飞书通讯录拉部门与人员，按手机号匹配更新 "
                    + "sys_user 的 feishu_open_id / 姓名 / 所属单元（单元按部门名匹配）；未配置飞书 20006，飞书接口失败 90001；"
                    + "同步结果写入审计日志")
    @PostMapping("/sync-org")
    @PreAuthorize("hasAuthority('system:manage')")
    @Idempotent
    @AuditLog(action = "SYNC_ORG", resourceType = "feishu", after = "#result")
    public FeishuDtos.SyncOrgView syncOrg() {
        return feishuSyncService.sync();
    }
}
