package edu.fzrj.metrics.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.Sha256;
import edu.fzrj.metrics.module.audit.entity.AuditLog;
import edu.fzrj.metrics.module.audit.mapper.AuditLogMapper;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.RefreshToken;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.RefreshTokenMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.security.dto.MeResponse;
import edu.fzrj.metrics.security.dto.TokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 认证：账号密码登录（失败 5 次锁 15 分钟）、旋转刷新、退出、当前用户
 */
@Slf4j
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.login.max-fail-count}")
    private int maxFailCount;
    @Value("${app.login.lock-minutes}")
    private int lockMinutes;
    @Value("${app.jwt.refresh-expire-days}")
    private int refreshExpireDays;

    public AuthService(SysUserMapper sysUserMapper, UserRoleMapper userRoleMapper,
                       OrgUnitMapper orgUnitMapper, RefreshTokenMapper refreshTokenMapper,
                       JwtService jwtService, PasswordEncoder passwordEncoder,
                       AuditLogMapper auditLogMapper, ObjectMapper objectMapper) {
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    /** 账号密码登录 */
    @Transactional
    public TokenResponse login(String username, String password, String clientType, String ip) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleted, 0L));
        if (user == null) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (Boolean.FALSE.equals(user.getIsEnabled())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            recordLoginFail(user, now);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        // 成功：清零失败计数，记录最近登录时间
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getLoginFailCount, 0)
                .set(SysUser::getLockedUntil, null)
                .set(SysUser::getLastLoginAt, now));
        TokenResponse response = issueTokens(user, clientType, ip);
        writeLoginAudit(user, clientType, ip);
        return response;
    }

    /** 飞书免登签发 Token（复用账号密码登录的签发与审计逻辑；账号状态 / 匹配由调用方校验） */
    @Transactional
    public TokenResponse loginWithUser(SysUser user, String clientType, String ip) {
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId())
                .set(SysUser::getLastLoginAt, LocalDateTime.now()));
        TokenResponse response = issueTokens(user, clientType, ip);
        writeLoginAudit(user, clientType, ip);
        return response;
    }

    /** 登录成功写审计（LOGIN）；此时还没有 SecurityContext，手工落库。只记身份与客户端信息，不记密码 / Token */
    private void writeLoginAudit(SysUser user, String clientType, String ip) {
        try {
            List<String> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getUserId, user.getId()))
                    .stream().map(UserRole::getRole).toList();
            AuditLog log = new AuditLog();
            log.setOccurredAt(LocalDateTime.now());
            log.setOperatorId(user.getId());
            log.setOperatorName(user.getName());
            log.setUnitId(user.getUnitId());
            log.setRoles(objectMapper.writeValueAsString(roles));
            log.setAction("LOGIN");
            log.setResourceType("auth");
            log.setResourceId(user.getId());
            log.setClientType(clientType);
            log.setIp(ip);
            log.setRequestId(org.slf4j.MDC.get(edu.fzrj.metrics.common.web.RequestIdFilter.MDC_KEY));
            auditLogMapper.insert(log);
        } catch (Exception e) {
            AuthService.log.error("登录审计日志写入失败", e);
        }
    }

    private void recordLoginFail(SysUser user, LocalDateTime now) {
        int failCount = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
        LambdaUpdateWrapper<SysUser> update = new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, user.getId());
        if (failCount >= maxFailCount) {
            update.set(SysUser::getLoginFailCount, 0)
                    .set(SysUser::getLockedUntil, now.plusMinutes(lockMinutes));
            sysUserMapper.update(null, update);
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
        update.set(SysUser::getLoginFailCount, failCount);
        sysUserMapper.update(null, update);
    }

    /** 刷新 Token：每次旋转，旧的立即失效 */
    @Transactional
    public TokenResponse refresh(String refreshToken, String clientType, String ip) {
        String hash = Sha256.hex(refreshToken);
        RefreshToken stored = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, hash));
        if (stored == null || stored.getRevokedAt() != null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        LocalDateTime now = LocalDateTime.now();
        if (stored.getExpiresAt().isBefore(now)) {
            throw new BizException(ErrorCode.TOKEN_EXPIRED);
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, stored.getUserId())
                .eq(SysUser::getDeleted, 0L));
        if (user == null || Boolean.FALSE.equals(user.getIsEnabled())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        TokenResponse response = issueTokens(user, clientType, ip);
        // 吊销旧 token 并指向新 token
        String newHash = Sha256.hex(response.getRefreshToken());
        RefreshToken newStored = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, newHash));
        refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getId, stored.getId())
                .set(RefreshToken::getRevokedAt, now)
                .set(RefreshToken::getReplacedById, newStored.getId()));
        return response;
    }

    /** 退出登录：让 refreshToken 失效 */
    public void logout(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            return;
        }
        refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, Sha256.hex(refreshToken))
                .isNull(RefreshToken::getRevokedAt)
                .set(RefreshToken::getRevokedAt, LocalDateTime.now()));
    }

    /** 当前用户信息 */
    public MeResponse me() {
        LoginUser loginUser = SecurityUtils.getLoginUser();
        SysUser user = sysUserMapper.selectById(loginUser.getUserId());
        String unitName = null;
        if (user.getUnitId() != null) {
            OrgUnit unit = orgUnitMapper.selectById(user.getUnitId());
            unitName = unit == null ? null : unit.getName();
        }
        MeResponse.UserInfo userInfo = new MeResponse.UserInfo(
                user.getId(), user.getUsername(), user.getName(),
                maskPhone(user.getPhone()), user.getEmail(), user.getUnitId(), unitName);
        return new MeResponse(userInfo, loginUser.getRoles(), loginUser.getPermissions(),
                dataScopeOf(loginUser));
    }

    /** 数据范围：ALL 全校 / OWNER 本人负责 / UNIT 本单元（多角色取最大范围） */
    private MeResponse.DataScope dataScopeOf(LoginUser loginUser) {
        List<String> roles = loginUser.getRoles();
        if (roles.contains("ADMIN") || roles.contains("LEADER")) {
            return new MeResponse.DataScope("ALL", null);
        }
        if (roles.contains("SCHOOL_OWNER")) {
            return new MeResponse.DataScope("OWNER", loginUser.getUnitId());
        }
        return new MeResponse.DataScope("UNIT", loginUser.getUnitId());
    }

    /** 签发 accessToken + refreshToken（入库 SHA-256） */
    private TokenResponse issueTokens(SysUser user, String clientType, String ip) {
        List<String> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, user.getId()))
                .stream().map(UserRole::getRole).toList();
        String accessToken = jwtService.createAccessToken(user.getId(), user.getName(), roles);

        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime refreshExpiresAt = LocalDateTime.now().plusDays(refreshExpireDays);
        RefreshToken record = new RefreshToken();
        record.setUserId(user.getId());
        record.setTokenHash(Sha256.hex(refreshToken));
        record.setExpiresAt(refreshExpiresAt);
        record.setClientType(clientType);
        record.setIp(ip);
        refreshTokenMapper.insert(record);

        TokenResponse.UnitRef unitRef = null;
        if (user.getUnitId() != null) {
            var unit = orgUnitMapper.selectById(user.getUnitId());
            if (unit != null) {
                unitRef = new TokenResponse.UnitRef(unit.getId(), unit.getCode(), unit.getName());
            }
        }
        TokenResponse.TokenUser tokenUser = new TokenResponse.TokenUser(user.getId(), user.getName(), unitRef, roles);
        return new TokenResponse(accessToken,
                LocalDateTime.now().plusSeconds(jwtService.getAccessExpireSeconds()),
                refreshToken, refreshExpiresAt, tokenUser);
    }

    /** 手机号脱敏：保留前 3 后 4 */
    static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
