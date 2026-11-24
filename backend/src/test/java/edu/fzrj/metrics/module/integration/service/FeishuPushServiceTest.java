package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import edu.fzrj.metrics.module.integration.client.FeishuClient;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import edu.fzrj.metrics.module.system.service.SettingService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuPushServiceTest {

    @Mock private FeishuConfig config;
    @Mock private FeishuClient feishuClient;
    @Mock private NotificationMapper notificationMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private SettingService settingService;
    @InjectMocks private FeishuPushService service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Notification.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @BeforeEach
    void enablePushByDefault() {
        lenient().when(settingService.getValue(eq(FeishuPushService.PUSH_SETTING_KEY), anyString()))
                .thenReturn("true");
    }

    @Test
    void 数据库关闭优先于环境变量且轮询不读取或发送消息() {
        when(config.isPushEnabled()).thenReturn(true);
        when(settingService.getValue(FeishuPushService.PUSH_SETTING_KEY, "true")).thenReturn("false");

        assertFalse(service.isPushEnabled());
        assertEquals(0, service.pollOnce());

        verifyNoInteractions(notificationMapper, sysUserMapper, feishuClient);
    }

    @Test
    void 设置缺行时回退到关闭的环境变量() {
        when(config.isPushEnabled()).thenReturn(false);
        when(settingService.getValue(FeishuPushService.PUSH_SETTING_KEY, "false")).thenReturn("false");

        assertFalse(service.isPushEnabled());
        assertEquals(0, service.pollOnce());
        verifyNoInteractions(notificationMapper, feishuClient);
    }

    @Test
    void 实际发送前关闭会保留待推送并释放租约() {
        when(settingService.getValue(eq(FeishuPushService.PUSH_SETTING_KEY), anyString()))
                .thenReturn("true", "true", "true", "false");
        when(notificationMapper.selectList(any())).thenReturn(List.of(notification(77L)));
        when(notificationMapper.update(any(), any())).thenReturn(1, 1);
        when(config.isConfigured()).thenReturn(true);
        when(sysUserMapper.selectById(9L)).thenReturn(receiver(true, 0L, "ou_9"));

        assertEquals(0, service.pollOnce());

        verifyNoInteractions(feishuClient);
        LambdaUpdateWrapper<Notification> release = capturedFinalUpdate();
        assertTrue(release.getSqlSet().contains("feishu_next_retry_at"));
        assertTrue(release.getParamNameValuePairs().containsValue(null));
    }

    @Test
    void 抢占失败时不查询接收人也不发送() {
        when(notificationMapper.selectList(any())).thenReturn(List.of(notification(77L)));
        when(notificationMapper.update(any(), any())).thenReturn(0);

        assertEquals(0, service.pollOnce());

        verifyNoInteractions(sysUserMapper, feishuClient);
    }

    @Test
    void 启用且绑定的接收人使用三参数发送和稳定UUID() {
        Notification notification = notification(77L);
        String expectedUuid = UUID.nameUUIDFromBytes("notification:77".getBytes(StandardCharsets.UTF_8)).toString();
        when(notificationMapper.selectList(any())).thenReturn(List.of(notification));
        when(notificationMapper.update(any(), any())).thenReturn(1, 1);
        when(config.isConfigured()).thenReturn(true);
        when(config.getAppBaseUrl()).thenReturn("https://metrics.example.com");
        when(sysUserMapper.selectById(9L)).thenReturn(receiver(true, 0L, "ou_9"));
        when(feishuClient.sendCardMessage(eq("ou_9"), anyString(), eq(expectedUuid))).thenReturn(true);

        assertEquals(1, service.pollOnce());

        verify(feishuClient).sendCardMessage(eq("ou_9"),
                argThat(card -> card.contains("测试标题") && card.contains("测试正文")), eq(expectedUuid));
        verify(feishuClient, never()).sendCardMessage(any(), any());
    }

    @ParameterizedTest(name = "{0}接收人置为SKIPPED")
    @MethodSource("skippedReceivers")
    void 无效接收人置为SKIPPED且不发送(String scenario, SysUser receiver, String reason) {
        when(notificationMapper.selectList(any())).thenReturn(List.of(notification(77L)));
        when(notificationMapper.update(any(), any())).thenReturn(1, 1);
        when(config.isConfigured()).thenReturn(true);
        when(sysUserMapper.selectById(9L)).thenReturn(receiver);

        assertEquals(1, service.pollOnce());

        verifyNoInteractions(feishuClient);
        LambdaUpdateWrapper<Notification> finalUpdate = capturedFinalUpdate();
        assertTrue(finalUpdate.getSqlSet().contains("feishu_status"));
        assertTrue(finalUpdate.getParamNameValuePairs().containsValue("SKIPPED"));
        assertTrue(finalUpdate.getParamNameValuePairs().containsValue(reason));
    }

    private static Stream<Arguments> skippedReceivers() {
        return Stream.of(
                Arguments.of("停用", receiver(false, 0L, "ou_disabled"), "接收账号已停用"),
                Arguments.of("未绑定", receiver(true, 0L, " "), "接收人未绑定飞书"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<Notification> capturedFinalUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<Notification>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(notificationMapper, times(2)).update(any(), captor.capture());
        return captor.getAllValues().get(1);
    }

    private static Notification notification(Long id) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUserId(9L);
        notification.setTitle("测试标题");
        notification.setContent("测试正文");
        notification.setLink("/todos");
        return notification;
    }

    private static SysUser receiver(boolean enabled, Long deleted, String openId) {
        SysUser receiver = new SysUser();
        receiver.setId(9L);
        receiver.setIsEnabled(enabled);
        receiver.setDeleted(deleted);
        receiver.setFeishuOpenId(openId);
        return receiver;
    }
}
