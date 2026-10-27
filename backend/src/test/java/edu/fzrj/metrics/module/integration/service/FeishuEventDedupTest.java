package edu.fzrj.metrics.module.integration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.entity.FeishuEvent;
import edu.fzrj.metrics.module.integration.mapper.FeishuEventMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 飞书事件去重单测：feishu_event 的 event_id 唯一索引防并发，
 * 首次插入返回事件（进入异步处理），重复事件（DuplicateKeyException）返回 null。
 */
class FeishuEventDedupTest {

    private final FeishuEventMapper feishuEventMapper = mock(FeishuEventMapper.class);
    private final FeishuEventService service = new FeishuEventService(
            mock(FeishuConfig.class), feishuEventMapper, mock(FeishuEventExecutor.class), new ObjectMapper());

    @Test
    void 首次事件落库并返回() {
        when(feishuEventMapper.insert(any(FeishuEvent.class))).thenReturn(1);

        FeishuEvent event = service.tryRecord("evt-1", "im.message.receive_v1", "{}");

        assertNotNull(event);
        assertEquals("evt-1", event.getEventId());
        assertEquals("im.message.receive_v1", event.getEventType());
        assertEquals("RECEIVED", event.getStatus());
        verify(feishuEventMapper, times(1)).insert(any(FeishuEvent.class));
    }

    @Test
    void 重复事件返回null不再处理() {
        when(feishuEventMapper.insert(any(FeishuEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_feishu_event"));

        FeishuEvent event = service.tryRecord("evt-1", "im.message.receive_v1", "{}");

        assertNull(event);
        verify(feishuEventMapper, times(1)).insert(any(FeishuEvent.class));
    }

    @Test
    void 事件类型为空落库为unknown() {
        when(feishuEventMapper.insert(any(FeishuEvent.class))).thenReturn(1);

        FeishuEvent event = service.tryRecord("evt-2", null, "{}");

        assertNotNull(event);
        assertEquals("unknown", event.getEventType());
    }
}
