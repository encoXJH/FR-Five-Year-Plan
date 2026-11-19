package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.RolePermissionDtos;
import edu.fzrj.metrics.module.system.entity.RolePermission;
import edu.fzrj.metrics.module.system.entity.RolePermissionVersion;
import edu.fzrj.metrics.module.system.mapper.RolePermissionMapper;
import edu.fzrj.metrics.module.system.mapper.RolePermissionVersionMapper;
import edu.fzrj.metrics.security.RolePermissions;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RolePermissionServiceTest {

    @Mock private RolePermissionMapper rolePermissionMapper;
    @Mock private RolePermissionVersionMapper versionMapper;
    @InjectMocks private RolePermissionService rolePermissionService;

    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RolePermission.class);
        TableInfoHelper.initTableInfo(assistant, RolePermissionVersion.class);
    }

    @AfterEach
    void tearDown() {
        if (security != null) security.close();
    }

    @Test
    void 运行时按数据库配置取多角色并集() {
        when(versionMapper.selectList(any())).thenReturn(List.of(version("LEADER", 1), version("UNIT_OWNER", 2)));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                grant("LEADER", RolePermissions.STATS_VIEW),
                grant("UNIT_OWNER", RolePermissions.SUBMISSION_SUBMIT),
                grant("UNIT_OWNER", RolePermissions.MATERIAL_UPLOAD)));

        List<String> permissions = rolePermissionService.configuredPermissionsOf(List.of("LEADER", "UNIT_OWNER"));

        assertEquals(List.of(RolePermissions.MATERIAL_UPLOAD, RolePermissions.STATS_VIEW,
                RolePermissions.SUBMISSION_SUBMIT), permissions);
        assertFalse(permissions.contains(RolePermissions.SCREEN_VIEW));
        assertFalse(permissions.contains(RolePermissions.ADJUSTMENT_DECIDE));
    }

    @Test
    void 未初始化配置回退默认矩阵() {
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        List<String> permissions = rolePermissionService.configuredPermissionsOf(List.of("LEADER"));

        assertEquals(RolePermissions.permissionsOf(List.of("LEADER")), permissions);
    }

    @Test
    void 校级负责人审核中心默认授权并允许管理员关闭() {
        assertTrue(RolePermissions.defaultPermissions("LEADER").contains(RolePermissions.SUBMISSION_REVIEW));
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
        when(versionMapper.lockByRole("LEADER")).thenReturn(version("LEADER", 0));
        List<String> before = RolePermissions.permissionsOf(List.of("LEADER"));
        when(rolePermissionMapper.selectList(any())).thenReturn(before.stream()
                .map(permission -> grant("LEADER", permission)).toList());
        when(versionMapper.incrementVersion("LEADER", 0, 99L)).thenReturn(1);
        List<String> desired = before.stream()
                .filter(code -> !code.equals(RolePermissions.SUBMISSION_REVIEW)).toList();

        RolePermissionDtos.RoleItem result = rolePermissionService.update("LEADER", request(desired, 0));

        assertFalse(result.permissions().contains(RolePermissions.SUBMISSION_REVIEW));
        assertTrue(result.auditBefore().contains(RolePermissions.SUBMISSION_REVIEW));
        assertEquals(1, result.version());
        verify(versionMapper).incrementVersion("LEADER", 0, 99L);
    }

    @Test
    void 干事动态继承学院负责人当前权限但排除用户创建和审核() {
        when(versionMapper.selectList(any())).thenReturn(List.of(version("UNIT_OWNER", 3)));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                grant("UNIT_OWNER", RolePermissions.USER_CREATE),
                grant("UNIT_OWNER", RolePermissions.SUBMISSION_REVIEW),
                grant("UNIT_OWNER", RolePermissions.SUBMISSION_SUBMIT)));

        List<String> permissions = rolePermissionService.configuredPermissionsOf(List.of("STAFF"));

        assertEquals(List.of(RolePermissions.SUBMISSION_SUBMIT), permissions);
    }

    @Test
    void 干事兼任学院负责人时保留学院负责人的用户创建和审核权限() {
        when(versionMapper.selectList(any())).thenReturn(List.of(version("UNIT_OWNER", 3)));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                grant("UNIT_OWNER", RolePermissions.USER_CREATE),
                grant("UNIT_OWNER", RolePermissions.SUBMISSION_REVIEW),
                grant("UNIT_OWNER", RolePermissions.SUBMISSION_SUBMIT)));

        List<String> permissions = rolePermissionService.configuredPermissionsOf(List.of("STAFF", "UNIT_OWNER"));

        assertEquals(List.of(RolePermissions.SUBMISSION_REVIEW, RolePermissions.SUBMISSION_SUBMIT,
                RolePermissions.USER_CREATE), permissions);
    }

    @Test
    void 学院负责人已初始化为空权限时干事也为空() {
        when(versionMapper.selectList(any())).thenReturn(List.of(version("UNIT_OWNER", 1)));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        List<String> permissions = rolePermissionService.configuredPermissionsOf(List.of("STAFF"));

        assertTrue(permissions.isEmpty());
    }

    @Test
    void 用户添加默认授权且三类角色允许管理员取消() {
        assertEquals(18, RolePermissions.defaultPermissions("ADMIN").size());
        assertEquals(7, RolePermissions.defaultPermissions("LEADER").size());
        assertEquals(9, RolePermissions.defaultPermissions("SCHOOL_OWNER").size());
        assertEquals(7, RolePermissions.defaultPermissions("UNIT_OWNER").size());
        RolePermissions.PermissionDefinition definition = RolePermissions.definitions().stream()
                .filter(item -> item.code().equals(RolePermissions.USER_CREATE)).findFirst().orElseThrow();
        assertEquals(Set.of("LEADER", "SCHOOL_OWNER", "UNIT_OWNER"), definition.configurableRoles());
        assertEquals(Set.of("ADMIN"), definition.lockedRoles());
        assertFalse(RolePermissions.ROLE_ORDER.contains("STAFF"));
    }

    @Test
    void 学院负责人用户添加可由管理员取消() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
        when(versionMapper.lockByRole("UNIT_OWNER")).thenReturn(version("UNIT_OWNER", 0));
        List<String> before = RolePermissions.permissionsOf(List.of("UNIT_OWNER"));
        when(rolePermissionMapper.selectList(any())).thenReturn(before.stream()
                .map(permission -> grant("UNIT_OWNER", permission)).toList());
        when(versionMapper.incrementVersion("UNIT_OWNER", 0, 99L)).thenReturn(1);
        List<String> desired = before.stream()
                .filter(code -> !code.equals(RolePermissions.USER_CREATE)).toList();

        RolePermissionDtos.RoleItem result = rolePermissionService.update("UNIT_OWNER", request(desired, 0));

        assertFalse(result.permissions().contains(RolePermissions.USER_CREATE));
        assertTrue(result.auditBefore().contains(RolePermissions.USER_CREATE));
        assertFalse(result.auditAfter().contains(RolePermissions.USER_CREATE));
        assertEquals(1, result.version());
    }

    @Test
    void 学院负责人审核核心锁定且建设方向负责人不再允许审核() {
        assertTrue(RolePermissions.defaultPermissions("UNIT_OWNER")
                .contains(RolePermissions.SUBMISSION_REVIEW));
        assertFalse(RolePermissions.defaultPermissions("SCHOOL_OWNER")
                .contains(RolePermissions.SUBMISSION_REVIEW));
        RolePermissions.PermissionDefinition definition = RolePermissions.definitions().stream()
                .filter(item -> item.code().equals(RolePermissions.SUBMISSION_REVIEW)).findFirst().orElseThrow();
        assertTrue(definition.lockedRoles().contains("UNIT_OWNER"));
        assertTrue(definition.configurableRoles().contains("LEADER"));
        assertFalse(definition.lockedRoles().contains("SCHOOL_OWNER"));
        assertFalse(definition.configurableRoles().contains("SCHOOL_OWNER"));
    }

    @Test
    void 核心权限不能移除() {
        when(versionMapper.lockByRole("UNIT_OWNER")).thenReturn(version("UNIT_OWNER", 0));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());
        RolePermissionDtos.UpdateRequest request = request(List.of(RolePermissions.SUBMISSION_SUBMIT), 0);

        BizException error = assertThrows(BizException.class,
                () -> rolePermissionService.update("UNIT_OWNER", request));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void 不能向角色授予未开放的跨角色权限() {
        when(versionMapper.lockByRole("LEADER")).thenReturn(version("LEADER", 0));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());
        RolePermissionDtos.UpdateRequest request = request(List.of(
                RolePermissions.ADJUSTMENT_DECIDE, RolePermissions.PLAN_MANAGE), 0);

        BizException error = assertThrows(BizException.class,
                () -> rolePermissionService.update("LEADER", request));

        assertEquals(10001, error.getErrorCode().getCode());
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void 版本冲突时不写入权限() {
        when(versionMapper.lockByRole("LEADER")).thenReturn(version("LEADER", 3));
        RolePermissionDtos.UpdateRequest request = request(RolePermissions.permissionsOf(List.of("LEADER")), 2);

        BizException error = assertThrows(BizException.class,
                () -> rolePermissionService.update("LEADER", request));

        assertEquals(10005, error.getErrorCode().getCode());
        verify(rolePermissionMapper, never()).delete(any());
        verify(versionMapper, never()).incrementVersion(anyString(), anyInt(), anyLong());
    }

    @Test
    void 保存可配置权限并记录前后快照() {
        security = Mockito.mockStatic(SecurityUtils.class);
        security.when(SecurityUtils::getUserId).thenReturn(99L);
        when(versionMapper.lockByRole("SCHOOL_OWNER")).thenReturn(version("SCHOOL_OWNER", 2));
        List<String> before = RolePermissions.permissionsOf(List.of("SCHOOL_OWNER"));
        when(rolePermissionMapper.selectList(any())).thenReturn(before.stream()
                .map(permission -> grant("SCHOOL_OWNER", permission)).toList());
        when(versionMapper.incrementVersion("SCHOOL_OWNER", 2, 99L)).thenReturn(1);
        List<String> desired = before.stream().filter(code -> !code.equals(RolePermissions.STATS_VIEW)).toList();

        RolePermissionDtos.RoleItem result = rolePermissionService.update("SCHOOL_OWNER", request(desired, 2));

        assertEquals(3, result.version());
        assertFalse(result.permissions().contains(RolePermissions.STATS_VIEW));
        assertTrue(result.auditBefore().contains(RolePermissions.STATS_VIEW));
        assertFalse(result.auditAfter().contains(RolePermissions.STATS_VIEW));
        verify(rolePermissionMapper, times(1)).delete(any());
        verify(rolePermissionMapper, times(desired.size())).insert(any(RolePermission.class));
        verify(versionMapper).incrementVersion("SCHOOL_OWNER", 2, 99L);
    }

    private RolePermissionDtos.UpdateRequest request(List<String> permissions, int version) {
        RolePermissionDtos.UpdateRequest request = new RolePermissionDtos.UpdateRequest();
        request.setPermissions(new ArrayList<>(permissions));
        request.setReason("按培训后的职责分工调整");
        request.setVersion(version);
        return request;
    }

    private RolePermissionVersion version(String role, int version) {
        RolePermissionVersion entity = new RolePermissionVersion();
        entity.setRole(role);
        entity.setVersion(version);
        return entity;
    }

    private RolePermission grant(String role, String permission) {
        RolePermission entity = new RolePermission();
        entity.setRole(role);
        entity.setPermission(permission);
        return entity;
    }
}
