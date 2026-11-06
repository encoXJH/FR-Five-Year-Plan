package edu.fzrj.metrics.module.integration.service;

import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.integration.client.FeishuClient;
import edu.fzrj.metrics.module.integration.client.TeacherApiClient;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.entity.FeishuBindingTicket;
import edu.fzrj.metrics.module.integration.mapper.FeishuBindingTicketMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.security.AuthService;
import edu.fzrj.metrics.security.dto.FeishuAuthDtos;
import edu.fzrj.metrics.security.dto.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuLoginServiceTest {

    @Mock private FeishuConfig config;
    @Mock private FeishuClient feishuClient;
    @Mock private TeacherApiClient teacherApiClient;
    @Mock private FeishuBindingTicketMapper ticketMapper;
    @Mock private FeishuBindingService bindingService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private AuthService authService;
    @InjectMocks private FeishuLoginService service;

    @BeforeEach
    void configured() {
        lenient().when(config.isConfigured()).thenReturn(true);
    }

    @Test
    void 已绑定用户直接登录且不调用教师接口() {
        SysUser user = user("10001", "张三", "ou_1");
        TokenResponse token = token();
        mockFeishuIdentity("ou_1", "张三");
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(authService.loginWithUser(user, "WEB", "127.0.0.1")).thenReturn(token);

        FeishuAuthDtos.FeishuLoginResponse response = service.login("code", "WEB", "127.0.0.1");

        assertFalse(response.bindRequired());
        assertEquals("access", response.accessToken());
        verify(teacherApiClient, never()).verify(anyString(), anyString());
        verify(ticketMapper, never()).insert(any(FeishuBindingTicket.class));
    }

    @Test
    void 已绑定但停用的账号拒绝登录() {
        SysUser user = user("10001", "张三", "ou_1");
        user.setIsEnabled(false);
        mockFeishuIdentity("ou_1", "张三");
        when(sysUserMapper.selectOne(any())).thenReturn(user);

        BizException error = assertThrows(BizException.class,
                () -> service.login("code", "WEB", "127.0.0.1"));
        assertEquals(20007, error.getErrorCode().getCode());
        verify(ticketMapper, never()).insert(any(FeishuBindingTicket.class));
    }

    @Test
    void 未绑定只签发一次性票据不签发平台Token() {
        mockFeishuIdentity("ou_new", "张三");
        when(sysUserMapper.selectOne(any())).thenReturn(null);
        when(config.getBindingTicketExpireMinutes()).thenReturn(10);
        when(ticketMapper.insert(any(FeishuBindingTicket.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, FeishuBindingTicket.class).setId(9L);
            return 1;
        });

        FeishuAuthDtos.FeishuLoginResponse response = service.login("code", "WEB", "127.0.0.1");

        assertTrue(response.bindRequired());
        assertNull(response.accessToken());
        assertEquals(43, response.bindingTicket().length());
        ArgumentCaptor<FeishuBindingTicket> captor = ArgumentCaptor.forClass(FeishuBindingTicket.class);
        verify(ticketMapper).insert(captor.capture());
        FeishuBindingTicket stored = captor.getValue();
        assertEquals(64, stored.getTicketHash().length());
        assertNotEquals(response.bindingTicket(), stored.getTicketHash());
        assertEquals("ou_new", stored.getFeishuOpenId());
        assertEquals("张三", stored.getFeishuName());
        verify(authService, never()).loginWithUser(any(), anyString(), anyString());
    }

    @Test
    void 无效或过期票据不会调用教师接口() {
        when(ticketMapper.selectOne(any())).thenReturn(null);
        FeishuAuthDtos.FeishuBindRequest request = new FeishuAuthDtos.FeishuBindRequest(
                "ticket", "张三", "10001");
        assertThrows(BizException.class, () -> service.bind(request, "WEB", "127.0.0.1"));

        FeishuBindingTicket expired = ticket();
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(ticketMapper.selectOne(any())).thenReturn(expired);
        assertThrows(BizException.class, () -> service.bind(request, "WEB", "127.0.0.1"));
        verify(teacherApiClient, never()).verify(anyString(), anyString());
        verify(bindingService, never()).completeBinding(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 飞书姓名与填写姓名不一致时拒绝且不调教师接口() {
        FeishuBindingTicket ticket = ticket();
        ticket.setFeishuName("李四");
        when(ticketMapper.selectOne(any())).thenReturn(ticket);

        BizException error = assertThrows(BizException.class, () -> service.bind(
                new FeishuAuthDtos.FeishuBindRequest("ticket", "张三", "10001"), "WEB", "127.0.0.1"));
        assertEquals(20006, error.getErrorCode().getCode());
        verify(teacherApiClient, never()).verify(anyString(), anyString());
    }

    @Test
    void 教师接口异常或核验失败时不进入绑定事务() {
        when(ticketMapper.selectOne(any())).thenReturn(ticket());
        doThrow(new TeacherApiClient.TeacherApiException("unavailable"))
                .when(teacherApiClient).verify("张三", "10001");
        FeishuAuthDtos.FeishuBindRequest request = new FeishuAuthDtos.FeishuBindRequest(
                "ticket", "张三", "10001");
        BizException unavailable = assertThrows(BizException.class,
                () -> service.bind(request, "WEB", "127.0.0.1"));
        assertEquals(90003, unavailable.getErrorCode().getCode());

        doReturn(null).when(teacherApiClient).verify("张三", "10001");
        BizException mismatch = assertThrows(BizException.class,
                () -> service.bind(request, "WEB", "127.0.0.1"));
        assertEquals(20006, mismatch.getErrorCode().getCode());
        verify(bindingService, never()).completeBinding(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 校内核验成功后进入绑定事务并返回Token() {
        when(ticketMapper.selectOne(any())).thenReturn(ticket());
        when(teacherApiClient.verify("张三", "10001"))
                .thenReturn(new TeacherApiClient.TeacherIdentity("10001", "张三", "在职在岗", "党政办公室"));
        when(bindingService.completeBinding(9L, "张三", "10001", "WEB", "127.0.0.1"))
                .thenReturn(token());

        TokenResponse response = service.bind(
                new FeishuAuthDtos.FeishuBindRequest("ticket", "张三", "10001"), "WEB", "127.0.0.1");
        assertEquals("access", response.getAccessToken());
    }

    private void mockFeishuIdentity(String openId, String name) {
        when(feishuClient.exchangeUserAccessToken("code")).thenReturn("user-token");
        when(feishuClient.getLoginUserInfo("user-token"))
                .thenReturn(new FeishuClient.FeishuUserInfo(openId, "un_1", name, null));
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

    private static SysUser user(String username, String name, String openId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername(username);
        user.setName(name);
        user.setFeishuOpenId(openId);
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
