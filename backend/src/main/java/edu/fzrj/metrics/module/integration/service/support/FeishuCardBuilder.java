package edu.fzrj.metrics.module.integration.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 飞书互动卡片构建（纯逻辑）。卡片格式见官方文档：
 * https://open.feishu.cn/document/server-docs/im-v1/message-content-description/create-apps
 * 卡片按钮链接用飞书 AppLink 打开平台网页应用对应页面：
 * https://open.feishu.cn/document/server-docs/deeplink-guide/applink-protocol/open-webapp
 */
public final class FeishuCardBuilder {

    /** 飞书 AppLink：在飞书客户端内打开指定网页 */
    public static final String APPLINK_OPEN_WEB_URL = "https://applink.feishu.cn/client/web_url/open?url=";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeishuCardBuilder() {
    }

    /**
     * 把站内路由（如 /tasks/123/submit）拼成 AppLink。
     *
     * @param appBaseUrl 平台对外基础地址；未配置时返回站内路由原值（兜底）
     */
    public static String appLink(String appBaseUrl, String link) {
        if (!StringUtils.hasText(link)) {
            return null;
        }
        if (!StringUtils.hasText(appBaseUrl)) {
            return link;
        }
        String fullUrl = appBaseUrl.endsWith("/") || link.startsWith("/")
                ? appBaseUrl + link : appBaseUrl + "/" + link;
        return APPLINK_OPEN_WEB_URL + URLEncoder.encode(fullUrl, StandardCharsets.UTF_8);
    }

    /** 构建互动卡片 JSON 字符串（标题 + 正文 + 「查看详情」按钮，link 为空时不带按钮） */
    public static String buildCard(String title, String content, String appLinkUrl) {
        ObjectNode card = MAPPER.createObjectNode();
        card.putObject("config").put("wide_screen_mode", true);
        card.putObject("header").put("template", "blue")
                .putObject("title").put("tag", "plain_text").put("content", title == null ? "" : title);
        var elements = card.putArray("elements");
        elements.addObject().put("tag", "div")
                .putObject("text").put("tag", "lark_md").put("content", content == null ? "" : content);
        if (StringUtils.hasText(appLinkUrl)) {
            elements.addObject().put("tag", "action")
                    .putArray("actions").addObject()
                    .put("tag", "button").put("type", "primary").put("url", appLinkUrl)
                    .putObject("text").put("tag", "plain_text").put("content", "查看详情");
        }
        return card.toString();
    }
}
