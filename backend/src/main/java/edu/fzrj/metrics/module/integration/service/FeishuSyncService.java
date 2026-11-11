package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.integration.client.FeishuClient;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.dto.FeishuDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.service.UserUnitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 飞书组织同步（API 约定第 11 章 / 15.11，P1 管理员手动触发）：
 * 拉取飞书通讯录部门与成员，按手机号匹配 sys_user，回写姓名 / 所属单元
 * （单元按飞书部门名与 org_unit.name 精确匹配）。不创建平台用户、不改角色，也不自动绑定 open_id；
 * 首次飞书绑定必须经校内教师接口核验。同步结果写审计日志。
 */
@Slf4j
@Service
public class FeishuSyncService {

    private final FeishuConfig config;
    private final FeishuClient feishuClient;
    private final SysUserMapper sysUserMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final edu.fzrj.metrics.module.audit.mapper.AuditLogMapper auditLogMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final UserUnitService userUnitService;

    public FeishuSyncService(FeishuConfig config, FeishuClient feishuClient,
                             SysUserMapper sysUserMapper, OrgUnitMapper orgUnitMapper,
                             edu.fzrj.metrics.module.audit.mapper.AuditLogMapper auditLogMapper,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                             UserUnitService userUnitService) {
        this.config = config;
        this.feishuClient = feishuClient;
        this.sysUserMapper = sysUserMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.userUnitService = userUnitService;
    }

    /** 飞书集成状态：配置概览 + 最近一次同步结果（取审计日志最新一条 SYNC_ORG 的 after 快照） */
    public FeishuDtos.StatusView status() {
        String appId = config.getAppId();
        String appIdMasked = !config.isConfigured() ? null
                : appId.length() <= 6 ? "****" : appId.substring(0, 6) + "****";
        String redirectUri = StringUtils.hasText(config.getRedirectUri()) ? config.getRedirectUri() : null;
        String eventCallbackUrl = StringUtils.hasText(config.getAppBaseUrl())
                ? config.getAppBaseUrl() + "/api/v1/integrations/feishu/events" : null;
        edu.fzrj.metrics.module.audit.entity.AuditLog lastSync = auditLogMapper.selectOne(
                new LambdaQueryWrapper<edu.fzrj.metrics.module.audit.entity.AuditLog>()
                        .eq(edu.fzrj.metrics.module.audit.entity.AuditLog::getAction, "SYNC_ORG")
                        .orderByDesc(edu.fzrj.metrics.module.audit.entity.AuditLog::getOccurredAt)
                        .last("LIMIT 1"));
        java.time.LocalDateTime lastSyncAt = null;
        FeishuDtos.SyncOrgView lastSyncResult = null;
        if (lastSync != null) {
            lastSyncAt = lastSync.getOccurredAt();
            if (StringUtils.hasText(lastSync.getAfterJson())) {
                try {
                    lastSyncResult = objectMapper.readValue(lastSync.getAfterJson(), FeishuDtos.SyncOrgView.class);
                } catch (Exception e) {
                    log.warn("最近一次组织同步结果快照解析失败：{}", e.getMessage());
                }
            }
        }
        return new FeishuDtos.StatusView(config.isConfigured(), appIdMasked, redirectUri,
                eventCallbackUrl, lastSyncAt, lastSyncResult);
    }

    public FeishuDtos.SyncOrgView sync() {
        if (!config.isConfigured()) {
            throw new BizException(ErrorCode.FEISHU_LOGIN_FAILED, "平台未配置飞书集成，不能同步组织架构");
        }
        List<FeishuClient.ContactDepartment> departments;
        try {
            departments = feishuClient.listAllDepartments();
        } catch (Exception e) {
            log.warn("飞书通讯录部门拉取失败：{}", e.getMessage());
            throw new BizException(ErrorCode.FEISHU_API_FAILED, "飞书通讯录部门拉取失败");
        }

        Map<String, OrgUnit> unitByName = new HashMap<>();
        for (OrgUnit unit : orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getDeleted, 0L))) {
            unitByName.put(unit.getName(), unit);
        }
        Map<String, SysUser> userByPhone = new HashMap<>();
        for (SysUser user : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0L))) {
            if (StringUtils.hasText(user.getPhone())) {
                userByPhone.putIfAbsent(user.getPhone(), user);
            }
        }

        int feishuUserCount = 0;
        int matched = 0;
        int nameUpdated = 0;
        int unitUpdated = 0;
        int unmatched = 0;
        Map<Long, LinkedHashSet<Long>> syncedUnitIds = new HashMap<>();
        for (FeishuClient.ContactDepartment department : departments) {
            List<FeishuClient.ContactUser> members;
            try {
                members = feishuClient.listDepartmentUsers(department.departmentId());
            } catch (Exception e) {
                log.warn("飞书部门成员拉取失败 department={}：{}", department.name(), e.getMessage());
                continue;
            }
            for (FeishuClient.ContactUser member : members) {
                feishuUserCount++;
                if (!StringUtils.hasText(member.mobile())) {
                    // 无手机号无法与平台用户匹配（需飞书后台授予手机号权限）
                    unmatched++;
                    continue;
                }
                SysUser user = userByPhone.get(member.mobile());
                if (user == null) {
                    unmatched++;
                    continue;
                }
                matched++;
                boolean changed = false;
                if (StringUtils.hasText(member.name()) && !member.name().equals(user.getName())) {
                    user.setName(member.name());
                    nameUpdated++;
                    changed = true;
                }
                OrgUnit unit = unitByName.get(department.name());
                if (unit != null) {
                    syncedUnitIds.computeIfAbsent(user.getId(), ignored -> new LinkedHashSet<>()).add(unit.getId());
                }
                if (changed) {
                    user.setUpdatedAt(LocalDateTime.now());
                    sysUserMapper.updateById(user);
                }
            }
        }
        Map<Long, List<Long>> existingUnits = userUnitService.unitIdsOf(syncedUnitIds.keySet());
        for (Map.Entry<Long, LinkedHashSet<Long>> entry : syncedUnitIds.entrySet()) {
            // 已有关联时以平台手工配置为准，飞书逐部门同步不得覆盖多学院关系。
            if (!existingUnits.getOrDefault(entry.getKey(), List.of()).isEmpty()) {
                continue;
            }
            SysUser user = userByPhone.values().stream().filter(item -> entry.getKey().equals(item.getId()))
                    .findFirst().orElse(null);
            if (user == null) {
                continue;
            }
            LinkedHashSet<Long> initialized = new LinkedHashSet<>(entry.getValue());
            if (user.getUnitId() != null) {
                initialized.add(user.getUnitId());
            }
            Long primaryUnitId = user.getUnitId() != null && initialized.contains(user.getUnitId())
                    ? user.getUnitId() : initialized.stream().findFirst().orElse(null);
            if (!primaryUnitId.equals(user.getUnitId())) {
                user.setUnitId(primaryUnitId);
                user.setUpdatedAt(LocalDateTime.now());
                sysUserMapper.updateById(user);
            }
            userUnitService.replaceUnits(user.getId(), primaryUnitId, initialized, null);
            unitUpdated++;
        }
        FeishuDtos.SyncOrgView view = new FeishuDtos.SyncOrgView(departments.size(), feishuUserCount,
                matched, 0, nameUpdated, unitUpdated, unmatched, LocalDateTime.now());
        log.info("飞书组织同步完成：{}", view);
        return view;
    }
}
