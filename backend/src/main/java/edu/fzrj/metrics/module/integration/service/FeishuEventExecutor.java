package edu.fzrj.metrics.module.integration.service;

import edu.fzrj.metrics.module.integration.entity.FeishuEvent;
import edu.fzrj.metrics.module.integration.mapper.FeishuEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 飞书事件异步处理（@Async）：回调入口必须 3 秒内返回，耗时处理在这里执行。
 * 当前阶段不消费具体业务事件，落库后即置 PROCESSED；后续按需在此分发事件类型。
 */
@Slf4j
@Component
public class FeishuEventExecutor {

    private final FeishuEventMapper feishuEventMapper;

    public FeishuEventExecutor(FeishuEventMapper feishuEventMapper) {
        this.feishuEventMapper = feishuEventMapper;
    }

    @Async
    public void processAsync(Long eventDbId) {
        try {
            FeishuEvent event = feishuEventMapper.selectById(eventDbId);
            if (event == null || !"RECEIVED".equals(event.getStatus())) {
                return;
            }
            // 事件业务分发入口（当前无订阅的业务事件类型，直接置已处理）
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            feishuEventMapper.updateById(event);
        } catch (Exception e) {
            log.error("飞书事件处理失败 eventDbId={}", eventDbId, e);
            try {
                FeishuEvent event = feishuEventMapper.selectById(eventDbId);
                if (event != null) {
                    event.setStatus("FAILED");
                    event.setProcessedAt(LocalDateTime.now());
                    feishuEventMapper.updateById(event);
                }
            } catch (Exception inner) {
                log.error("飞书事件失败状态回写失败 eventDbId={}", eventDbId, inner);
            }
        }
    }
}
