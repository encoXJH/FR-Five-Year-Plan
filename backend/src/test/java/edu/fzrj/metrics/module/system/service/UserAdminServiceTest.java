package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.exception.BizException;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock private SysUserMapper sysUserMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private OrgUnitMapper orgUnitMapper;
    @Mock private RefreshTokenMapper refreshTokenMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserUnitService userUnitService;
    @Mock private Validator validator;
    @InjectMocks private UserAdminService userAdminService;

    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, UserRole.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
        TableInfoHelper.initTableInfo(assistant, RefreshToken.class);
    }

    @AfterEach
    void tearDown() {
        if (security != null) security.close();
    }

    @Test
    void 创建多学院用户时维护关联集合并返回完整学院() {
        mockCreator("ADMIN", List.of());
        mockSuccessfulCreate("UNIT_OWNER", List.of(103L, 104L));
        SystemDtos.UserCreateRequest request = createRequest("UNIT_OWNER", List.of(103L, 104L));

        SystemDtos.UserItem result = userAdminService.create(request);

        assertEquals(103L, result.unitId());
        assertEquals(List.of(103L, 104L), result.unitIds());
        verify(userUnitService).replaceUnits(1L, 103L, List.of(103L, 104L), 99L);
    }

    @ParameterizedTest
    @MethodSource("allowedCreationMatrix")
    void 创建矩阵逐角色校验允许项(String creatorRole, String targetRole) {
        List<Long> creatorUnitIds = "UNIT_OWNER".equals(creatorRole) ? List.of(101L) : List.of();
        List<Long> requestUnitIds = "STAFF".equals(targetRole) ? List.of() : creatorUnitIds;
        mockCreator(creatorRole, creatorUnitIds);
        mockSuccessfulCreate(targetRole, creatorUnitIds);

        SystemDtos.UserItem result = userAdminService.create(createRequest(targetRole, requestUnitIds));

        assertEquals(List.of(targetRole), result.roles());
        assertEquals("STAFF".equals(targetRole) ? 99L : null, result.ownerUserId());
    }

    static Stream<Arguments> allowedCreationMatrix() {
        return Stream.of(
                Arguments.of("ADMIN", "ADMIN"), Arguments.of("ADMIN", "LEADER"),
                Arguments.of("ADMIN", "SCHOOL_OWNER"), Arguments.of("ADMIN", "UNIT_OWNER"),
                Arguments.of("LEADER", "LEADER"), Arguments.of("LEADER", "SCHOOL_OWNER"),
                Arguments.of("LEADER", "UNIT_OWNER"), Arguments.of("SCHOOL_OWNER", "SCHOOL_OWNER"),
                Arguments.of("SCHOOL_OWNER", "UNIT_OWNER"), Arguments.of("UNIT_OWNER", "UNIT_OWNER"),
                Arguments.of("UNIT_OWNER", "STAFF"));
    }

    @ParameterizedTest
    @MethodSource("deniedCreationMatrix")
    void 创建矩阵逐角色拒绝越权项(String creatorRole, String targetRole) {
        mockCreator(creatorRole, List.of(101L));

        BizException error = assertThrows(BizException.class,
                () -> userAdminService.create(createRequest(targetRole, List.of(101L))));

        assertEquals(20004, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    static Stream<Arguments> deniedCreationMatrix() {
        return Stream.of(
                Arguments.of("ADMIN", "STAFF"), Arguments.of("LEADER", "ADMIN"),
                Arguments.of("LEADER", "STAFF"), Arguments.of("SCHOOL_OWNER", "ADMIN"),
                Arguments.of("SCHOOL_OWNER", "LEADER"), Arguments.of("SCHOOL_OWNER", "STAFF"),
                Arguments.of("UNIT_OWNER", "ADMIN"), Arguments.of("UNIT_OWNER", "LEADER"),
                Arguments.of("UNIT_OWNER", "SCHOOL_OWNER"), Arguments.of("STAFF", "STAFF"),
                Arguments.of("STAFF", "UNIT_OWNER"));
    }

    @Test
    void 创建干事不能同时请求其他角色() {
        mockCreator("UNIT_OWNER", List.of(101L));
        SystemDtos.UserCreateRequest request = createRequest("STAFF", List.of(101L));
        request.setRoles(List.of("UNIT_OWNER", "STAFF"));

        BizException error = assertThrows(BizException.class, () -> userAdminService.create(request));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void 创建干事单元留空并继承创建人全部学院() {
        mockCreator("UNIT_OWNER", List.of(101L, 102L));
        mockSuccessfulCreate("STAFF", List.of(101L, 102L));

        SystemDtos.UserItem result = userAdminService.create(createRequest("STAFF", List.of()));

        assertEquals(101L, result.unitId());
        assertEquals(List.of(101L, 102L), result.unitIds());
        assertEquals(99L, result.ownerUserId());
        verify(userUnitService).replaceUnits(1L, 101L, List.of(101L, 102L), 99L);
    }

    @Test
    void 创建干事显式指定单元会被拒绝() {
        mockCreator("UNIT_OWNER", List.of(101L, 102L));
        when(sysUserMapper.selectCount(any())).thenReturn(0L);

        BizException error = assertThrows(BizException.class,
                () -> userAdminService.create(createRequest("STAFF", List.of(101L))));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void 内部调用创建方法仍显式执行DTO校验() {
        SystemDtos.UserCreateRequest request = new SystemDtos.UserCreateRequest();
        @SuppressWarnings("unchecked")
        ConstraintViolation<SystemDtos.UserCreateRequest> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("name");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("姓名必填");
        when(validator.validate(request)).thenReturn(Set.of(violation));

        BizException error = assertThrows(BizException.class, () -> userAdminService.create(request));

        assertEquals(10001, error.getErrorCode().getCode());
        assertEquals("name", error.getErrors().get(0).getField());
        verify(validator).validate(request);
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void 学院负责人创建学院负责人必须指定本人学院() {
        mockCreator("UNIT_OWNER", List.of(101L));
        when(sysUserMapper.selectCount(any())).thenReturn(0L);

        BizException error = assertThrows(BizException.class,
                () -> userAdminService.create(createRequest("UNIT_OWNER", List.of())));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNIT_OWNER"})
    void 学院负责人不能在其他学院创建负责人(String targetRole) {
        mockCreator("UNIT_OWNER", List.of(101L));
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(orgUnitMapper.selectCount(any())).thenReturn(1L);

        BizException error = assertThrows(BizException.class,
                () -> userAdminService.create(createRequest(targetRole, List.of(102L))));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void 仅更新主学院时保留第二关联学院() throws Exception {
        mockOperator();
        SysUser user = user(1L, 101L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(sysUserMapper.updateById(user)).thenReturn(1);
        when(orgUnitMapper.selectCount(any())).thenReturn(1L);
        when(userUnitService.unitIdsOf(1L)).thenReturn(List.of(101L, 104L));
        when(userUnitService.unitIdsOf(anyCollection()))
                .thenReturn(Map.of(1L, List.of(103L, 104L)));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(103L, "数字影视传媒产业学院"), unit(104L, "数据产业学院")));

        SystemDtos.UserItem result = userAdminService.patch(1L,
                new ObjectMapper().readTree("{\"unitId\":\"103\",\"version\":1}"));

        assertEquals(103L, result.unitId());
        assertEquals(List.of(103L, 104L), result.unitIds());
        verify(userUnitService).replaceUnits(1L, 103L, List.of(104L), 99L);
    }

    @Test
    void 按第二关联学院筛选可查询到用户() {
        when(userUnitService.userIdsOfUnit(104L)).thenReturn(List.of(1L));
        SysUser user = user(1L, 103L);
        doAnswer(invocation -> {
            Page<SysUser> page = invocation.getArgument(0);
            page.setRecords(List.of(user));
            page.setTotal(1);
            return page;
        }).when(sysUserMapper).selectPage(any(), any());
        when(userUnitService.unitIdsOf(anyCollection()))
                .thenReturn(Map.of(1L, List.of(103L, 104L)));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role(1L, "UNIT_OWNER")));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(103L, "数字影视传媒产业学院"), unit(104L, "数据产业学院")));
        SystemDtos.UserQuery query = new SystemDtos.UserQuery();
        query.setUnitId(104L);

        PageResult<SystemDtos.UserItem> result = userAdminService.page(query);

        assertEquals(1, result.getTotal());
        assertEquals(List.of(103L, 104L), result.getItems().get(0).unitIds());
        verify(userUnitService).userIdsOfUnit(104L);
    }

    @Test
    void 非管理员人员目录强制排除目录隐藏账号() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
        doAnswer(invocation -> {
            Page<SysUser> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        }).when(sysUserMapper).selectPage(any(), any());

        userAdminService.page(new SystemDtos.UserQuery());

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sysUserMapper).selectPage(any(), captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("directory_visible"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(true));
    }

    @Test
    void 管理员人员目录不附加目录可见性限制() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
        doAnswer(invocation -> {
            Page<SysUser> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        }).when(sysUserMapper).selectPage(any(), any());

        userAdminService.page(new SystemDtos.UserQuery());

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sysUserMapper).selectPage(any(), captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("directory_visible"));
    }

    @Test
    void 学院负责人目录仅包含学院项目负责人和干事并过滤展示角色() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(() -> SecurityUtils.hasRole("UNIT_OWNER")).thenReturn(true);
        security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
        security.when(() -> SecurityUtils.hasRole("LEADER")).thenReturn(false);
        List<UserRole> candidateRoles = List.of(role(1L, "UNIT_OWNER"), role(2L, "STAFF"));
        List<UserRole> itemRoles = List.of(
                role(1L, "ADMIN"), role(1L, "UNIT_OWNER"),
                role(2L, "LEADER"), role(2L, "STAFF"));
        when(userRoleMapper.selectList(any())).thenReturn(candidateRoles, itemRoles);
        doAnswer(invocation -> {
            Page<SysUser> page = invocation.getArgument(0);
            page.setRecords(List.of(user(1L, 103L), user(2L, 104L)));
            page.setTotal(2);
            return page;
        }).when(sysUserMapper).selectPage(any(), any());

        PageResult<SystemDtos.UserItem> result = userAdminService.page(new SystemDtos.UserQuery());

        assertEquals(2, result.getTotal());
        assertEquals(List.of("UNIT_OWNER"), result.getItems().get(0).roles());
        assertEquals(List.of("STAFF"), result.getItems().get(1).roles());
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userRoleMapper, Mockito.times(2)).selectList(captor.capture());
        assertTrue(captor.getAllValues().get(0).getSqlSegment().contains("IN"));
        assertTrue(captor.getAllValues().get(0).getParamNameValuePairs().containsValue("UNIT_OWNER"));
        assertTrue(captor.getAllValues().get(0).getParamNameValuePairs().containsValue("STAFF"));
    }

    @Test
    void 学院负责人按其他角色筛选时返回空结果() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(() -> SecurityUtils.hasRole("UNIT_OWNER")).thenReturn(true);
        security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
        security.when(() -> SecurityUtils.hasRole("LEADER")).thenReturn(false);
        SystemDtos.UserQuery query = new SystemDtos.UserQuery();
        query.setRole("ADMIN");

        PageResult<SystemDtos.UserItem> result = userAdminService.page(query);

        assertEquals(0, result.getTotal());
        assertTrue(result.getItems().isEmpty());
        verify(sysUserMapper, never()).selectPage(any(), any());
        verify(userRoleMapper, never()).selectList(any());
    }

    @Test
    void 校级负责人只能查询不能修改用户() throws Exception {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);

        BizException patchError = assertThrows(BizException.class, () -> userAdminService.patch(
                1L, new ObjectMapper().readTree("{\"version\":1}")));
        BizException rolesError = assertThrows(BizException.class, () -> userAdminService.updateRoles(
                1L, rolesRequest(List.of("LEADER"), 1)));
        BizException resetError = assertThrows(BizException.class,
                () -> userAdminService.resetPassword(1L, "newPassword"));

        assertEquals(20004, patchError.getErrorCode().getCode());
        assertEquals(20004, rolesError.getErrorCode().getCode());
        assertEquals(20004, resetError.getErrorCode().getCode());
        verify(sysUserMapper, never()).selectOne(any());
    }

    @Test
    void 普通账号不能通过角色替换追加干事() {
        mockOperator();
        SysUser user = user(1L, 101L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role(1L, "UNIT_OWNER")));
        SystemDtos.RolesRequest request = rolesRequest(List.of("UNIT_OWNER", "STAFF"), 1);

        BizException error = assertThrows(BizException.class, () -> userAdminService.updateRoles(1L, request));

        assertEquals(20004, error.getErrorCode().getCode());
        verify(userRoleMapper, never()).delete(any());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void 合法干事可以保留角色并调整其他角色且归属不变() {
        mockOperator();
        SysUser user = user(1L, null);
        user.setOwnerUserId(88L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(
                List.of(role(1L, "STAFF")), List.of(role(1L, "STAFF"), role(1L, "UNIT_OWNER")));
        when(sysUserMapper.updateById(user)).thenReturn(1);
        when(userUnitService.unitIdsOf(anyCollection())).thenReturn(Map.of());

        SystemDtos.RoleChangeView result = userAdminService.updateRoles(
                1L, rolesRequest(List.of("STAFF", "UNIT_OWNER"), 1));

        assertEquals(88L, result.user().ownerUserId());
        assertEquals(88L, user.getOwnerUserId());
        assertEquals(List.of("STAFF", "UNIT_OWNER"), result.auditAfter());
    }

    @Test
    void 角色替换后用户版本更新冲突时抛出10005() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("owner");
        user.setName("负责人");
        user.setVersion(3);
        user.setDeleted(0L);
        UserRole oldRole = new UserRole();
        oldRole.setUserId(1L);
        oldRole.setRole("SCHOOL_OWNER");
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(oldRole));
        when(sysUserMapper.updateById(any(SysUser.class))).thenReturn(0);
        SystemDtos.RolesRequest request = new SystemDtos.RolesRequest();
        request.setRoles(List.of("LEADER"));
        request.setReason("岗位调整");
        request.setVersion(3);

        BizException error = assertThrows(BizException.class, () -> userAdminService.updateRoles(1L, request));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(userRoleMapper).delete(any());
        verify(userRoleMapper).insert(any(UserRole.class));
        verify(sysUserMapper, never()).selectById(any());
    }

    @Test
    void 干事列表跨创建人且排除多角色账号() {
        mockCreator("UNIT_OWNER", List.of(101L, 102L));
        when(userUnitService.userIdsOfUnits(List.of(101L, 102L))).thenReturn(List.of(1L, 2L, 3L));
        List<UserRole> candidateRoles = List.of(
                role(1L, "STAFF"), role(2L, "STAFF"),
                role(3L, "STAFF"), role(3L, "UNIT_OWNER"));
        List<UserRole> itemRoles = List.of(role(1L, "STAFF"), role(2L, "STAFF"));
        when(userRoleMapper.selectList(any())).thenReturn(candidateRoles, itemRoles);
        SysUser first = user(1L, 101L);
        first.setOwnerUserId(88L);
        SysUser second = user(2L, 102L);
        second.setOwnerUserId(77L);
        doAnswer(invocation -> {
            Page<SysUser> page = invocation.getArgument(0);
            page.setRecords(List.of(first, second));
            page.setTotal(2);
            return page;
        }).when(sysUserMapper).selectPage(any(), any());
        when(userUnitService.unitIdsOf(anyCollection())).thenReturn(Map.of(
                1L, List.of(101L, 999L), 2L, List.of(102L)));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(101L, "学院101"), unit(102L, "学院102"), unit(999L, "学院999")));

        PageResult<SystemDtos.UserItem> result = userAdminService.staffPage(new SystemDtos.StaffQuery());

        assertEquals(2, result.getTotal());
        assertEquals(88L, result.getItems().get(0).ownerUserId());
        assertEquals(77L, result.getItems().get(1).ownerUserId());
        ArgumentCaptor<LambdaQueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sysUserMapper).selectPage(any(), wrapperCaptor.capture());
        wrapperCaptor.getValue().getSqlSegment();
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(1L));
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(2L));
        assertFalse(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(3L));
    }

    @Test
    void 干事列表按负责人学院筛选并拒绝外院筛选() {
        mockCreator("UNIT_OWNER", List.of(101L, 102L));
        when(userUnitService.userIdsOfUnit(102L)).thenReturn(List.of());
        SystemDtos.StaffQuery query = new SystemDtos.StaffQuery();
        query.setUnitId(102L);

        PageResult<SystemDtos.UserItem> result = userAdminService.staffPage(query);
        query.setUnitId(999L);
        BizException error = assertThrows(BizException.class, () -> userAdminService.staffPage(query));

        assertEquals(0, result.getTotal());
        assertEquals(20005, error.getErrorCode().getCode());
        verify(userUnitService).userIdsOfUnit(102L);
        verify(userUnitService, never()).userIdsOfUnits(anyCollection());
    }

    @Test
    void 非学院负责人即使拥有创建权限也不能调用干事服务() {
        mockCreator("ADMIN", List.of(101L));

        BizException error = assertThrows(BizException.class,
                () -> userAdminService.staffPage(new SystemDtos.StaffQuery()));

        assertEquals(20004, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).selectPage(any(), any());
    }

    @ParameterizedTest
    @MethodSource("unmanagedStaffCases")
    void 逐ID拒绝多角色外院及无学院关系账号(List<UserRole> roles, List<Long> unitIds) throws Exception {
        mockCreator("UNIT_OWNER", List.of(101L));
        SysUser user = user(1L, 101L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(roles);
        lenient().when(userUnitService.unitIdsOf(1L)).thenReturn(unitIds);

        BizException error = assertThrows(BizException.class, () -> userAdminService.patchStaff(1L,
                new ObjectMapper().readTree("{\"name\":\"新姓名\",\"version\":1}")));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    static Stream<Arguments> unmanagedStaffCases() {
        return Stream.of(
                Arguments.of(List.of(role(1L, "STAFF"), role(1L, "UNIT_OWNER")), List.of(101L)),
                Arguments.of(List.of(role(1L, "STAFF")), List.of(102L)),
                Arguments.of(List.of(role(1L, "STAFF")), List.of()));
    }

    @Test
    void 干事编辑跨创建人切换可管关系并保留外院关系() throws Exception {
        mockCreator("UNIT_OWNER", List.of(101L, 102L, 103L));
        SysUser user = mockManagedStaff(1L, List.of(101L, 103L, 999L));
        when(orgUnitMapper.selectCount(any())).thenReturn(1L);
        when(sysUserMapper.updateById(user)).thenReturn(1);
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(userUnitService.unitIdsOf(anyCollection())).thenReturn(Map.of(1L, List.of(102L, 999L)));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(102L, "学院102"), unit(999L, "学院999")));

        SystemDtos.UserItem result = userAdminService.patchStaff(1L, new ObjectMapper().readTree(
                "{\"name\":\"新姓名\",\"phone\":\"13812345678\",\"unitId\":\"102\","
                        + "\"isEnabled\":false,\"version\":1}"));

        assertEquals("新姓名", result.name());
        assertEquals("138****5678", result.phone());
        assertEquals(List.of(102L, 999L), result.unitIds());
        assertEquals(88L, user.getOwnerUserId());
        assertEquals(77L, user.getCreatedBy());
        verify(userUnitService).replaceUnits(1L, 102L, List.of(999L), 99L);
        verify(userRoleMapper, never()).delete(any());
        verify(refreshTokenMapper).update(isNull(), any());
    }

    @Test
    void 干事编辑版本冲突返回10005() throws Exception {
        mockCreator("UNIT_OWNER", List.of(101L));
        SysUser user = mockManagedStaff(1L, List.of(101L));
        user.setVersion(2);

        BizException error = assertThrows(BizException.class, () -> userAdminService.patchStaff(1L,
                new ObjectMapper().readTree("{\"name\":\"不会写入\",\"version\":1}")));

        assertEquals(10005, error.getErrorCode().getCode());
        assertEquals("吴昉", user.getName());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void 干事不能移动到负责人范围外学院() throws Exception {
        mockCreator("UNIT_OWNER", List.of(101L));
        mockManagedStaff(1L, List.of(101L));

        BizException error = assertThrows(BizException.class, () -> userAdminService.patchStaff(1L,
                new ObjectMapper().readTree("{\"unitId\":\"999\",\"version\":1}")));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verify(userUnitService, never()).replaceUnits(any(), any(), anyCollection(), any());
    }

    @Test
    void 干事密码重置版本冲突返回10005() {
        mockCreator("UNIT_OWNER", List.of(101L));
        SysUser user = mockManagedStaff(1L, List.of(101L));
        user.setVersion(2);

        BizException error = assertThrows(BizException.class,
                () -> userAdminService.resetStaffPassword(1L, staffPasswordRequest(1)));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verify(refreshTokenMapper, never()).update(isNull(), any());
    }

    @Test
    void 干事密码重置逐ID拒绝外院账号() {
        mockCreator("UNIT_OWNER", List.of(101L));
        SysUser user = user(1L, 102L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role(1L, "STAFF")));
        when(userUnitService.unitIdsOf(1L)).thenReturn(List.of(102L));

        BizException error = assertThrows(BizException.class,
                () -> userAdminService.resetStaffPassword(1L, staffPasswordRequest(1)));

        assertEquals(20005, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
        verify(refreshTokenMapper, never()).update(isNull(), any());
    }

    @Test
    void 干事密码重置清除锁定并吊销令牌() {
        mockCreator("UNIT_OWNER", List.of(101L));
        SysUser user = mockManagedStaff(1L, List.of(101L));
        user.setLoginFailCount(4);
        user.setLockedUntil(LocalDateTime.now().plusDays(1));
        when(passwordEncoder.encode("newPassword")).thenReturn("newHash");
        when(sysUserMapper.updateById(user)).thenReturn(1);

        userAdminService.resetStaffPassword(1L, staffPasswordRequest(1));

        assertEquals("newHash", user.getPasswordHash());
        assertEquals(0, user.getLoginFailCount());
        assertNull(user.getLockedUntil());
        assertEquals(88L, user.getOwnerUserId());
        assertEquals(77L, user.getCreatedBy());
        verify(refreshTokenMapper).update(isNull(), any());
    }

    private SysUser mockManagedStaff(Long userId, List<Long> unitIds) {
        SysUser user = user(userId, unitIds.stream().findFirst().orElse(null));
        user.setOwnerUserId(88L);
        user.setCreatedBy(77L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role(userId, "STAFF")));
        when(userUnitService.unitIdsOf(userId)).thenReturn(unitIds);
        return user;
    }

    private SystemDtos.StaffPasswordResetRequest staffPasswordRequest(int version) {
        SystemDtos.StaffPasswordResetRequest request = new SystemDtos.StaffPasswordResetRequest();
        request.setPassword("newPassword");
        request.setVersion(version);
        return request;
    }

    private void mockCreator(String role, List<Long> unitIds) {
        security = Mockito.mockStatic(SecurityUtils.class);
        Long primaryUnitId = unitIds.stream().findFirst().orElse(null);
        security.when(SecurityUtils::getLoginUser).thenReturn(new LoginUser(
                99L, "creator", "创建人", primaryUnitId, List.of(role), List.of(), unitIds));
    }

    private void mockSuccessfulCreate(String targetRole, List<Long> unitIds) {
        security.when(SecurityUtils::getUserId).thenReturn(99L);
        SysUser[] stored = new SysUser[1];
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        if (!unitIds.isEmpty()) {
            if (!"STAFF".equals(targetRole)) {
                when(orgUnitMapper.selectCount(any())).thenReturn(1L);
            }
            when(orgUnitMapper.selectList(any())).thenReturn(unitIds.stream()
                    .map(id -> unit(id, "学院" + id)).toList());
        }
        when(passwordEncoder.encode("password")).thenReturn("hash");
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(1L);
            user.setVersion(1);
            stored[0] = user;
            return 1;
        }).when(sysUserMapper).insert(any(SysUser.class));
        when(sysUserMapper.selectById(1L)).thenAnswer(invocation -> stored[0]);
        when(userUnitService.unitIdsOf(anyCollection())).thenReturn(Map.of(1L, unitIds));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role(1L, targetRole)));
    }

    private SystemDtos.UserCreateRequest createRequest(String role, List<Long> unitIds) {
        SystemDtos.UserCreateRequest request = new SystemDtos.UserCreateRequest();
        request.setUsername("wufang");
        request.setPassword("password");
        request.setName("吴昉");
        request.setUnitIds(unitIds);
        request.setRoles(List.of(role));
        return request;
    }

    private SystemDtos.RolesRequest rolesRequest(List<String> roles, int version) {
        SystemDtos.RolesRequest request = new SystemDtos.RolesRequest();
        request.setRoles(roles);
        request.setReason("岗位调整");
        request.setVersion(version);
        return request;
    }

    private void mockOperator() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
    }

    private static SysUser user(Long id, Long unitId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("wufang");
        user.setName("吴昉");
        user.setUnitId(unitId);
        user.setVersion(1);
        user.setDeleted(0L);
        user.setIsEnabled(true);
        return user;
    }

    private static UserRole role(Long userId, String role) {
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRole(role);
        return userRole;
    }

    private static OrgUnit unit(Long id, String name) {
        OrgUnit unit = new OrgUnit();
        unit.setId(id);
        unit.setName(name);
        unit.setDeleted(0L);
        return unit;
    }
}
