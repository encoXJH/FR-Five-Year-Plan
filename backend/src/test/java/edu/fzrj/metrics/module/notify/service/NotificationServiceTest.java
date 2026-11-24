package edu.fzrj.metrics.module.notify.service;

import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationMapper notificationMapper;
    @InjectMocks
    private NotificationService notificationService;

    @Test
    void 首次写入成功并补齐飞书推送默认值() {
        Notification notification = new Notification();
        notification.setDedupKey("BOT:TEST:1");
        when(notificationMapper.insert(notification)).thenReturn(1);

        assertTrue(notificationService.insertOnce(notification));
        assertNotNull(notification.getCreatedAt());
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", notification.getFeishuStatus());
        org.junit.jupiter.api.Assertions.assertEquals(0, notification.getFeishuRetryCount());
    }

    @Test
    void 去重键冲突时返回false() {
        Notification notification = new Notification();
        when(notificationMapper.insert(notification)).thenThrow(new DuplicateKeyException("duplicate"));

        assertFalse(notificationService.insertOnce(notification));
    }
}
