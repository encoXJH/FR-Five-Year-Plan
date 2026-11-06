package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.audit.mapper.AuditLogMapper;
import edu.fzrj.metrics.module.integration.entity.FeishuBindingTicket;
import edu.fzrj.metrics.module.integration.mapper.FeishuBindingTicketMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.entity.UserRole;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.mapper.UserRoleMapper;
import edu.fzrj.metrics.security.AuthService;
import edu.fzrj.metrics.security.dto.TokenResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuBindingServiceTest {

    @Mock private FeishuBindingTicketMapper ticketMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private UserRoleMapper userRoleMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private AuthService authService;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private FeishuBindingService service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, FeishuBindingTicket.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, UserRole.class);
    }

    @Test
    void 严格匹配后绑定已有账号并签发Token() throws Exception {
        FeishuBindingTicket ticket = ticket();
        SysUser user = user();
        TokenResponse token = token();
        when(ticketMapper.update(any(), any())).thenReturn(1);
        when(ticketMapper.selectById(9L)).thenReturn(ticket);
        when(sysUserMapper.selectOne(any())).thenReturn(user, null);
        when(sysUserMapper.update(any(), any())).thenReturn(1);
        when(authService.loginWithUser(user, "WEB", "127.0.0.1")).thenReturn(token);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        TokenResponse response = service.completeBinding(9L, "张三", "10001", "WEB", "127.0.0.1");

        assertEquals("access", response.getAccessToken());
        assertEquals("ou_new", user.getFeishuOpenId());
        assertEquals("un_1", user.getFeishuUnionId());
        verify(auditLogMapper).insert(any(edu.fzrj.metrics.module.audit.entity.AuditLog.class));
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void 票据消费失败时不查询用户也不签发Token() {
        when(ticketMapper.update(any(), any())).thenReturn(0);
        BizException error = assertThrows(BizException.class,
                () -> service.completeBinding(9L, "张三", "10001", "WEB", "127.0.0.1"));
        assertEquals(20006, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).selectOne(any());
        verify(authService, never()).loginWithUser(any(), anyString(), anyString());
    }

    @Test
    void 不存在严格匹配的已有启用账号时拒绝绑定() {
        when(ticketMapper.update(any(), any())).thenReturn(1);
        when(ticketMapper.selectById(9L)).thenReturn(ticket());
        when(sysUserMapper.selectOne(any())).thenReturn(null);
        BizException error = assertThrows(BizException.class,
                () -> service.completeBinding(9L, "张三", "10001", "WEB", "127.0.0.1"));
        assertEquals(20007, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).update(any(), any());
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void 平台姓名必须精确一致() {
        SysUser user = user();
        user.setName("张三丰");
        when(ticketMapper.update(any(), any())).thenReturn(1);
        when(ticketMapper.selectById(9L)).thenReturn(ticket());
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        BizException error = assertThrows(BizException.class,
                () -> service.completeBinding(9L, "张三", "10001", "WEB", "127.0.0.1"));
        assertEquals(20007, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).update(any(), any());
    }

    @Test
    void 已绑定其他飞书账号的平台用户不可被覆盖() {
        SysUser user = user();
        user.setFeishuOpenId("ou_other");
        when(ticketMapper.update(any(), any())).thenReturn(1);
        when(ticketMapper.selectById(9L)).thenReturn(ticket());
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        BizException error = assertThrows(BizException.class,
                () -> service.completeBinding(9L, "张三", "10001", "WEB", "127.0.0.1"));
        assertEquals(20006, error.getErrorCode().getCode());
        verify(sysUserMapper, never()).update(any(), any());
    }

    @Test
    void openId已属于其他用户或条件更新冲突时拒绝() {
        SysUser user = user();
        SysUser other = user();
        other.setId(2L);
        when(ticketMapper.update(any(), any())).thenReturn(1);
        when(ticketMapper.selectById(9L)).thenReturn(ticket());
        when(sysUserMapper.selectOne(any())).thenReturn(user, other);
        assertThrows(BizException.class,
                () -> service.completeBinding(9L, "张三", "10001", "WEB", "127.0.0.1"));
        verify(sysUserMapper, never()).update(any(), any());
    }

    private static FeishuBindingTicket ticket() {
        FeishuBindingTicket ticket = new FeishuBindingTicket();
        ticket.setId(9L);
        ticket.setFeishuOpenId("ou_new");
        ticket.setFeishuUnionId("un_1");
        ticket.setFeishuName("张三");
        ticket.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return ticket;
    }

    private static SysUser user() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("10001");
        user.setName("张三");
        user.setIsEnabled(true);
        user.setVersion(0);
        return user;
    }

    private static TokenResponse token() {
        LocalDateTime now = LocalDateTime.now();
        return new TokenResponse("access", now.plusHours(1), "refresh", now.plusDays(7),
                new TokenResponse.TokenUser(1L, "张三", null, List.of("ADMIN")));
    }
}
