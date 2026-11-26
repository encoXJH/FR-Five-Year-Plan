package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import edu.fzrj.metrics.common.api.FieldError;
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
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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

    private static final List<String> ROLE_ORDER = List.of("ADMIN", "LEADER", "SCHOOL_OWNER", "UNIT_OWNER", "STAFF");
    private static final Set<String> ROLES = Set.copyOf(ROLE_ORDER);
    private static final List<String> UNIT_OWNER_DIRECTORY_ROLES = List.of("UNIT_OWNER", "STAFF");
    private static final Map<String, Set<String>> CREATABLE_ROLES = Map.of(
            "ADMIN", Set.of("ADMIN", "LEADER", "SCHOOL_OWNER", "UNIT_OWNER", "STAFF"),
            "LEADER", Set.of("LEADER", "SCHOOL_OWNER", "UNIT_OWNER"),
            "SCHOOL_OWNER", Set.of("SCHOOL_OWNER", "UNIT_OWNER"),
            "UNIT_OWNER", Set.of("UNIT_OWNER", "STAFF"),
            "STAFF", Set.of());
    private static final Set<String> SORT_FIELDS = Set.of("id", "username", "name", "last_login_at");

    private final SysUserMapper sysUserMapper;
    private final UserRoleMapper userRoleMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserUnitService userUnitService;
    private final Validator validator;

    public UserAdminService(SysUserMapper sysUserMapper, UserRoleMapper userRoleMapper,
                            OrgUnitMapper orgUnitMapper, RefreshTokenMapper refreshTokenMapper,
                            PasswordEncoder passwordEncoder, UserUnitService userUnitService,
                            Validator validator) {
        this.sysUserMapper = sysUserMapper;
        this.userRoleMapper = userRoleMapper;
        this.orgUnitMapper = orgUnitMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.userUnitService = userUnitService;
        this.validator = validator;
    }

    // ---------------- 查询 ----------------

    public PageResult<SystemDtos.UserItem> page(SystemDtos.UserQuery query) {
        if (StringUtils.hasText(query.getRole()) && !ROLES.contains(query.getRole())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色取值不合法：" + query.getRole());
        }
        boolean admin = SecurityUtils.hasRole("ADMIN");
        boolean unitOwnerDirectory = SecurityUtils.hasRole("UNIT_OWNER")
                && !admin && !SecurityUtils.hasRole("LEADER");
        if (unitOwnerDirectory && StringUtils.hasText(query.getRole())
                && !UNIT_OWNER_DIRECTORY_ROLES.contains(query.getRole())) {
            return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
        }
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0L)
                .eq(!admin, SysUser::getDirectoryVisible, true)
                .eq(query.getIsEnabled() != null, SysUser::getIsEnabled, query.getIsEnabled())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(SysUser::getUsername, query.getKeyword())
                        .or().like(SysUser::getName, query.getKeyword()));
        if (query.getUnitId() != null) {
            List<Long> unitUserIds = userUnitService.userIdsOfUnit(query.getUnitId());
            if (unitUserIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
            }
            wrapper.in(SysUser::getId, unitUserIds);
        }
        if (unitOwnerDirectory || StringUtils.hasText(query.getRole())) {
            List<String> directoryRoles = unitOwnerDirectory && !StringUtils.hasText(query.getRole())
                    ? UNIT_OWNER_DIRECTORY_ROLES : List.of(query.getRole());
            List<Long> userIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                            .in(UserRole::getRole, directoryRoles)
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
        return new PageResult<>(toItems(result.getRecords(), unitOwnerDirectory), result.getTotal(),
                query.getPage(), query.getPageSize());
    }

    public PageResult<SystemDtos.UserItem> staffPage(SystemDtos.StaffQuery query) {
        LoginUser manager = requireUnitOwner();
        if (query.getUnitId() != null && !manager.getUnitIds().contains(query.getUnitId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "筛选单元超出当前负责人的学院范围");
        }
        List<Long> candidateIds = query.getUnitId() == null
                ? userUnitService.userIdsOfUnits(manager.getUnitIds())
                : userUnitService.userIdsOfUnit(query.getUnitId());
        if (candidateIds.isEmpty()) {
            return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
        }
        Map<Long, Set<String>> roleMap = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .in(UserRole::getUserId, candidateIds)
                        .select(UserRole::getUserId, UserRole::getRole))
                .stream().collect(Collectors.groupingBy(UserRole::getUserId,
                        Collectors.mapping(UserRole::getRole, Collectors.toSet())));
        List<Long> staffIds = candidateIds.stream()
                .filter(id -> Set.of("STAFF").equals(roleMap.get(id)))
                .toList();
        if (staffIds.isEmpty()) {
            return new PageResult<>(List.of(), 0L, query.getPage(), query.getPageSize());
        }
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0L)
                .eq(query.getIsEnabled() != null, SysUser::getIsEnabled, query.getIsEnabled())
                .in(SysUser::getId, staffIds)
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(SysUser::getUsername, query.getKeyword())
                        .or().like(SysUser::getName, query.getKeyword()));
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
        validateCreateRequest(request);
        LoginUser creator = SecurityUtils.getLoginUser();
        List<String> roles = validateCreationRoles(request.getRoles(), creator.getRoles());
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
        StaffCreation staff = resolveStaffCreation(roles, request.getOwnerUserId(), creator);
        UnitSelection units = resolveCreationUnits(
                roles, request.getUnitId(), request.getUnitIds(), creator, staff);

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName().trim());
        user.setPhone(StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null);
        user.setEmail(StringUtils.hasText(request.getEmail()) ? request.getEmail().trim() : null);
        user.setUnitId(units.primaryUnitId());
        user.setOwnerUserId(staff == null ? null : staff.ownerUserId());
        user.setIsEnabled(true);
        user.setLoginFailCount(0);
        user.setCreatedBy(creator.getUserId());
        user.setUpdatedBy(user.getCreatedBy());
        sysUserMapper.insert(user);
        replaceRoles(user.getId(), roles);
        userUnitService.replaceUnits(user.getId(), units.primaryUnitId(), units.unitIds(), user.getCreatedBy());
        return toItems(List.of(sysUserMapper.selectById(user.getId()))).get(0);
    }

    // ---------------- 修改 / 停用 ----------------

    @Transactional
    public SystemDtos.UserItem patch(Long userId, JsonNode body) {
        requireAdmin();
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
        boolean unitsChanged = patch.has("unitId") || patch.has("unitIds");
        List<Long> requestedUnitIds = null;
        if (unitsChanged) {
            Long previousPrimary = user.getUnitId();
            if (patch.has("unitId")) {
                Long unitId = patch.isNull("unitId") ? null : patch.longId("unitId");
                requireUnitIfPresent(unitId);
                user.setUnitId(unitId);
            }
            if (patch.has("unitIds")) {
                requestedUnitIds = parseUnitIds(patch.raw("unitIds"));
                requestedUnitIds.forEach(this::requireUnitIfPresent);
            } else {
                Long retainedPrimary = previousPrimary;
                requestedUnitIds = userUnitService.unitIdsOf(userId).stream()
                        .filter(id -> !id.equals(retainedPrimary)).toList();
            }
            if (user.getUnitId() == null) {
                user.setUnitId(requestedUnitIds.stream().findFirst().orElse(null));
            }
        }
        if (patch.has("isEnabled")) {
            user.setIsEnabled(patch.bool("isEnabled"));
            if (Boolean.FALSE.equals(user.getIsEnabled())) {
                revokeAllRefreshTokens(userId);
            }
        }
        user.setUpdatedBy(SecurityUtils.getUserId());
        if (sysUserMapper.updateById(user) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "用户已被他人修改，请刷新后重试");
        }
        if (unitsChanged) {
            userUnitService.replaceUnits(userId, user.getUnitId(), requestedUnitIds, SecurityUtils.getUserId());
        }
        return toItems(List.of(sysUserMapper.selectById(userId))).get(0);
    }

    @Transactional
    public SystemDtos.UserItem patchStaff(Long userId, JsonNode body) {
        LoginUser manager = requireUnitOwner();
        PatchHelper patch = new PatchHelper(body);
        SysUser user = requireManagedAccount(userId, manager);
        int version = patch.versionRequired();
        if (!user.getVersion().equals(version)) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + user.getVersion());
        }
        if (patch.has("name")) {
            String name = patch.text("name").trim();
            if (name.isEmpty() || name.length() > 64) {
                throw new BizException(ErrorCode.PARAM_INVALID, "姓名必填且不超过 64 字");
            }
            user.setName(name);
        }
        if (patch.has("phone")) {
            String phone = patch.isNull("phone") ? null : patch.text("phone").trim();
            if (phone != null && phone.length() > 32) {
                throw new BizException(ErrorCode.PARAM_INVALID, "手机号不超过 32 字");
            }
            user.setPhone(phone);
        }
        boolean unitChanged = patch.has("unitId");
        List<Long> retainedUnitIds = null;
        if (unitChanged) {
            if (patch.isNull("unitId")) {
                throw new BizException(ErrorCode.PARAM_INVALID, "账号主学院不能为空");
            }
            Long unitId = patch.longId("unitId");
            if (!manager.getUnitIds().contains(unitId)) {
                throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "目标单元超出当前负责人的学院范围");
            }
            requireUnitIfPresent(unitId);
            retainedUnitIds = userUnitService.unitIdsOf(userId).stream()
                    .filter(id -> !manager.getUnitIds().contains(id))
                    .toList();
            user.setUnitId(unitId);
        }
        if (patch.has("isEnabled")) {
            user.setIsEnabled(patch.bool("isEnabled"));
            if (Boolean.FALSE.equals(user.getIsEnabled())) {
                revokeAllRefreshTokens(userId);
            }
        }
        user.setUpdatedBy(manager.getUserId());
        if (sysUserMapper.updateById(user) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "用户已被他人修改，请刷新后重试");
        }
        if (unitChanged) {
            userUnitService.replaceUnits(userId, user.getUnitId(), retainedUnitIds, manager.getUserId());
        }
        return toItems(List.of(sysUserMapper.selectById(userId))).get(0);
    }

    // ---------------- 角色（敏感操作，必须 reason） ----------------

    @Transactional
    public SystemDtos.RoleChangeView updateRoles(Long userId, SystemDtos.RolesRequest request) {
        requireAdmin();
        SysUser user = requireUser(userId);
        if (!user.getVersion().equals(request.getVersion())) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + user.getVersion());
        }
        List<String> newRoles = validateRoles(request.getRoles());
        List<String> before = rolesOf(userId);
        if (newRoles.contains("STAFF") && (!before.contains("STAFF") || user.getOwnerUserId() == null)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能通过用户添加流程创建干事，不能为普通账号追加 STAFF");
        }
        replaceRoles(userId, newRoles);
        // 推进用户版本号（乐观锁语义挂在 sys_user 上）
        user.setUpdatedBy(SecurityUtils.getUserId());
        if (sysUserMapper.updateById(user) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "用户已被他人修改，请刷新后重试");
        }
        return new SystemDtos.RoleChangeView(
                toItems(List.of(sysUserMapper.selectById(userId))).get(0), before, List.copyOf(newRoles));
    }

    // ---------------- 重置密码 ----------------

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        requireAdmin();
        SysUser user = requireUser(userId);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setUpdatedBy(SecurityUtils.getUserId());
        if (sysUserMapper.updateById(user) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "用户已被他人修改，请刷新后重试");
        }
        // 重置后强制重新登录：吊销全部 refreshToken
        revokeAllRefreshTokens(userId);
    }

    @Transactional
    public void resetStaffPassword(Long userId, SystemDtos.StaffPasswordResetRequest request) {
        LoginUser manager = requireUnitOwner();
        SysUser user = requireManagedAccount(userId, manager);
        if (!user.getVersion().equals(request.getVersion())) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + user.getVersion());
        }
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        user.setUpdatedBy(manager.getUserId());
        if (sysUserMapper.updateById(user) == 0) {
            throw new BizException(ErrorCode.VERSION_CONFLICT, "用户已被他人修改，请刷新后重试");
        }
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

    void requireAdmin() {
        if (!SecurityUtils.hasRole("ADMIN")) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台管理员可以修改用户");
        }
    }

    private LoginUser requireUnitOwner() {
        LoginUser manager = SecurityUtils.getLoginUser();
        if (!manager.getRoles().contains("UNIT_OWNER")) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅学院项目负责人可以管理本学院账号");
        }
        return manager;
    }

    public SysUser requireManagedAccount(Long userId) {
        return requireManagedAccount(userId, requireUnitOwner());
    }

    private SysUser requireManagedAccount(Long userId, LoginUser manager) {
        SysUser user = requireUser(userId);
        Set<String> targetRoles = new LinkedHashSet<>(rolesOf(userId));
        boolean staff = Set.of("STAFF").equals(targetRoles);
        if (!staff && !Set.of("UNIT_OWNER").equals(targetRoles)) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "目标账号角色已变更，超出学院负责人管理范围");
        }
        // 纯 STAFF 归属本学院任意负责人即可管理；UNIT_OWNER 仍限本人创建
        if (!staff && !manager.getUserId().equals(user.getCreatedBy())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "学院负责人账号只能管理本人创建的");
        }
        Set<Long> managerUnitIds = new LinkedHashSet<>(manager.getUnitIds());
        boolean inScope = userUnitService.unitIdsOf(userId).stream().anyMatch(managerUnitIds::contains);
        if (!inScope) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "目标用户超出当前负责人的学院范围");
        }
        return user;
    }

    private void validateCreateRequest(SystemDtos.UserCreateRequest request) {
        Set<ConstraintViolation<SystemDtos.UserCreateRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return;
        }
        List<FieldError> errors = violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        throw new BizException(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.getMessage(), errors);
    }

    List<String> creatableRoles(List<String> creatorRoles) {
        return ROLE_ORDER.stream()
                .filter(role -> creatorRoles.stream().anyMatch(creatorRole ->
                        CREATABLE_ROLES.getOrDefault(creatorRole, Set.of()).contains(role)))
                .toList();
    }

    List<String> validateCreationRoles(List<String> roles, List<String> creatorRoles) {
        List<String> distinct = validateRoles(roles);
        validateCreatableRoles(distinct, creatorRoles);
        return distinct;
    }

    private StaffCreation resolveStaffCreation(List<String> roles, Long requestedOwnerUserId,
                                               LoginUser creator) {
        if (!roles.contains("STAFF")) {
            if (requestedOwnerUserId != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "仅管理员创建干事时可以指定归属负责人");
            }
            return null;
        }
        if (!creator.getRoles().contains("ADMIN")) {
            if (requestedOwnerUserId != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "学院负责人创建干事时无需指定归属负责人");
            }
            return new StaffCreation(creator.getUserId(), null);
        }
        if (requestedOwnerUserId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "管理员创建干事时必须选择归属负责人");
        }
        SysUser owner = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, requestedOwnerUserId)
                .eq(SysUser::getDeleted, 0L));
        if (owner == null || !Boolean.TRUE.equals(owner.getIsEnabled())
                || !rolesOf(owner.getId()).contains("UNIT_OWNER")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "归属负责人不存在、未启用或不具备学院项目负责人角色");
        }
        List<Long> inherited = List.copyOf(new LinkedHashSet<>(userUnitService.unitIdsOf(owner.getId())));
        if (inherited.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "归属负责人未配置学院，不能创建干事");
        }
        Long primary = owner.getUnitId() != null && inherited.contains(owner.getUnitId())
                ? owner.getUnitId() : inherited.get(0);
        return new StaffCreation(owner.getId(), new UnitSelection(primary, inherited));
    }

    UnitSelection resolveCreationUnits(List<String> roles, Long requestedPrimaryUnitId,
                                       List<Long> requestedUnitIds, LoginUser creator) {
        return resolveCreationUnits(roles, requestedPrimaryUnitId, requestedUnitIds, creator, null);
    }

    private UnitSelection resolveCreationUnits(List<String> roles, Long requestedPrimaryUnitId,
                                               List<Long> requestedUnitIds, LoginUser creator,
                                               StaffCreation staff) {
        List<Long> requested = requestedUnitIds == null ? List.of() : requestedUnitIds;
        if (roles.contains("STAFF")) {
            if (requestedPrimaryUnitId != null || !requested.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "创建干事时单元必须留空，由服务端继承归属负责人全部学院");
            }
            if (staff != null && staff.units() != null) {
                return staff.units();
            }
            List<Long> inherited = List.copyOf(new LinkedHashSet<>(creator.getUnitIds()));
            if (inherited.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "当前创建人未配置学院，不能创建干事");
            }
            Long primary = creator.getUnitId() != null && inherited.contains(creator.getUnitId())
                    ? creator.getUnitId() : inherited.get(0);
            return new UnitSelection(primary, inherited);
        }

        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        if (requestedPrimaryUnitId != null) {
            normalized.add(requestedPrimaryUnitId);
        }
        requested.stream().filter(Objects::nonNull).forEach(normalized::add);
        Long primary = requestedPrimaryUnitId != null ? requestedPrimaryUnitId
                : normalized.stream().findFirst().orElse(null);
        List<Long> unitIds = List.copyOf(normalized);
        unitIds.forEach(this::requireUnitIfPresent);
        validateCreatedUnits(roles, primary, unitIds, creator);
        return new UnitSelection(primary, unitIds);
    }

    record UnitSelection(Long primaryUnitId, List<Long> unitIds) {
    }

    private record StaffCreation(Long ownerUserId, UnitSelection units) {
    }

    private List<String> validateRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少指定一个角色");
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(roles));
        for (String role : distinct) {
            if (!ROLES.contains(role)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "角色取值不合法：" + role);
            }
        }
        return distinct;
    }

    private void validateCreatableRoles(List<String> roles, List<String> creatorRoles) {
        for (String role : roles) {
            boolean allowed = creatorRoles.stream()
                    .anyMatch(creatorRole -> CREATABLE_ROLES.getOrDefault(creatorRole, Set.of()).contains(role));
            if (!allowed) {
                throw new BizException(ErrorCode.FORBIDDEN, "当前角色不能创建角色：" + role);
            }
        }
        if (roles.contains("STAFF") && roles.size() != 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "创建干事时角色只能为 STAFF");
        }
    }

    private void validateCreatedUnits(List<String> roles, Long primaryUnitId, List<Long> requestedUnitIds,
                                      LoginUser creator) {
        if (!creator.getRoles().contains("UNIT_OWNER")
                || (!roles.contains("STAFF") && !roles.contains("UNIT_OWNER"))) {
            return;
        }
        Set<Long> targetUnitIds = new LinkedHashSet<>();
        if (primaryUnitId != null) {
            targetUnitIds.add(primaryUnitId);
        }
        requestedUnitIds.stream().filter(Objects::nonNull).forEach(targetUnitIds::add);
        if (roles.contains("UNIT_OWNER") && targetUnitIds.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "学院负责人创建学院负责人时至少指定一个所属单元");
        }
        if (!new LinkedHashSet<>(creator.getUnitIds()).containsAll(targetUnitIds)) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED, "目标单元超出当前创建人的学院范围");
        }
    }

    List<String> rolesOf(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .orderByAsc(UserRole::getRole))
                .stream().map(UserRole::getRole).toList();
    }

    void replaceRoles(Long userId, List<String> roles) {
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

    void revokeAllRefreshTokens(Long userId) {
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

    private List<Long> parseUnitIds(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "字段 unitIds 应为 ID 数组");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode value : node) {
            try {
                if (value.isTextual() || value.isIntegralNumber()) {
                    ids.add(Long.valueOf(value.asText()));
                } else {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "字段 unitIds 应为 ID 数组");
            }
        }
        return List.copyOf(ids);
    }

    List<SystemDtos.UserItem> toItems(List<SysUser> users) {
        return toItems(users, false);
    }

    private List<SystemDtos.UserItem> toItems(List<SysUser> users, boolean unitOwnerDirectory) {
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> unitIdsByUser = userUnitService.unitIdsOf(
                users.stream().map(SysUser::getId).toList());
        Map<Long, OrgUnit> unitMap = unitMapOf(users, unitIdsByUser);
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
                        roleMap.getOrDefault(u.getId(), List.of()).stream()
                                .filter(role -> !unitOwnerDirectory || UNIT_OWNER_DIRECTORY_ROLES.contains(role))
                                .toList(),
                        u.getIsEnabled(), u.getLastLoginAt(), u.getVersion(),
                        unitIdsByUser.getOrDefault(u.getId(), List.of()), u.getOwnerUserId(), u.getCreatedBy(),
                        manageableBy(SecurityUtils.getLoginUserOrNull(), u,
                                roleMap.getOrDefault(u.getId(), List.of()),
                                unitIdsByUser.getOrDefault(u.getId(), List.of()))))
                .toList();
    }

    private boolean manageableBy(LoginUser manager, SysUser user, List<String> roles, List<Long> unitIds) {
        if (manager == null || !manager.getRoles().contains("UNIT_OWNER")) {
            return false;
        }
        Set<String> targetRoles = new LinkedHashSet<>(roles);
        boolean staff = Set.of("STAFF").equals(targetRoles);
        if (!staff && !Set.of("UNIT_OWNER").equals(targetRoles)) {
            return false;
        }
        // 纯 STAFF 归属本学院任意负责人即可管理；UNIT_OWNER 仍限本人创建
        if (!staff && !manager.getUserId().equals(user.getCreatedBy())) {
            return false;
        }
        return unitIds.stream().anyMatch(manager.getUnitIds()::contains);
    }

    private Map<Long, OrgUnit> unitMapOf(List<SysUser> users, Map<Long, List<Long>> unitIdsByUser) {
        List<Long> unitIds = java.util.stream.Stream.concat(
                        users.stream().map(SysUser::getUnitId),
                        unitIdsByUser.values().stream().flatMap(List::stream))
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
