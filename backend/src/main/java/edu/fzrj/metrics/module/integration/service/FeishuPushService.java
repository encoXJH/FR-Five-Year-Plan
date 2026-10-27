package edu.fzrj.metrics.module.integration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import edu.fzrj.metrics.module.integration.client.FeishuClient;
import edu.fzrj.metrics.module.integration.config.FeishuConfig;
import edu.fzrj.metrics.module.integration.service.support.FeishuCardBuilder;
import edu.fzrj.metrics.module.integration.service.support.FeishuPushRules;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.module.system.entity.SysUser;
import edu.fzrj.metrics.module.system.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 飞书消息推送（API 约定第 11 章）：定时轮询消费 notification 表 feishuStatus=PENDING 的消息，
 * 以互动卡片发送，卡片按钮用 AppLink 打开平台对应页面（notification.link）。
 * 失败按指数退避最多重试 3 次；终态 SENT / FAILED / SKIPPED（未配置飞书或接收人未绑定）。
 * 推送失败只记日志与表内状态，绝不影响主业务（站内消息照常生成）。
 */
@Slf4j
@Service
public class FeishuPushService {

    private final FeishuConfig config;
    private final FeishuClient feishuClient;
    private final NotificationMapper notificationMapper;
    private final SysUserMapper sysUserMapper;

    public FeishuPushService(FeishuConfig config, FeishuClient feishuClient,
                             NotificationMapper notificationMapper, SysUserMapper sysUserMapper) {
        this.config = config;
        this.feishuClient = feishuClient;
        this.notificationMapper = notificationMapper;
        this.sysUserMapper = sysUserMapper;
    }

    /** 轮询入口：默认每 60 秒（app.feishu.push-interval-ms 可配） */
    @Scheduled(fixedDelayString = "${app.feishu.push-interval-ms:60000}",
            initialDelayString = "${app.feishu.push-initial-delay-ms:15000}")
    public void scheduledPoll() {
        if (!config.isPushEnabled()) {
            return;
        }
        try {
            pollOnce();
        } catch (Exception e) {
            log.error("飞书消息推送轮询失败", e);
        }
    }

    /** 单轮推送；返回本轮处理条数（独立成方法便于测试与手工触发） */
    public int pollOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<Notification> batch = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getFeishuStatus, "PENDING")
                .and(w -> w.isNull(Notification::getFeishuNextRetryAt)
                        .or().le(Notification::getFeishuNextRetryAt, now))
                .orderByAsc(Notification::getId)
                .last("LIMIT " + Math.max(1, config.getPushBatchSize())));
        int processed = 0;
        for (Notification notification : batch) {
            try {
                pushOne(notification, now);
                processed++;
            } catch (Exception e) {
                // 单条失败不影响整批
                log.error("飞书推送处理失败 notificationId={}", notification.getId(), e);
            }
        }
        return processed;
    }

    /** 推送单条消息（状态机见 FeishuPushRules） */
    private void pushOne(Notification notification, LocalDateTime now) {
        boolean configured = config.isConfigured();
        SysUser receiver = configured ? sysUserMapper.selectById(notification.getUserId()) : null;
        String openId = receiver == null ? null : receiver.getFeishuOpenId();
        boolean hasOpenId = StringUtils.hasText(openId);

        boolean success = false;
        String error = null;
        if (configured && hasOpenId) {
            String card = FeishuCardBuilder.buildCard(notification.getTitle(), notification.getContent(),
                    FeishuCardBuilder.appLink(config.getAppBaseUrl(), notification.getLink()));
            success = feishuClient.sendCardMessage(openId, card);
            if (!success) {
                error = "飞书消息接口返回失败";
            }
        }

        FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(
                configured, hasOpenId,
                notification.getFeishuRetryCount() == null ? 0 : notification.getFeishuRetryCount(),
                success, now);
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notification.getId())
                .eq(Notification::getFeishuStatus, "PENDING")
                .set(Notification::getFeishuStatus, outcome.status())
                .set(Notification::getFeishuRetryCount, outcome.retryCount())
                .set(Notification::getFeishuNextRetryAt, outcome.nextRetryAt())
                .set(Notification::getFeishuLastError, error));
        if ("FAILED".equals(outcome.status())) {
            log.warn("飞书推送终态失败 notificationId={} retry={} error={}",
                    notification.getId(), outcome.retryCount(), error);
        }
    }
}
