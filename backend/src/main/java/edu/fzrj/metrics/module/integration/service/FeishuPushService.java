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
import edu.fzrj.metrics.module.system.service.SettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 飞书消息推送（API 约定第 11 章）：定时轮询消费 notification 表 feishuStatus=PENDING 的消息，
 * 以互动卡片发送，卡片按钮用 AppLink 打开平台对应页面（notification.link）。
 * 失败按指数退避最多重试 3 次；终态 SENT / FAILED / SKIPPED（未配置飞书或接收人未绑定）。
 * 推送失败只记日志与表内状态，绝不影响主业务（站内消息照常生成）。
 */
@Slf4j
@Service
public class FeishuPushService {

    public static final String PUSH_SETTING_KEY = "feishu.pushEnabled";

    private final FeishuConfig config;
    private final FeishuClient feishuClient;
    private final NotificationMapper notificationMapper;
    private final SysUserMapper sysUserMapper;
    private final SettingService settingService;

    public FeishuPushService(FeishuConfig config, FeishuClient feishuClient,
                             NotificationMapper notificationMapper, SysUserMapper sysUserMapper,
                             SettingService settingService) {
        this.config = config;
        this.feishuClient = feishuClient;
        this.notificationMapper = notificationMapper;
        this.sysUserMapper = sysUserMapper;
        this.settingService = settingService;
    }

    /** 数据库开关优先，缺行时回退环境变量；非法值按关闭处理。 */
    public boolean isPushEnabled() {
        String value = settingService.getValue(PUSH_SETTING_KEY, Boolean.toString(config.isPushEnabled()));
        return "true".equalsIgnoreCase(value);
    }

    /** 轮询入口：默认每 60 秒（app.feishu.push-interval-ms 可配） */
    @Scheduled(fixedDelayString = "${app.feishu.push-interval-ms:60000}",
            initialDelayString = "${app.feishu.push-initial-delay-ms:15000}")
    public void scheduledPoll() {
        if (!isPushEnabled()) {
            return;
        }
        try {
            pollOnce();
        } catch (Exception e) {
            log.error("飞书消息推送轮询失败", e);
        }
    }

    /** 单轮推送；返回本轮实际抢占并处理的条数（独立成方法便于测试与手工触发） */
    public int pollOnce() {
        if (!isPushEnabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        List<Notification> batch = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getFeishuStatus, "PENDING")
                .and(w -> w.isNull(Notification::getFeishuNextRetryAt)
                        .or().le(Notification::getFeishuNextRetryAt, now))
                .orderByAsc(Notification::getId)
                .last("LIMIT " + Math.max(1, config.getPushBatchSize())));
        int processed = 0;
        for (Notification notification : batch) {
            try {
                if (pushOne(notification, now)) {
                    processed++;
                }
            } catch (Exception e) {
                // 单条失败不影响整批
                log.error("飞书推送处理失败 notificationId={}", notification.getId(), e);
            }
        }
        return processed;
    }

    /** 推送单条消息；nextRetryAt 同时作为五分钟租约，避免多实例重复发送。 */
    private boolean pushOne(Notification notification, LocalDateTime now) {
        if (!isPushEnabled()) {
            return false;
        }
        LocalDateTime leaseUntil = now.plusMinutes(5).truncatedTo(ChronoUnit.MILLIS);
        int claimed = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notification.getId())
                .eq(Notification::getFeishuStatus, "PENDING")
                .and(w -> w.isNull(Notification::getFeishuNextRetryAt)
                        .or().le(Notification::getFeishuNextRetryAt, now))
                .set(Notification::getFeishuNextRetryAt, leaseUntil));
        if (claimed != 1) {
            return false;
        }
        if (!isPushEnabled()) {
            releaseLease(notification.getId(), leaseUntil);
            return false;
        }

        boolean configured = config.isConfigured();
        SysUser receiver = sysUserMapper.selectById(notification.getUserId());
        boolean active = receiver != null && Boolean.TRUE.equals(receiver.getIsEnabled())
                && receiver.getDeleted() != null && receiver.getDeleted() == 0L;
        String openId = active ? receiver.getFeishuOpenId() : null;
        boolean hasOpenId = StringUtils.hasText(openId);
        String skipReason = null;
        if (!configured) {
            skipReason = "未配置飞书应用凭证";
        } else if (receiver == null || receiver.getDeleted() == null || receiver.getDeleted() != 0L) {
            skipReason = "接收账号不存在或已删除";
        } else if (!Boolean.TRUE.equals(receiver.getIsEnabled())) {
            skipReason = "接收账号已停用";
        } else if (!hasOpenId) {
            skipReason = "接收人未绑定飞书";
        }

        boolean success = false;
        String error = skipReason;
        if (skipReason == null) {
            if (!isPushEnabled()) {
                releaseLease(notification.getId(), leaseUntil);
                return false;
            }
            String card = FeishuCardBuilder.buildCard(notification.getTitle(), notification.getContent(),
                    FeishuCardBuilder.appLink(config.getAppBaseUrl(), notification.getLink()));
            String uuid = UUID.nameUUIDFromBytes(("notification:" + notification.getId())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            success = feishuClient.sendCardMessage(openId, card, uuid);
            if (!success) {
                error = "飞书消息接口返回失败";
            }
        }

        FeishuPushRules.Outcome outcome = FeishuPushRules.afterAttempt(
                skipReason == null, hasOpenId,
                notification.getFeishuRetryCount() == null ? 0 : notification.getFeishuRetryCount(),
                success, now);
        if (skipReason != null) {
            outcome = new FeishuPushRules.Outcome("SKIPPED",
                    notification.getFeishuRetryCount() == null ? 0 : notification.getFeishuRetryCount(), null);
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notification.getId())
                .eq(Notification::getFeishuStatus, "PENDING")
                .eq(Notification::getFeishuNextRetryAt, leaseUntil)
                .set(Notification::getFeishuStatus, outcome.status())
                .set(Notification::getFeishuRetryCount, outcome.retryCount())
                .set(Notification::getFeishuNextRetryAt, outcome.nextRetryAt())
                .set(Notification::getFeishuLastError, error));
        if ("FAILED".equals(outcome.status())) {
            log.warn("飞书推送终态失败 notificationId={} retry={} error={}",
                    notification.getId(), outcome.retryCount(), error);
        }
        return true;
    }

    /** 开关在处理中途关闭时保留 PENDING，仅释放本次租约。 */
    private void releaseLease(Long notificationId, LocalDateTime leaseUntil) {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notificationId)
                .eq(Notification::getFeishuStatus, "PENDING")
                .eq(Notification::getFeishuNextRetryAt, leaseUntil)
                .set(Notification::getFeishuNextRetryAt, null));
    }
}
