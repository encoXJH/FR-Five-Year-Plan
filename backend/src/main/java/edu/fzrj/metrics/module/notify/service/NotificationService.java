package edu.fzrj.metrics.module.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.notify.dto.NotifyDtos;
import edu.fzrj.metrics.module.notify.entity.Notification;
import edu.fzrj.metrics.module.notify.mapper.NotificationMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 站内消息（API 约定 15.9）：列表 / 标记已读 / 全部已读。仅本人消息可见、可操作。
 */
@Service
public class NotificationService {

    private static final Set<String> SORT_FIELDS = Set.of("created_at", "id");

    private final NotificationMapper notificationMapper;

    public NotificationService(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    public NotifyDtos.NotificationPage page(NotifyDtos.NotificationQuery query) {
        Long userId = SecurityUtils.getUserId();
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .isNull(Boolean.TRUE.equals(query.getUnreadOnly()), Notification::getReadAt)
                .eq(query.getType() != null && !query.getType().isBlank(),
                        Notification::getType, query.getType())
                .orderByDesc(Notification::getCreatedAt);
        Page<Notification> page = notificationMapper.selectPage(query.toPage(SORT_FIELDS), wrapper);
        long unread = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt));
        List<NotifyDtos.NotificationItem> items = page.getRecords().stream()
                .map(this::toItem)
                .toList();
        return new NotifyDtos.NotificationPage(items, page.getTotal(), query.getPage(), query.getPageSize(), unread);
    }

    /** 标记已读：仅本人消息（越权 20005）；重复已读幂等 */
    public NotifyDtos.NotificationItem markRead(Long notificationId) {
        Long userId = SecurityUtils.getUserId();
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "消息不存在");
        }
        if (!userId.equals(notification.getUserId())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            notificationMapper.updateById(notification);
        }
        return toItem(notification);
    }

    /** 全部标记已读：仅本人；返回本次新标记的条数 */
    public NotifyDtos.ReadAllResult markAllRead() {
        Long userId = SecurityUtils.getUserId();
        int updated = notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .isNull(Notification::getReadAt)
                .set(Notification::getReadAt, LocalDateTime.now()));
        return new NotifyDtos.ReadAllResult((long) updated);
    }

    private NotifyDtos.NotificationItem toItem(Notification n) {
        return new NotifyDtos.NotificationItem(n.getId(), n.getType(), n.getTitle(), n.getContent(),
                n.getBizType(), n.getBizId(), n.getLink(), n.getReadAt(), n.getCreatedAt());
    }
}
