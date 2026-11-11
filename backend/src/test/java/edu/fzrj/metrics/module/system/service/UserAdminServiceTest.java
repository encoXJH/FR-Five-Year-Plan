package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.api.PageResult;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.OrgUnit;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.OrgUnitMapper;
import edu.fzrj.metrics.module.system.mapper.RefreshTokenMapper;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
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
    @InjectMocks private UserAdminService userAdminService;

    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, UserRole.class);
        TableInfoHelper.initTableInfo(assistant, OrgUnit.class);
    }

    @AfterEach
    void tearDown() {
        if (security != null) security.close();
    }

    @Test
    void 创建多学院用户时维护关联集合并返回完整学院() {
        mockOperator();
        AtomicReference<SysUser> stored = new AtomicReference<>();
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(orgUnitMapper.selectCount(any())).thenReturn(1L);
        when(passwordEncoder.encode("password")).thenReturn("hash");
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(1L);
            user.setVersion(1);
            stored.set(user);
            return 1;
        }).when(sysUserMapper).insert(any(SysUser.class));
        when(sysUserMapper.selectById(1L)).thenAnswer(invocation -> stored.get());
        when(userUnitService.unitIdsOf(anyCollection()))
                .thenReturn(Map.of(1L, List.of(103L, 104L)));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role(1L, "UNIT_OWNER")));
        when(orgUnitMapper.selectList(any())).thenReturn(List.of(
                unit(103L, "数字影视传媒产业学院"), unit(104L, "数据产业学院")));
        SystemDtos.UserCreateRequest request = new SystemDtos.UserCreateRequest();
        request.setUsername("wufang");
        request.setPassword("password");
        request.setName("吴昉");
        request.setUnitIds(List.of(103L, 104L));
        request.setRoles(List.of("UNIT_OWNER"));

        SystemDtos.UserItem result = userAdminService.create(request);

        assertEquals(103L, result.unitId());
        assertEquals(List.of(103L, 104L), result.unitIds());
        verify(userUnitService).replaceUnits(1L, 103L, List.of(103L, 104L), 99L);
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
    void 角色替换后用户版本更新冲突时抛出10005() {
        security = Mockito.mockStatic(SecurityUtils.class);
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

    private void mockOperator() {
        security = Mockito.mockStatic(SecurityUtils.class);
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
