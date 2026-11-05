package edu.fzrj.metrics.module.integration.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** 飞书 OAuth 浏览器回调适配：只转发授权结果，平台 Token 仍由既有免登接口签发。 */
@Tag(name = "飞书集成")
@RestController
public class FeishuOAuthCallbackController {

    @Operation(summary = "飞书 OAuth 登录回调",
            description = "公开完整路径 /api/callback；接收飞书授权结果并 302 跳转前端免登页，"
                    + "仅转发 code、state、error、error_description，不在 URL 中返回平台 Token")
    @GetMapping("/api/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription) {
        UriComponentsBuilder target = UriComponentsBuilder.fromPath("/login/feishu-callback");
        addQueryParam(target, "code", code);
        addQueryParam(target, "state", state);
        addQueryParam(target, "error", error);
        addQueryParam(target, "error_description", errorDescription);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(target.build().encode().toUri())
                .build();
    }

    private void addQueryParam(UriComponentsBuilder builder, String name, String value) {
        if (StringUtils.hasText(value)) {
            builder.queryParam(name, value);
        }
    }
}
