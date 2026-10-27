package edu.fzrj.metrics.module.integration.service.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书消息卡片构建纯逻辑单测（AppLink 拼接与卡片 JSON）
 */
class FeishuCardBuilderTest {

    @Test
    void appLink拼接平台地址并URL编码() {
        String link = FeishuCardBuilder.appLink("https://metrics.example.com", "/tasks/123/submit");
        assertEquals("https://applink.feishu.cn/client/web_url/open?url="
                + "https%3A%2F%2Fmetrics.example.com%2Ftasks%2F123%2Fsubmit", link);
    }

    @Test
    void 未配置平台地址时返回站内路由原值() {
        assertEquals("/tasks/123/submit", FeishuCardBuilder.appLink("", "/tasks/123/submit"));
    }

    @Test
    void 空链接返回null() {
        assertNull(FeishuCardBuilder.appLink("https://metrics.example.com", null));
        assertNull(FeishuCardBuilder.appLink("https://metrics.example.com", "  "));
    }

    @Test
    void 卡片包含标题正文与按钮链接() {
        String card = FeishuCardBuilder.buildCard("任务下发", "请查收", "https://applink.feishu.cn/x");
        assertTrue(card.contains("\"wide_screen_mode\":true"));
        assertTrue(card.contains("\"content\":\"任务下发\""));
        assertTrue(card.contains("\"content\":\"请查收\""));
        assertTrue(card.contains("\"url\":\"https://applink.feishu.cn/x\""));
        assertTrue(card.contains("查看详情"));
    }

    @Test
    void 无链接时不带按钮() {
        String card = FeishuCardBuilder.buildCard("标题", "正文", null);
        assertTrue(card.contains("\"content\":\"正文\""));
        assertFalse(card.contains("\"actions\""), card);
    }
}
