package edu.fzrj.metrics.module.system.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.service.UserAdminService;
import edu.fzrj.metrics.module.system.service.UserDeletionService;
import edu.fzrj.metrics.module.system.service.UserImportService;
import edu.fzrj.metrics.security.SecurityUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @BeforeAll
    static void initMybatisLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    @Test
    void 全校人员目录允许系统管理或用户创建权限只读查询() throws NoSuchMethodException {
        PreAuthorize authorization = UserController.class
                .getMethod("page", SystemDtos.UserQuery.class)
                .getAnnotation(PreAuthorize.class);

        assertEquals("hasAnyAuthority('system:manage', 'user:create')", authorization.value());
    }

    @Test
    void 非管理员人员选项排除目录隐藏账号() {
        SysUserMapper mapper = Mockito.mock(SysUserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        UserController controller = controller(mapper);
        try (MockedStatic<SecurityUtils> security = Mockito.mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(false);
            controller.options();
        }

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("directory_visible"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(true));
    }

    @Test
    void 管理员人员选项不附加目录可见性限制() {
        SysUserMapper mapper = Mockito.mock(SysUserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        UserController controller = controller(mapper);
        try (MockedStatic<SecurityUtils> security = Mockito.mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            controller.options();
        }

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("directory_visible"));
    }

    @Test
    void 创建接口绑定并透传归属负责人ID() throws Exception {
        SysUserMapper mapper = Mockito.mock(SysUserMapper.class);
        UserAdminService adminService = Mockito.mock(UserAdminService.class);
        UserController controller = new UserController(mapper, adminService,
                Mockito.mock(UserDeletionService.class), Mockito.mock(UserImportService.class));
        SystemDtos.UserCreateRequest request = new ObjectMapper().readValue("""
                {"username":"staff001","password":"password","name":"干事",
                 "roles":["STAFF"],"ownerUserId":"88"}
                """, SystemDtos.UserCreateRequest.class);

        controller.create(request);

        ArgumentCaptor<SystemDtos.UserCreateRequest> captor =
                ArgumentCaptor.forClass(SystemDtos.UserCreateRequest.class);
        verify(adminService).create(captor.capture());
        assertEquals(88L, captor.getValue().getOwnerUserId());
        assertEquals(List.of("STAFF"), captor.getValue().getRoles());
    }

    @Test
    void 专用学院账号写接口使用用户创建权限并暴露预期路由() throws Exception {
        var patch = UserController.class.getMethod("patchStaff", Long.class,
                com.fasterxml.jackson.databind.JsonNode.class);
        var reset = UserController.class.getMethod("resetStaffPassword", Long.class,
                SystemDtos.StaffPasswordResetRequest.class);
        var roles = UserController.class.getMethod("updateManagedRoles", Long.class,
                SystemDtos.RolesRequest.class);
        var delete = UserController.class.getMethod("deleteManaged", Long.class,
                SystemDtos.UserDeleteRequest.class);

        assertEquals(List.of("/staff/{userId}"), List.of(patch.getAnnotation(PatchMapping.class).value()));
        assertEquals("hasAuthority('user:create')", patch.getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAuthority('user:create')", reset.getAnnotation(PreAuthorize.class).value());
        assertEquals(List.of("/staff/{userId}/roles"), List.of(roles.getAnnotation(PutMapping.class).value()));
        assertEquals("hasAuthority('user:create')", roles.getAnnotation(PreAuthorize.class).value());
        assertEquals(List.of("/staff/{userId}"), List.of(delete.getAnnotation(DeleteMapping.class).value()));
        assertEquals("hasAuthority('user:create')", delete.getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void 专用学院账号写接口按职责分派服务并透传版本原因() throws Exception {
        UserAdminService adminService = Mockito.mock(UserAdminService.class);
        UserDeletionService deletionService = Mockito.mock(UserDeletionService.class);
        UserController controller = new UserController(Mockito.mock(SysUserMapper.class), adminService,
                deletionService, Mockito.mock(UserImportService.class));
        ObjectMapper objectMapper = new ObjectMapper();
        var patchBody = objectMapper.readTree(
                "{\"name\":\"新姓名\",\"role\":\"UNIT_OWNER\",\"reason\":\"岗位调整\",\"version\":2}");
        var passwordRequest = objectMapper.readValue(
                "{\"password\":\"newPassword\",\"version\":2}",
                SystemDtos.StaffPasswordResetRequest.class);
        var rolesRequest = objectMapper.readValue(
                "{\"roles\":[\"UNIT_OWNER\"],\"version\":2,\"reason\":\"岗位调整\"}",
                SystemDtos.RolesRequest.class);
        var deleteRequest = objectMapper.readValue(
                "{\"version\":2,\"reason\":\"人员离岗\"}", SystemDtos.UserDeleteRequest.class);

        controller.patchStaff(1L, patchBody);
        controller.resetStaffPassword(1L, passwordRequest);
        controller.updateManagedRoles(1L, rolesRequest);
        controller.deleteManaged(1L, deleteRequest);

        verify(deletionService).patchManaged(1L, patchBody);
        verify(adminService, Mockito.never()).patchStaff(any(), any());
        verify(adminService).resetStaffPassword(1L, passwordRequest);
        verify(deletionService).updateManagedRoles(1L, rolesRequest);
        verify(deletionService).deleteManaged(1L, deleteRequest);
        assertEquals(2, rolesRequest.getVersion());
        assertEquals("岗位调整", rolesRequest.getReason());
        assertEquals(2, deleteRequest.getVersion());
        assertEquals("人员离岗", deleteRequest.getReason());
    }

    @Test
    void 通用管理员写接口权限及服务路径保持不变() throws Exception {
        var patch = UserController.class.getMethod("patch", Long.class,
                com.fasterxml.jackson.databind.JsonNode.class);
        var roles = UserController.class.getMethod("updateRoles", Long.class,
                SystemDtos.RolesRequest.class);
        var delete = UserController.class.getMethod("delete", Long.class,
                SystemDtos.UserDeleteRequest.class);
        var reset = UserController.class.getMethod("resetPassword", Long.class,
                SystemDtos.PasswordResetRequest.class);
        assertEquals("hasAuthority('system:manage')", patch.getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAuthority('system:manage')", roles.getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAuthority('system:manage')", delete.getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAuthority('system:manage')", reset.getAnnotation(PreAuthorize.class).value());

        UserAdminService adminService = Mockito.mock(UserAdminService.class);
        UserDeletionService deletionService = Mockito.mock(UserDeletionService.class);
        UserController controller = new UserController(Mockito.mock(SysUserMapper.class), adminService,
                deletionService, Mockito.mock(UserImportService.class));
        SystemDtos.RolesRequest rolesRequest = new SystemDtos.RolesRequest();
        rolesRequest.setRoles(List.of("LEADER"));
        rolesRequest.setVersion(1);
        rolesRequest.setReason("岗位调整");
        SystemDtos.UserDeleteRequest deleteRequest = new SystemDtos.UserDeleteRequest();
        deleteRequest.setVersion(1);
        deleteRequest.setReason("人员离岗");

        controller.updateRoles(1L, rolesRequest);
        controller.delete(1L, deleteRequest);

        verify(adminService).updateRoles(1L, rolesRequest);
        verify(deletionService).delete(1L, deleteRequest);
    }

    private UserController controller(SysUserMapper mapper) {
        return new UserController(mapper, Mockito.mock(UserAdminService.class),
                Mockito.mock(UserDeletionService.class), Mockito.mock(UserImportService.class));
    }
}
