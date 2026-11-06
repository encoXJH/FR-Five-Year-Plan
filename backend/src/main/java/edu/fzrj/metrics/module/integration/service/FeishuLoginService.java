package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.Sha256;
import edu.fzrj.metrics.module.integration.client.FeishuClient;
import edu.fzrj.metrics.module.integration.client.TeacherApiClient;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.entity.FeishuBindingTicket;
import edu.fzrj.metrics.module.integration.mapper.FeishuBindingTicketMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.AuthService;
import edu.fzrj.metrics.security.dto.FeishuAuthDtos;
import edu.fzrj.metrics.security.dto.TokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 飞书免登与首次绑定：已绑定 open_id 直接登录；首次绑定必须经校内教师 API 核验，
 * 且严格匹配已有启用账号，不再按手机号自动绑定。
 */
@Slf4j
@Service
public class FeishuLoginService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final FeishuConfig config;
    private final FeishuClient feishuClient;
    private final TeacherApiClient teacherApiClient;
    private final FeishuBindingTicketMapper ticketMapper;
    private final FeishuBindingService bindingService;
    private final SysUserMapper sysUserMapper;
    private final AuthService authService;

    public FeishuLoginService(FeishuConfig config, FeishuClient feishuClient,
                              TeacherApiClient teacherApiClient, FeishuBindingTicketMapper ticketMapper,
                              FeishuBindingService bindingService, SysUserMapper sysUserMapper,
                              AuthService authService) {
        this.config = config;
        this.feishuClient = feishuClient;
        this.teacherApiClient = teacherApiClient;
        this.ticketMapper = ticketMapper;
        this.bindingService = bindingService;
        this.sysUserMapper = sysUserMapper;
        this.authService = authService;
    }

    /** 飞书集成是否可用（供前端决定是否显示免登入口） */
    public boolean enabled() {
        return config.isConfigured();
    }

    /** 浏览器 OAuth 授权跳转地址（API 约定 4.1；未配置返回 20006） */
    public String authorizeUrl(String state) {
        if (!config.isConfigured()) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "平台未配置飞书集成，请使用账号密码登录");
        }
        return feishuClient.buildAuthorizeUrl(state);
    }

    /** code 只兑换一次；未绑定时返回一次性票据，不签发平台 Token。 */
    public FeishuAuthDtos.FeishuLoginResponse login(String code, String clientType, String ip) {
        if (!config.isConfigured()) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "平台未配置飞书集成，请使用账号密码登录");
        }
        FeishuClient.FeishuUserInfo info;
        try {
            String userAccessToken = feishuClient.exchangeUserAccessToken(code);
            info = feishuClient.getLoginUserInfo(userAccessToken);
        } catch (Exception e) {
            log.warn("飞书免登身份核验失败：{}", e.getMessage());
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "飞书身份核验失败，请重试或使用账号密码登录");
        }
        if (info == null || !StringUtils.hasText(info.openId())) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "飞书用户信息不完整");
        }

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getFeishuOpenId, info.openId())
                .eq(SysUser::getDeleted, 0L));
        if (user != null) {
            if (!Boolean.TRUE.equals(user.getIsEnabled())) {
                throw new BizException(ErrorCode.ACCOUNT_DISABLED);
            }
            return FeishuAuthDtos.FeishuLoginResponse.authenticated(
                    authService.loginWithUser(user, clientType, ip));
        }
        return createBindingChallenge(info);
    }

    /** 首次绑定：先校验票据和飞书姓名，再调用校内教师 API，最后进入独立绑定事务。 */
    public TokenResponse bind(FeishuAuthDtos.FeishuBindRequest request, String clientType, String ip) {
        String name = request.name().trim();
        String employeeId = request.employeeId().trim();
        FeishuBindingTicket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<FeishuBindingTicket>()
                .eq(FeishuBindingTicket::getTicketHash, Sha256.hex(request.bindingTicket())));
        LocalDateTime now = LocalDateTime.now();
        if (ticket == null || ticket.getConsumedAt() != null || !ticket.getExpiresAt().isAfter(now)) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "绑定票据已失效，请重新使用飞书登录");
        }
        if (ticket.getFeishuName() == null || !ticket.getFeishuName().equals(name)) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "飞书姓名与填写姓名不一致");
        }

        TeacherApiClient.TeacherIdentity identity;
        try {
            identity = teacherApiClient.verify(name, employeeId);
        } catch (TeacherApiClient.TeacherApiException e) {
            throw new BizException(ErrorCode.MIDDLE_PLATFORM_UNAVAILABLE, "校内教师核验服务暂不可用，请稍后重试");
        }
        if (identity == null) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "姓名或工号核验未通过，请核对后重试");
        }
        return bindingService.completeBinding(ticket.getId(), name, employeeId, clientType, ip);
    }

    private FeishuAuthDtos.FeishuLoginResponse createBindingChallenge(FeishuClient.FeishuUserInfo info) {
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(config.getBindingTicketExpireMinutes());
        FeishuBindingTicket stored = new FeishuBindingTicket();
        stored.setTicketHash(Sha256.hex(ticket));
        stored.setFeishuOpenId(info.openId());
        stored.setFeishuUnionId(info.unionId());
        stored.setFeishuName(info.name());
        stored.setExpiresAt(expiresAt);
        stored.setCreatedAt(now);
        ticketMapper.insert(stored);
        return FeishuAuthDtos.FeishuLoginResponse.bindingRequired(ticket, expiresAt);
    }
}
