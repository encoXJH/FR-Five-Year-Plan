package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
    @InjectMocks private UserAdminService userAdminService;

    private MockedStatic<SecurityUtils> security;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, UserRole.class);
    }

    @AfterEach
    void tearDown() {
        if (security != null) security.close();
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
}
