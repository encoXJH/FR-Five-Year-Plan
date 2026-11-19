package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.common.api.UserRef;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRefResolverTest {

    @Mock private SysUserMapper sysUserMapper;
    @InjectMocks private UserRefResolver userRefResolver;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @Test
    void 展示解析保留已删除用户姓名() {
        SysUser deleted = user(7L, 7L);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(deleted));

        Map<Long, SysUser> result = userRefResolver.mapOf(List.of(7L));
        UserRef ref = userRefResolver.refOf(7L, result);

        assertEquals("历史负责人", ref.name());
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sysUserMapper).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().toUpperCase().contains("DELETED"));
    }

    @Test
    void 写校验解析仍排除已删除用户() {
        when(sysUserMapper.selectList(any())).thenReturn(List.of());

        Map<Long, SysUser> result = userRefResolver.mapActiveOf(List.of(7L));

        assertTrue(result.isEmpty());
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sysUserMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().toUpperCase().contains("DELETED"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(0L));
    }

    private static SysUser user(Long id, Long deleted) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setName("历史负责人");
        user.setDeleted(deleted);
        return user;
    }
}
