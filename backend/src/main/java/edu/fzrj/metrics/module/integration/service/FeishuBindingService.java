package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.web.RequestIdFilter;
import edu.fzrj.metrics.module.audit.entity.AuditLog;
import edu.fzrj.metrics.module.audit.mapper.AuditLogMapper;
import edu.fzrj.metrics.module.integration.entity.FeishuBindingTicket;
import edu.fzrj.metrics.module.integration.mapper.FeishuBindingTicketMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.security.AuthService;
import edu.fzrj.metrics.security.dto.TokenResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 首次绑定事务：一次性消费票据并严格绑定已有启用账号。 */
@Slf4j
@Service
public class FeishuBindingService {

    private final FeishuBindingTicketMapper ticketMapper;
    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final AuthService authService;

    public FeishuBindingService(FeishuBindingTicketMapper ticketMapper, SysUserMapper sysUserMapper,
                                UserRoleMapper userRoleMapper, AuditLogMapper auditLogMapper,
                                ObjectMapper objectMapper, AuthService authService) {
        this.ticketMapper = ticketMapper;
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.authService = authService;
    }

    @Transactional
    public TokenResponse completeBinding(Long ticketId, String name, String employeeId,
                                         String clientType, String ip) {
        LocalDateTime now = LocalDateTime.now();
        int consumed = ticketMapper.update(null, new LambdaUpdateWrapper<FeishuBindingTicket>()
                .eq(FeishuBindingTicket::getId, ticketId)
                .isNull(FeishuBindingTicket::getConsumedAt)
                .gt(FeishuBindingTicket::getExpiresAt, now)
                .set(FeishuBindingTicket::getConsumedAt, now));
        if (consumed != 1) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "绑定票据已失效，请重新使用飞书登录");
        }
        FeishuBindingTicket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "绑定票据无效");
        }

        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, employeeId)
                .eq(SysUser::getDeleted, 0L));
        if (user == null || !employeeId.equals(user.getUsername()) || !name.equals(user.getName())
                || !Boolean.TRUE.equals(user.getIsEnabled())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED,
                    "未找到姓名、工号一致且已启用的平台账号，请联系管理员");
        }
        if (user.getFeishuOpenId() != null) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "该平台账号已绑定其他飞书账号，请联系管理员");
        }
        SysUser openIdOwner = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getFeishuOpenId, ticket.getFeishuOpenId())
                .eq(SysUser::getDeleted, 0L));
        if (openIdOwner != null) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "该飞书账号已绑定平台用户，请直接登录");
        }

        try {
            int updated = sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, user.getId())
                    .eq(SysUser::getVersion, user.getVersion())
                    .eq(SysUser::getDeleted, 0L)
                    .eq(SysUser::getIsEnabled, true)
                    .isNull(SysUser::getFeishuOpenId)
                    .set(SysUser::getFeishuOpenId, ticket.getFeishuOpenId())
                    .set(SysUser::getFeishuUnionId, ticket.getFeishuUnionId())
                    .set(SysUser::getUpdatedAt, now)
                    .set(SysUser::getVersion, user.getVersion() + 1));
            if (updated != 1) {
                throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "账号绑定状态已变化，请重新使用飞书登录");
            }
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "该飞书账号已绑定其他用户，请联系管理员");
        }
        user.setFeishuOpenId(ticket.getFeishuOpenId());
        user.setFeishuUnionId(ticket.getFeishuUnionId());
        TokenResponse response = authService.loginWithUser(user, clientType, ip);
        writeBindingAudit(user, clientType, ip);
        return response;
    }

    /** 公开绑定接口没有 SecurityContext，仿照登录审计手工写入；不记录票据或飞书身份。 */
    private void writeBindingAudit(SysUser user, String clientType, String ip) {
        try {
            List<String> roles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getUserId, user.getId()))
                    .stream().map(UserRole::getRole).toList();
            AuditLog audit = new AuditLog();
            audit.setOccurredAt(LocalDateTime.now());
            audit.setOperatorId(user.getId());
            audit.setOperatorName(user.getName());
            audit.setUnitId(user.getUnitId());
            audit.setRoles(objectMapper.writeValueAsString(roles));
            audit.setAction("UPDATE");
            audit.setResourceType("sys_user");
            audit.setResourceId(user.getId());
            audit.setBeforeJson(objectMapper.writeValueAsString(Map.of("feishuBound", false)));
            audit.setAfterJson(objectMapper.writeValueAsString(Map.of("feishuBound", true)));
            audit.setClientType(clientType);
            audit.setIp(ip);
            audit.setRequestId(MDC.get(RequestIdFilter.MDC_KEY));
            auditLogMapper.insert(audit);
        } catch (Exception e) {
            log.error("飞书首次绑定审计写入失败 userId={}", user.getId(), e);
        }
    }
}
