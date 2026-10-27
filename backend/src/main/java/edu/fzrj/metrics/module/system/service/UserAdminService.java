package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.RefreshToken;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.RefreshTokenMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户与角色管理（API 约定 15.10、PRD FR-M12-03）：用户 CRUD、停用、重置密码、设置角色。
 * 角色多选（一人可兼多角色，权限并集）；修改角色属敏感操作，必须填写 reason（API 约定第 10 章）。
 * 响应与审计中的手机号一律脱敏（API 约定 13）。
 */
@Service
public class UserAdminService {

    private static final Set<String> ROLES = Set.of("ADMIN", "LEADER", "SCHOOL_OWNER", "UNIT_OWNER");
    private static final Set<String> SORT_FIELDS = Set.of("id", "username", "name", "last_login_at");

    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(SysUserMapper sysUserMapper, UserRoleMapper userRoleMapper,
                            OrgUnitMapper orgUnitMapper, RefreshTokenMapper refreshTokenMapper,
                            PasswordEncoder passwordEncoder) {
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
    }

    // ---------------- 查询 ----------------

    public PageResult<SystemDtos.UserItem> page(SystemDtos.UserQuery query) {
        if (StringUtils.hasText(query.getRole()) && !ROLES.contains(query.getRole())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色取值不合法：" + query.getRole());
        }
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0L)
                .eq(query.getUnitId() != null, SysUser::getUnitId, query.getUnitId())
                .eq(query.getIsEnabled() != null, SysUser::getIsEnabled, query.getIsEnabled())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(SysUser::getUsername, query.getKeyword())
                        .or().like(SysUser::getName, query.getKeyword()));
        if (StringUtils.hasText(query.getRole())) {
            List<Long> userIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getRole, query.getRole())
                            .select(UserRole::getUserId))
                    .stream().map(UserRole::getUserId).toList();
            if (userIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
            }
            wrapper.in(SysUser::getId, userIds);
        }
        Page<SysUser> page = query.toPage(SORT_FIELDS);
        if (!StringUtils.hasText(query.getSort())) {
            page.addOrder(com.baomidou.mybatisplus.core.metadata.OrderItem.asc("id"));
        }
        Page<SysUser> result = sysUserMapper.selectPage(page, wrapper);
        return new PageResult<>(toItems(result.getRecords()), result.getTotal(),
                query.getPage(), query.getPageSize());
    }

    // ---------------- 新增 ----------------

    @Transactional
    public SystemDtos.UserItem create(SystemDtos.UserCreateRequest request) {
        String username = request.getUsername().trim();
        if (!username.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "登录名只允许字母、数字、下划线、点、短横线");
        }
        Long duplicate = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .eq(SysUser::getDeleted, 0L));
        if (duplicate > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "登录名已存在：" + username);
        }
        requireUnitIfPresent(request.getUnitId());
        List<String> roles = validateRoles(request.getRoles());

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName().trim());
        user.setPhone(StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null);
        user.setEmail(StringUtils.hasText(request.getEmail()) ? request.getEmail().trim() : null);
        user.setUnitId(request.getUnitId());
        user.setIsEnabled(true);
        user.setLoginFailCount(0);
        user.setCreatedBy(SecurityUtils.getUserId());
        user.setUpdatedBy(user.getCreatedBy());
        sysUserMapper.insert(user);
        replaceRoles(user.getId(), roles);
        return toItems(List.of(sysUserMapper.selectById(user.getId()))).get(0);
    }

    // ---------------- 修改 / 停用 ----------------

    @Transactional
    public SystemDtos.UserItem patch(Long userId, JsonNode body) {
        PatchHelper patch = new PatchHelper(body);
        SysUser user = requireUser(userId);
        int version = patch.versionRequired();
        if (!user.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + user.getVersion());
        }
        if (patch.has("isEnabled") && Boolean.FALSE.equals(patch.bool("isEnabled"))
                && userId.equals(SecurityUtils.getUserId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能停用当前登录的管理员账号");
        }
        if (patch.has("username") && !user.getUsername().equals(patch.text("username"))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "登录名创建后不允许修改");
        }
        if (patch.has("name")) {
            String name = patch.text("name").trim();
            if (name.isEmpty() || name.length() > 64) {
                throw new BizException(ErrorCode.PARAM_INVALID, "姓名必填且不超过 64 字");
            }
            user.setName(name);
        }
        if (patch.has("phone")) {
            user.setPhone(patch.isNull("phone") ? null : patch.text("phone").trim());
        }
        if (patch.has("email")) {
            user.setEmail(patch.isNull("email") ? null : patch.text("email").trim());
        }
        if (patch.has("unitId")) {
            Long unitId = patch.isNull("unitId") ? null : patch.longId("unitId");
            requireUnitIfPresent(unitId);
            user.setUnitId(unitId);
        }
        if (patch.has("isEnabled")) {
            user.setIsEnabled(patch.bool("isEnabled"));
            if (Boolean.FALSE.equals(user.getIsEnabled())) {
                revokeAllRefreshTokens(userId);
            }
        }
        user.setUpdatedBy(SecurityUtils.getUserId());
        sysUserMapper.updateById(user);
        return toItems(List.of(sysUserMapper.selectById(userId))).get(0);
    }

    // ---------------- 角色（敏感操作，必须 reason） ----------------

    @Transactional
    public SystemDtos.RoleChangeView updateRoles(Long userId, SystemDtos.RolesRequest request) {
        SysUser user = requireUser(userId);
        if (!user.getVersion().equals(request.getVersion())) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + user.getVersion());
        }
        List<String> newRoles = validateRoles(request.getRoles());
        List<String> before = rolesOf(userId);
        replaceRoles(userId, newRoles);
        // 推进用户版本号（乐观锁语义挂在 sys_user 上）
        user.setUpdatedBy(SecurityUtils.getUserId());
        sysUserMapper.updateById(user);
        return new SystemDtos.RoleChangeView(
                toItems(List.of(sysUserMapper.selectById(userId))).get(0), before, List.copyOf(newRoles));
    }

    // ---------------- 重置密码 ----------------

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = requireUser(userId);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setUpdatedBy(SecurityUtils.getUserId());
        sysUserMapper.updateById(user);
        // 重置后强制重新登录：吊销全部 refreshToken
        revokeAllRefreshTokens(userId);
    }

    // ---------------- 内部 ----------------

    public SysUser requireUser(Long userId) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, 0L));
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private List<String> validateRoles(List<String> roles) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(roles));
        for (String role : distinct) {
            if (!ROLES.contains(role)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "角色取值不合法：" + role);
            }
        }
        return distinct;
    }

    private List<String> rolesOf(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .orderByAsc(UserRole::getRole))
                .stream().map(UserRole::getRole).toList();
    }

    private void replaceRoles(Long userId, List<String> roles) {
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        Long operator = SecurityUtils.getUserId();
        for (String role : roles) {
            UserRole userRole = new UserRole();
            userRole.setUserId(userId);
            userRole.setRole(role);
            userRole.setCreatedBy(operator);
            userRoleMapper.insert(userRole);
        }
    }

    private void revokeAllRefreshTokens(Long userId) {
        refreshTokenMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getUserId, userId)
                .isNull(RefreshToken::getRevokedAt)
                .set(RefreshToken::getRevokedAt, LocalDateTime.now()));
    }

    private void requireUnitIfPresent(Long unitId) {
        if (unitId == null) {
            return;
        }
        Long count = orgUnitMapper.selectCount(new LambdaQueryWrapper<OrgUnit>()
                .eq(OrgUnit::getId, unitId)
                .eq(OrgUnit::getDeleted, 0L));
        if (count == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所属执行单元不存在");
        }
    }

    private List<SystemDtos.UserItem> toItems(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, OrgUnit> unitMap = unitMapOf(users);
        Map<Long, List<String>> roleMap = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .in(UserRole::getUserId, users.stream().map(SysUser::getId).toList())
                        .orderByAsc(UserRole::getRole))
                .stream().collect(Collectors.groupingBy(UserRole::getUserId,
                        Collectors.mapping(UserRole::getRole, Collectors.toList())));
        return users.stream()
                .map(u -> new SystemDtos.UserItem(u.getId(), u.getUsername(), u.getName(),
                        maskPhone(u.getPhone()), u.getEmail(),
                        u.getUnitId(),
                        u.getUnitId() == null || unitMap.get(u.getUnitId()) == null
                                ? null : unitMap.get(u.getUnitId()).getName(),
                        roleMap.getOrDefault(u.getId(), List.of()),
                        u.getIsEnabled(), u.getLastLoginAt(), u.getVersion()))
                .toList();
    }

    private Map<Long, OrgUnit> unitMapOf(List<SysUser> users) {
        List<Long> unitIds = users.stream().map(SysUser::getUnitId)
                .filter(Objects::nonNull).distinct().toList();
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnit>()
                        .in(OrgUnit::getId, unitIds)
                        .eq(OrgUnit::getDeleted, 0L))
                .stream().collect(Collectors.toMap(OrgUnit::getId, Function.identity()));
    }

    /** 手机号脱敏：保留前 3 后 4（与 /auth/me 一致） */
    private static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
