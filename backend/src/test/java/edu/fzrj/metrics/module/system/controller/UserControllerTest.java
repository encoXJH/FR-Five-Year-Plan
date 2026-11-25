package edu.fzrj.metrics.module.system.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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

    private UserController controller(SysUserMapper mapper) {
        return new UserController(mapper, Mockito.mock(UserAdminService.class),
                Mockito.mock(UserDeletionService.class), Mockito.mock(UserImportService.class));
    }
}
