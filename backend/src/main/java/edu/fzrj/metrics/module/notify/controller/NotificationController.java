package edu.fzrj.metrics.module.notify.controller;

import edu.fzrj.metrics.module.notify.dto.NotifyDtos;
import edu.fzrj.metrics.module.notify.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内消息（API 约定 15.9）
 */
@Tag(name = "站内消息")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "站内消息列表",
            description = "已登录；仅本人消息；unreadOnly=true 只查未读；响应含 unreadCount 未读总数")
    @GetMapping
    public NotifyDtos.NotificationPage page(@ModelAttribute NotifyDtos.NotificationQuery query) {
        return notificationService.page(query);
    }

    @Operation(summary = "标记已读",
            description = "已登录；仅本人消息（越权 20005）；重复已读幂等")
    @PostMapping("/{notificationId}/read")
    public NotifyDtos.NotificationItem markRead(@PathVariable Long notificationId) {
        return notificationService.markRead(notificationId);
    }

    @Operation(summary = "全部标记为已读",
            description = "已登录；仅本人消息；返回本次新标记的条数 readCount")
    @PostMapping("/read-all")
    public NotifyDtos.ReadAllResult markAllRead() {
        return notificationService.markAllRead();
    }
}
