package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.RolePermissionDtos;
import edu.fzrj.metrics.module.system.entity.RolePermission;
import edu.fzrj.metrics.module.system.entity.RolePermissionVersion;
import edu.fzrj.metrics.module.system.mapper.RolePermissionMapper;
import edu.fzrj.metrics.module.system.mapper.RolePermissionVersionMapper;
import edu.fzrj.metrics.security.RolePermissions;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 角色功能权限配置与运行时权限解析。 */
@Service
public class RolePermissionService {

    private final RolePermissionMapper rolePermissionMapper;
    private final RolePermissionVersionMapper versionMapper;

    public RolePermissionService(RolePermissionMapper rolePermissionMapper,
                                 RolePermissionVersionMapper versionMapper) {
        this.rolePermissionMapper = rolePermissionMapper;
        this.versionMapper = versionMapper;
    }

    /** 多角色取数据库配置并集；未初始化的角色仅用于迁移前兼容，回退到默认矩阵。 */
    public List<String> configuredPermissionsOf(List<String> roles) {
        List<String> knownRoles = roles.stream()
                .filter(RolePermissions.ROLE_ORDER::contains)
                .distinct()
                .toList();
        if (knownRoles.isEmpty()) {
            return List.of();
        }
        Set<String> initialized = versionMapper.selectList(new LambdaQueryWrapper<RolePermissionVersion>()
                        .in(RolePermissionVersion::getRole, knownRoles)
                        .select(RolePermissionVersion::getRole))
                .stream().map(RolePermissionVersion::getRole).collect(Collectors.toSet());
        Map<String, Set<String>> grants = rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .in(RolePermission::getRole, knownRoles)
                        .select(RolePermission::getRole, RolePermission::getPermission))
                .stream().collect(Collectors.groupingBy(RolePermission::getRole,
                        Collectors.mapping(RolePermission::getPermission, Collectors.toSet())));
        return knownRoles.stream()
                .flatMap(role -> initialized.contains(role)
                        ? grants.getOrDefault(role, Set.of()).stream()
                        : RolePermissions.defaultPermissions(role).stream())
                .distinct()
                .sorted()
                .toList();
    }

    public RolePermissionDtos.MatrixView matrix() {
        List<RolePermissionDtos.RoleItem> roles = RolePermissions.ROLE_ORDER.stream()
                .map(this::roleItem)
                .toList();
        List<RolePermissionDtos.PermissionItem> permissions = RolePermissions.definitions().stream()
                .map(definition -> new RolePermissionDtos.PermissionItem(
                        definition.code(), definition.name(), definition.group(), definition.description(),
                        orderedRoles(definition.configurableRoles()), orderedRoles(definition.lockedRoles())))
                .toList();
        return new RolePermissionDtos.MatrixView(roles, permissions);
    }

    @Transactional
    public RolePermissionDtos.RoleItem update(String role, RolePermissionDtos.UpdateRequest request) {
        if (!RolePermissions.ROLE_ORDER.contains(role)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色取值不合法：" + role);
        }
        RolePermissionVersion currentVersion = versionMapper.lockByRole(role);
        if (currentVersion == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色权限配置不存在：" + role);
        }
        if (!currentVersion.getVersion().equals(request.getVersion())) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "角色权限已被他人修改，当前最新版本号：" + currentVersion.getVersion());
        }
        List<String> before = grantsOf(role);
        Set<String> desired = new TreeSet<>(request.getPermissions());
        validateSelection(role, desired);

        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermission>()
                .eq(RolePermission::getRole, role));
        Long operator = SecurityUtils.getUserId();
        for (String permission : desired) {
            RolePermission entity = new RolePermission();
            entity.setRole(role);
            entity.setPermission(permission);
            entity.setCreatedBy(operator);
            rolePermissionMapper.insert(entity);
        }
        if (versionMapper.incrementVersion(role, request.getVersion(), operator) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "角色权限已被他人修改，请刷新后重试");
        }
        List<String> after = List.copyOf(desired);
        return new RolePermissionDtos.RoleItem(role, RolePermissions.roleLabel(role), after,
                request.getVersion() + 1).withAudit(before, after);
    }

    private RolePermissionDtos.RoleItem roleItem(String role) {
        RolePermissionVersion version = versionMapper.selectById(role);
        List<String> permissions = version == null
                ? RolePermissions.permissionsOf(List.of(role))
                : grantsOf(role);
        return new RolePermissionDtos.RoleItem(role, RolePermissions.roleLabel(role), permissions,
                version == null ? 0 : version.getVersion());
    }

    private List<String> grantsOf(String role) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRole, role)
                        .select(RolePermission::getPermission)
                        .orderByAsc(RolePermission::getPermission))
                .stream().map(RolePermission::getPermission).toList();
    }

    private void validateSelection(String role, Set<String> requested) {
        Map<String, RolePermissions.PermissionDefinition> definitions = RolePermissions.definitions().stream()
                .collect(Collectors.toMap(RolePermissions.PermissionDefinition::code, Function.identity()));
        for (String permission : requested) {
            if (!definitions.containsKey(permission)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "权限码不存在：" + permission);
            }
        }
        for (RolePermissions.PermissionDefinition definition : definitions.values()) {
            boolean selected = requested.contains(definition.code());
            boolean locked = definition.lockedRoles().contains(role);
            boolean configurable = definition.configurableRoles().contains(role);
            if (locked && !selected) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        RolePermissions.roleLabel(role) + "的核心权限不能移除：" + definition.name());
            }
            if (selected && !locked && !configurable) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "权限“" + definition.name() + "”不允许配置给" + RolePermissions.roleLabel(role));
            }
        }
    }

    private List<String> orderedRoles(Set<String> roles) {
        return RolePermissions.ROLE_ORDER.stream().filter(roles::contains).toList();
    }
}
