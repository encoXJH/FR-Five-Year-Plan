package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.integration.client.FeishuClient;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.service.support.FeishuBindMatcher;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.AuthService;
import edu.fzrj.metrics.security.dto.TokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 飞书免登与 OAuth（API 约定 4.1 / 15.1）：
 * code → 飞书 user_access_token → 用户信息 → 按 open_id / 手机号匹配 sys_user → 签发平台 JWT。
 * 未配置飞书或身份核验失败返回 20006；匹配到但账号停用返回 20007；飞书侧任何异常不影响账号密码登录。
 */
@Slf4j
@Service
public class FeishuLoginService {

    private final FeishuConfig config;
    private final FeishuClient feishuClient;
    private final SysUserMapper sysUserMapper;
    private final AuthService authService;

    public FeishuLoginService(FeishuConfig config, FeishuClient feishuClient,
                              SysUserMapper sysUserMapper, AuthService authService) {
        this.config = config;
        this.feishuClient = feishuClient;
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

    /** 飞书免登：code 换身份 → 匹配平台用户 → 发平台 Token */
    public TokenResponse login(String code, String clientType, String ip) {
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

        List<FeishuBindMatcher.Candidate> candidates = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getDeleted, 0L)
                        .select(SysUser::getId, SysUser::getPhone, SysUser::getFeishuOpenId))
                .stream()
                .map(u -> new FeishuBindMatcher.Candidate(u.getId(), u.getPhone(), u.getFeishuOpenId()))
                .toList();
        FeishuBindMatcher.Decision decision = FeishuBindMatcher.match(info.openId(), info.mobile(), candidates);
        if (decision.result() == FeishuBindMatcher.MatchResult.NOT_FOUND) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "飞书账号未绑定平台用户，请联系管理员");
        }

        SysUser user = sysUserMapper.selectById(decision.user().id());
        if (user == null || Boolean.FALSE.equals(user.getIsEnabled())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (decision.needBind()) {
            bindOpenId(user, info);
        }
        return authService.loginWithUser(user, clientType, ip);
    }

    /** 手机号命中后回写 open_id / union_id；该用户已绑定其他 open_id 时拒绝（防顶号） */
    private void bindOpenId(SysUser user, FeishuClient.FeishuUserInfo info) {
        if (StringUtils.hasText(user.getFeishuOpenId()) && !info.openId().equals(user.getFeishuOpenId())) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "该手机号已绑定其他飞书账号，请联系管理员");
        }
        try {
            sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .set(SysUser::getFeishuOpenId, info.openId())
                    .set(SysUser::getFeishuUnionId, info.unionId())
                    .set(SysUser::getUpdatedAt, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            // open_id 唯一索引冲突：其他用户已绑定该 open_id，视为绑定失败但不影响已匹配用户登录
            log.warn("飞书 open_id 绑定冲突 userId={}", user.getId());
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "该飞书账号已绑定其他用户，请联系管理员");
        }
    }
}
