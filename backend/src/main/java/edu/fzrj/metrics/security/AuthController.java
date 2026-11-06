package edu.fzrj.metrics.security;

import edu.fzrj.metrics.module.integration.service.FeishuLoginService;
import edu.fzrj.metrics.security.dto.FeishuAuthDtos;
import edu.fzrj.metrics.security.dto.LoginRequest;
import edu.fzrj.metrics.security.dto.MeResponse;
import edu.fzrj.metrics.security.dto.RefreshRequest;
import edu.fzrj.metrics.security.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（API 约定 15.1）
 */
@Tag(name = "认证")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final FeishuLoginService feishuLoginService;

    public AuthController(AuthService authService, FeishuLoginService feishuLoginService) {
        this.authService = authService;
        this.feishuLoginService = feishuLoginService;
    }

    @Operation(summary = "账号密码登录")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request.getUsername(), request.getPassword(),
                clientType(http), clientIp(http));
    }

    @Operation(summary = "刷新 Token")
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(request.getRefreshToken(), clientType(http), clientIp(http));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public void logout(@RequestBody(required = false) RefreshRequest request) {
        authService.logout(request == null ? null : request.getRefreshToken());
    }

    @Operation(summary = "当前用户、角色、权限码、数据范围")
    @GetMapping("/me")
    public MeResponse me() {
        return authService.me();
    }

    @Operation(summary = "飞书免登",
            description = "公开；飞书 JSAPI / OAuth 的一次性 code 换身份。已绑定 open_id 返回平台 Token；"
                    + "首次登录返回 bindRequired=true 和短时一次性 bindingTicket，不签发 Token")
    @PostMapping("/feishu/login")
    public FeishuAuthDtos.FeishuLoginResponse feishuLogin(
            @Valid @RequestBody FeishuAuthDtos.FeishuLoginRequest request, HttpServletRequest http) {
        return feishuLoginService.login(request.code(), clientType(http), clientIp(http));
    }

    @Operation(summary = "飞书首次绑定",
            description = "公开；凭一次性 bindingTicket 提交姓名和工号，经校内教师 API 核验后，"
                    + "严格匹配已有启用账号并绑定飞书身份；成功返回平台 Token，不自动创建用户")
    @PostMapping("/feishu/bind")
    public TokenResponse feishuBind(@Valid @RequestBody FeishuAuthDtos.FeishuBindRequest request,
                                    HttpServletRequest http) {
        return feishuLoginService.bind(request, clientType(http), clientIp(http));
    }

    @Operation(summary = "飞书授权跳转地址",
            description = "公开；普通浏览器场景跳转飞书 OAuth 授权页；未配置飞书 20006")
    @GetMapping("/feishu/authorize-url")
    public FeishuAuthDtos.AuthorizeUrlView feishuAuthorizeUrl(
            @RequestParam(required = false) String state) {
        return new FeishuAuthDtos.AuthorizeUrlView(feishuLoginService.authorizeUrl(state));
    }

    @Operation(summary = "飞书集成可用性",
            description = "公开；未配置飞书时 enabled=false，前端自动隐藏飞书入口（登录前可查询）")
    @GetMapping("/feishu/config")
    public FeishuAuthDtos.FeishuConfigView feishuConfig() {
        return new FeishuAuthDtos.FeishuConfigView(feishuLoginService.enabled());
    }

    private String clientType(HttpServletRequest request) {
        String clientType = request.getHeader("X-Client-Type");
        return StringUtils.hasText(clientType) ? clientType : "WEB";
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
